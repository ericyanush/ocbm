package com.carlink.navigation

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import com.carlink.logging.Logger
import com.carlink.logging.logInfo
import com.carlink.logging.logWarn
import com.carlink.MainActivity

/** Shared vehicle settings and lifecycle for the AAOS Templates Host navigation session. */
object ClusterNavigationController {
    private const val PREFS = "cluster_navigation"
    private const val KEY_ENABLED = "enabled"
    private const val SERVICE = "com.carlink.navigation.CarPlayClusterService"
    // Keep the Templates Host integration opt-in until it is verified across AAOS builds.
    // Starting CarAppActivity during ordinary adapter startup can interfere with the foreground
    // CarPlay activity on hosts that do not support the cluster templates flow reliably.
    private const val DEFAULT_ENABLED = false
    private const val CAR_APP_ACTIVITY = "androidx.car.app.activity.CarAppActivity"
    private const val BINDING_RESTART_DELAY_MS = 2_000L
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var restartPending = false

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, DEFAULT_ENABLED)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
        val component = ComponentName(context, SERVICE)
        context.packageManager.setComponentEnabledSetting(
            component,
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
        logInfo(
            "setting changed enabled=$enabled serviceState=${context.packageManager.getComponentEnabledSetting(component)}",
            tag = Logger.Tags.CLUSTER,
        )
        if (enabled) startTemplatesBinding(context)
        if (!enabled) OcbmNavigationFeed.clear()
    }

    /** CarAppActivity causes the installed Templates Host to bind the navigation service. */
    fun startTemplatesBinding(context: Context) {
        if (!isEnabled(context)) return
        if (ClusterBindingState.sessionAlive) {
            logInfo("Templates Host session already alive; skipping duplicate CarAppActivity launch", tag = Logger.Tags.CLUSTER)
            return
        }
        runCatching {
            logInfo("launching CarAppActivity to establish Templates Host session", tag = Logger.Tags.CLUSTER)
            context.startActivity(
                Intent().apply {
                    setClassName(context, "androidx.car.app.activity.CarAppActivity")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                },
            )
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching {
                    val mainIntent = Intent(context, MainActivity::class.java).apply {
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                                Intent.FLAG_ACTIVITY_NO_ANIMATION,
                        )
                    }
                    context.startActivity(mainIntent)
                }.onFailure { logWarn("Could not restore main app after Templates Host bind: ${it.message}", tag = Logger.Tags.CLUSTER) }
            }, 1_000L)
            logInfo("CarAppActivity launch accepted; main app restore scheduled in 1000ms", tag = Logger.Tags.CLUSTER)
        }.onFailure { logWarn("Templates Host binding unavailable: ${it.message}", tag = Logger.Tags.CLUSTER) }
    }

    /** Match Enigy's proven reconnect behavior by rebuilding the Host binding per phone session. */
    fun restartTemplatesBinding(context: Context) {
        if (!isEnabled(context)) return
        synchronized(this) {
            if (restartPending) {
                logInfo("Templates Host binding restart already pending; coalescing phone event", tag = Logger.Tags.CLUSTER)
                return
            }
            restartPending = true
        }

        logInfo("phone session accepted; refreshing Templates Host binding", tag = Logger.Tags.CLUSTER)
        finishCarAppActivityTask(context)
        // The host can omit or delay onDestroy; clear this process-local guard as soon as the
        // task is explicitly removed so the relaunch cannot be blocked by stale session state.
        ClusterBindingState.reset()
        mainHandler.postDelayed({
            synchronized(this) { restartPending = false }
            if (isEnabled(context)) {
                logInfo("relaunching CarAppActivity after binding teardown", tag = Logger.Tags.CLUSTER)
                startTemplatesBinding(context.applicationContext)
            }
        }, BINDING_RESTART_DELAY_MS)
    }

    private fun finishCarAppActivityTask(context: Context) {
        runCatching {
            val activityManager = context.getSystemService(ActivityManager::class.java)
            val task = activityManager.appTasks.firstOrNull {
                it.taskInfo?.baseActivity?.className == CAR_APP_ACTIVITY
            }
            if (task == null) {
                logInfo("no existing CarAppActivity task to finish", tag = Logger.Tags.CLUSTER)
            } else {
                logInfo("finishing CarAppActivity task id=${task.taskInfo?.taskId}", tag = Logger.Tags.CLUSTER)
                task.finishAndRemoveTask()
            }
        }.onFailure {
            logWarn("could not finish CarAppActivity task: ${it.message}", tag = Logger.Tags.CLUSTER)
        }
    }
}
