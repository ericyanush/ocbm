package com.carlink.ocbm

import android.content.Context

/** Per-head-unit receiver port; one value drives listening, DNS-SD, and iAP2 StartSession. */
internal class CarPlayControlPortStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("carlink_receiver", Context.MODE_PRIVATE)

    val port: Int get() = runCatching { prefs.getInt("control_port", DEFAULT_PORT) }
        .getOrDefault(DEFAULT_PORT).takeIf(::isValid) ?: DEFAULT_PORT

    val bridgeOnly: Boolean get() = runCatching { prefs.getBoolean("bridge_only", false) }.getOrDefault(false)

    fun save(port: Int, bridgeOnly: Boolean = this.bridgeOnly): Boolean {
        require(isValid(port))
        return prefs.edit().putInt("control_port", port).putBoolean("bridge_only", bridgeOnly).commit()
    }

    companion object {
        const val DEFAULT_PORT = 7011
        fun isValid(port: Int): Boolean = port in 1024..65535
    }
}
