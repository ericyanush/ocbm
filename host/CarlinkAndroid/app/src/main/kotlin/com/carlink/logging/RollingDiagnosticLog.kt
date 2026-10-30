package com.carlink.logging

import android.content.Context
import android.app.Application
import android.os.Build
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.withLock

/** Bounded, asynchronous app-private diagnostics with SAF export to removable storage. */
class RollingDiagnosticLog private constructor(
    context: Context,
    appVersion: String,
) {
    private val appContext = context.applicationContext
    private val versionLabel = appVersion
    private val logsDir = File(appContext.filesDir, "diagnostics").apply { mkdirs() }
    private val queue = ConcurrentLinkedQueue<String>()
    private val queueCount = AtomicInteger()
    private val enabled = AtomicBoolean(true)
    private val lock = ReentrantLock()
    private val executor =
        Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "CarlinkDiagnosticLog").apply { isDaemon = true }
        }
    private val timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).withZone(ZoneId.systemDefault())
    private val fileTimestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.US).withZone(ZoneId.systemDefault())
    private val sessionPrefix = "carlink_${fileTimestamp.format(Instant.now())}"
    private val header =
        buildString {
            appendLine("Carlink OCBM diagnostics")
            appendLine("Session: $sessionPrefix")
            appendLine("App: $versionLabel")
            appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            appendLine("=".repeat(72))
        }
    private var currentFile: File? = null

    init {
        lock.withLock { openCurrentLocked() }
        executor.scheduleAtFixedRate({ flushInternal() }, 1, 1, TimeUnit.SECONDS)
    }

    fun append(
        level: Logger.Level,
        tag: String?,
        message: String,
        throwable: Throwable?,
    ) {
        val label = tag?.let { "[$it] " } ?: ""
        val stack = throwable?.let { "\n${Log.getStackTraceString(it)}" } ?: ""
        enqueue("${timestamp.format(Instant.now())} ${level.name.first()} $label$message$stack")
    }

    fun appendProbe(
        level: Int,
        line: String,
    ) {
        val severity =
            when (level) {
                Log.ERROR -> "E"
                Log.WARN -> "W"
                Log.DEBUG -> "D"
                Log.VERBOSE -> "V"
                else -> "I"
            }
        enqueue("${timestamp.format(Instant.now())} $severity [NETPROBE] $line")
    }

    /** Adapter records retain their own timestamp and source while sharing the bounded export log. */
    fun appendAdapterLog(line: String) {
        enqueue("${timestamp.format(Instant.now())} I [ADAPTER] $line")
    }

    /** Persist an uncaught exception synchronously before Android's handler terminates the process. */
    fun appendCrash(
        thread: Thread,
        throwable: Throwable,
    ) {
        val now = Instant.now()
        val stack = StringWriter().also { writer -> throwable.printStackTrace(PrintWriter(writer)) }.toString()
        val processName = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else appContext.packageName
        val record = buildString {
            appendLine("Carlink uncaught exception")
            appendLine("Time: ${timestamp.format(now)}")
            appendLine("Process: $processName (pid=${android.os.Process.myPid()})")
            appendLine("Thread: ${thread.name}")
            appendLine("App: $versionLabel")
            appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            appendLine()
            appendLine(stack)
        }

        // First drain queued normal logs, then synchronously write both the active session log and
        // a standalone crash record. FileDescriptor.sync() ensures the crash survives process death.
        flushInternal()
        lock.withLock {
            runCatching {
                val crashFile = File(logsDir, "crash_${fileTimestamp.format(now)}_${now.toEpochMilli()}.txt")
                FileOutputStream(crashFile, false).use { output ->
                    output.write(record.toByteArray(Charsets.UTF_8))
                    output.fd.sync()
                }
                currentFile?.let { sessionFile ->
                    FileOutputStream(sessionFile, true).use { output ->
                        output.write("\n===== UNCAUGHT EXCEPTION =====\n".toByteArray(Charsets.UTF_8))
                        output.write(record.toByteArray(Charsets.UTF_8))
                        output.fd.sync()
                    }
                }
                pruneLocked()
            }.onFailure { error ->
                Log.e("CARLINK", "[CRASH_LOG] Failed to persist uncaught exception", error)
            }
        }
    }

    private fun enqueue(line: String) {
        if (!enabled.get()) return
        // Bound memory in case a faulty hot path floods diagnostics faster than storage can flush.
        if (queueCount.incrementAndGet() > MAX_QUEUE_LINES) {
            queueCount.decrementAndGet()
            return
        }
        queue.offer(line)
    }

    fun files(): List<File> =
        logsDir
            .listFiles { file -> file.isFile && (file.name.endsWith(".log") || file.name.endsWith(".txt")) }
            ?.sortedByDescending(File::lastModified) ?: emptyList()

    fun totalBytes(): Long = files().sumOf(File::length)

    fun flush() {
        flushInternal()
    }

    /** Make a consistent ZIP snapshot; the activity copies it to a user-selected SAF destination. */
    fun createExportArchive(): File {
        flush()
        return lock.withLock {
            val archive = File(appContext.cacheDir, "carlink-diagnostics.zip")
            ZipOutputStream(FileOutputStream(archive, false)).use { zip ->
                files().asReversed().forEach { file ->
                    zip.putNextEntry(ZipEntry(file.name))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            archive
        }
    }

    fun copyArchiveTo(uri: Uri): Long {
        val archive = createExportArchive()
        return appContext.contentResolver.openOutputStream(uri, "w")?.use { output ->
            archive.inputStream().use { input -> input.copyTo(output) }
        } ?: error("Could not open the selected destination")
    }

    fun purge(): Int =
        lock.withLock {
            flushInternal()
            val old = currentFile
            old?.appendText("\n===== logs purged by user =====\n")
            var deleted = 0
            files().forEach { file -> if (file != old && file.delete()) deleted++ }
            old?.delete()
            openCurrentLocked()
            deleted + if (old?.exists() == false) 1 else 0
        }

    private fun flushInternal() {
        if (queue.isEmpty()) return
        lock.withLock {
            var file = currentFile ?: return
            val batch = StringBuilder()
            var lines = 0
            while (true) {
                val line = queue.poll() ?: break
                batch.append(line).append('\n')
                lines++
            }
            if (lines == 0) return
            queueCount.addAndGet(-lines)
            try {
                file.appendText(batch.toString())
                if (file.length() >= MAX_FILE_BYTES) {
                    openCurrentLocked()
                }
                pruneLocked()
            } catch (error: Exception) {
                Log.e("CARLINK", "[FILE_LOG] Failed to persist diagnostics", error)
            }
        }
    }

    private fun openCurrentLocked() {
        val file = File(logsDir, "${sessionPrefix}_${System.currentTimeMillis()}.log")
        currentFile = file
        runCatching { file.appendText(header) }
            .onFailure { Log.e("CARLINK", "[FILE_LOG] Cannot open diagnostics file", it) }
    }

    private fun pruneLocked() {
        val all = files().sortedByDescending(File::lastModified)
        var retained = 0L
        all.forEachIndexed { index, file ->
            retained += file.length()
            if (index >= MAX_FILES || retained > MAX_TOTAL_BYTES || file.lastModified() < System.currentTimeMillis() - RETENTION_MS) {
                if (file != currentFile) file.delete()
            }
        }
    }

    companion object {
        private const val MAX_FILE_BYTES = 2L * 1024 * 1024
        private const val MAX_TOTAL_BYTES = 10L * 1024 * 1024
        private const val MAX_FILES = 5
        private const val MAX_QUEUE_LINES = 10_000
        private const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000

        @Volatile private var instance: RollingDiagnosticLog? = null

        fun getInstance(
            context: Context,
            appVersion: String,
        ): RollingDiagnosticLog =
            instance ?: synchronized(this) {
                instance ?: RollingDiagnosticLog(context.applicationContext, appVersion).also { instance = it }
            }

        fun getInitializedInstance(): RollingDiagnosticLog? = instance
    }
}
