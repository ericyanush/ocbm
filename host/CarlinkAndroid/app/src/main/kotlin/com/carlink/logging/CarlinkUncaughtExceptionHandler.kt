package com.carlink.logging

import android.content.Context
import android.os.Process
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.system.exitProcess

/** Persists the fatal stack trace, then delegates to Android's normal crash handler. */
class CarlinkUncaughtExceptionHandler(
    context: Context,
    private val delegate: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {
    private val appContext = context.applicationContext

    override fun uncaughtException(
        thread: Thread,
        throwable: Throwable,
    ) {
        Log.e(TAG, "Uncaught exception on thread ${thread.name}; persisting before process termination", throwable)
        val persisted = runCatching {
            RollingDiagnosticLog.getInitializedInstance()?.appendCrash(thread, throwable)
                ?: error("diagnostic logger was not initialized")
        }.onFailure { error ->
            Log.e(TAG, "Could not write crash through the rolling diagnostic logger", error)
        }.isSuccess

        if (!persisted) writeFallbackCrash(thread, throwable)

        val normalHandler = delegate
        if (normalHandler != null && normalHandler !== this) {
            normalHandler.uncaughtException(thread, throwable)
        } else {
            Process.killProcess(Process.myPid())
            exitProcess(10)
        }
    }

    private fun writeFallbackCrash(
        thread: Thread,
        throwable: Throwable,
    ) {
        runCatching {
            val directory = File(appContext.filesDir, "diagnostics")
            if (!directory.exists() && !directory.mkdirs()) return
            val now = Instant.now()
            val file = File(directory, "crash_fallback_${FILE_TIME.format(now)}_${now.toEpochMilli()}.txt")
            FileOutputStream(file, false).use { output ->
                output.write("Thread: ${thread.name}\n".toByteArray())
                output.write(Log.getStackTraceString(throwable).toByteArray())
                output.fd.sync()
            }
        }.onFailure { error -> Log.e(TAG, "Fallback crash-file write failed", error) }
    }

    companion object {
        private const val TAG = "CARLINK"
        private val FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.US).withZone(ZoneId.systemDefault())
    }
}
