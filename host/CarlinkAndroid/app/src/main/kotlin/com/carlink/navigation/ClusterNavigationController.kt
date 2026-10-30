package com.carlink.navigation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/** Opt-in lifecycle for the AAOS Templates Host navigation session. */
object ClusterNavigationController {
    private const val PREFS = "cluster_navigation"
    private const val KEY_ENABLED = "enabled"
    private const val SERVICE = "com.carlink.navigation.CarPlayClusterService"
    private const val TAG = "CarlinkClusterNav"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
        val component = ComponentName(context, SERVICE)
        context.packageManager.setComponentEnabledSetting(
            component,
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
        if (enabled) startTemplatesBinding(context)
        if (!enabled) CarPlayNavigationState.clear("cluster navigation disabled")
        Log.i(TAG, "cluster navigation ${if (enabled) "enabled" else "disabled"}")
    }

    /** CarAppActivity causes the installed Templates Host to bind the navigation service. */
    fun startTemplatesBinding(context: Context) {
        if (!isEnabled(context)) return
        runCatching {
            context.startActivity(
                Intent().apply {
                    setClassName(context, "androidx.car.app.activity.CarAppActivity")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                },
            )
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching {
                    context.startActivity(
                        context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                        } ?: return@postDelayed,
                    )
                }.onFailure { Log.w(TAG, "Could not restore main app after Templates Host bind", it) }
            }, 1_000L)
            Log.i(TAG, "started Templates Host binding activity")
        }.onFailure { Log.w(TAG, "Templates Host binding unavailable", it) }
    }
}
