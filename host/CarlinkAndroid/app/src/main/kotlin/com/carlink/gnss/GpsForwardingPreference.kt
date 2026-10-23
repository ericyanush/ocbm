package com.carlink.gnss

import android.content.Context

/** Persistent opt-in for forwarding the head unit's GPS fix through native wireless CarPlay. */
class GpsForwardingPreference(
    context: Context,
) {
    private val prefs = context.applicationContext.getSharedPreferences("carlink_gnss", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        }

    private companion object {
        const val KEY_ENABLED = "forward_vehicle_location"
    }
}
