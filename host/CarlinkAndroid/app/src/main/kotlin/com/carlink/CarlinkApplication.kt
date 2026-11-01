package com.carlink

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import com.carlink.logging.CarlinkUncaughtExceptionHandler
import com.carlink.logging.Logger
import com.carlink.logging.RollingDiagnosticLog
import java.io.ByteArrayOutputStream
import java.io.File

/** Initializes persistent diagnostics before any activity or service can fail. */
class CarlinkApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ensureProcessDiagnostics(this)
    }

    companion object {
        /**
         * Application.onCreate runs once in every Android process belonging to this package.
         * Keeping this idempotent lets services safely ensure their own process has the same
         * persistent logger and fatal-crash handler as the UI process.
         */
        fun ensureProcessDiagnostics(application: Application) {
            if (RollingDiagnosticLog.getInitializedInstance() == null) {
                val packageInfo = application.packageManager.getPackageInfo(application.packageName, 0)
                val appVersion = "${packageInfo.versionName}+${packageInfo.longVersionCode}"
                Logger.initialize(application, appVersion)
            }
            Logger.setDebugLoggingEnabled(BuildConfig.DEBUG)

            val current = Thread.getDefaultUncaughtExceptionHandler()
            if (current !is CarlinkUncaughtExceptionHandler) {
                Thread.setDefaultUncaughtExceptionHandler(CarlinkUncaughtExceptionHandler(application, current))
                Log.i("CARLINK", "[CRASH_LOG] persistent uncaught exception handler installed for pid=${android.os.Process.myPid()}")
                Logger.i(
                    "[PROCESS] uncaught-exception handler installed pid=${android.os.Process.myPid()} " +
                        "process=${if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else application.packageName} " +
                        "previous=${current?.javaClass?.name ?: "none"}",
                    "MAIN",
                )
            }
            Logger.i(
                "[PROCESS] Application.onCreate pid=${android.os.Process.myPid()} " +
                    "process=${if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else application.packageName}",
                "MAIN",
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                (Build.VERSION.SDK_INT < 28 || Application.getProcessName() == application.packageName)
            ) {
                logPreviousProcessExit(application)
            }
        }

        /**
         * Java uncaught handlers cannot see native crashes, LMK kills, or system process kills.
         * Android keeps the previous process exit reason; persist it on the next app start so the
         * diagnostic bundle can distinguish those cases from a handled Kotlin exception.
         */
        private fun logPreviousProcessExit(application: Application) {
            val prefs = application.getSharedPreferences("process_exit_diagnostics", Context.MODE_PRIVATE)
            val lastReportedTimestamp = prefs.getLong("last_reported_timestamp", 0L)
            val manager = application.getSystemService(ActivityManager::class.java) ?: return
            val exits = runCatching {
                manager.getHistoricalProcessExitReasons(application.packageName, 0, 8)
            }.onFailure {
                Logger.w("[PROCESS_EXIT] unable to query historical exits: ${it.message}", "MAIN")
            }.getOrNull() ?: return

            val freshExits = exits
                .filter {
                    it.processName == application.packageName &&
                        (it.timestamp > lastReportedTimestamp || shouldRetryTruncatedTrace(application, it))
                }
                .sortedBy { it.timestamp }
            if (freshExits.isEmpty()) return

            var newestTimestamp = lastReportedTimestamp
            for (exit in freshExits) {
                val reason = when (exit.reason) {
                    ApplicationExitInfo.REASON_ANR -> "ANR"
                    ApplicationExitInfo.REASON_CRASH -> "JAVA_CRASH"
                    ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE_CRASH"
                    ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
                    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
                    ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
                    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
                    ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
                    ApplicationExitInfo.REASON_OTHER -> "OTHER"
                    ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
                    ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
                    ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
                    ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
                    ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN"
                    else -> "${exit.reason}"
                }
                Logger.e(
                    "[PROCESS_EXIT] previous process ended: reason=$reason status=${exit.status} " +
                        "timestamp=${exit.timestamp} pid=${exit.pid} importance=${exit.importance} " +
                        "pssKb=${exit.pss} rssKb=${exit.rss} description=${exit.description}",
                    "MAIN",
                )

                // Native crash and ANR records can contain a tombstone/trace. Keep the copy bounded.
                val hasSystemTrace = exit.reason == ApplicationExitInfo.REASON_ANR ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE)
                if (hasSystemTrace) {
                    runCatching {
                        exit.traceInputStream?.use { stream ->
                            val bytes = ByteArrayOutputStream()
                            val buffer = ByteArray(4096)
                            while (bytes.size() < MAX_EXIT_TRACE_BYTES) {
                                val count = stream.read(buffer, 0, minOf(buffer.size, MAX_EXIT_TRACE_BYTES - bytes.size()))
                                if (count <= 0) break
                                bytes.write(buffer, 0, count)
                            }
                            val trace = bytes.toByteArray()
                            val truncated = trace.size == MAX_EXIT_TRACE_BYTES && stream.read() != -1
                            if (trace.isNotEmpty()) {
                                val artifact = RollingDiagnosticLog.getInitializedInstance()
                                    ?.appendProcessExitTrace(exit.pid, reason, exit.timestamp, trace)
                                if (artifact != null) {
                                    Logger.e(
                                        "[PROCESS_EXIT_TRACE] saved system trace pid=${exit.pid} reason=$reason " +
                                            "bytes=${trace.size} truncated=$truncated artifact=${artifact.name}",
                                        "MAIN",
                                    )
                                } else {
                                    Logger.e(
                                        "[PROCESS_EXIT_TRACE] system supplied a $reason trace for pid=${exit.pid} " +
                                            "(${trace.size} bytes), but it could not be saved to diagnostics",
                                        "MAIN",
                                    )
                                }
                            }
                        }
                    }.onFailure {
                        Logger.w("[PROCESS_EXIT] trace unavailable for pid=${exit.pid}: ${it.message}", "MAIN")
                    }
                }
                if (!shouldRetryTruncatedTrace(application, exit)) {
                    newestTimestamp = maxOf(newestTimestamp, exit.timestamp)
                }
            }
            prefs.edit().putLong("last_reported_timestamp", newestTimestamp).apply()
        }

        private const val MAX_EXIT_TRACE_BYTES = 4 * 1024 * 1024
        private const val LEGACY_EXIT_TRACE_BYTES = 64 * 1024

        private fun shouldRetryTruncatedTrace(
            application: Application,
            exit: ApplicationExitInfo,
        ): Boolean {
            val traceReason = exit.reason == ApplicationExitInfo.REASON_ANR ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE)
            if (!traceReason) return false
            val file = File(
                application.filesDir,
                "diagnostics/process_exit_trace_${exit.pid}_${exit.timestamp}.pb",
            )
            return file.isFile &&
                (file.length() == LEGACY_EXIT_TRACE_BYTES.toLong() || file.length() >= MAX_EXIT_TRACE_BYTES.toLong())
        }
    }
}
