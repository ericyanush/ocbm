package com.carlink

import android.app.Application
import android.util.Log
import com.carlink.logging.CarlinkUncaughtExceptionHandler
import com.carlink.logging.Logger
import com.carlink.logging.RollingDiagnosticLog

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
            }
        }
    }
}
