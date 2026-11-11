package com.carlink.ocbm

import android.content.Context

/** Persisted per-head-unit USB ownership choice. Split helper stays the default for GM trucks. */
enum class UsbTransportMode(val label: String) {
    SPLIT_HELPER("Split USB helper"),
    DIRECT("Main app direct USB"),
}

/** How the iPhone reaches the native receiver after iAP2 handoff. Direct remains the safe default. */
enum class CarPlayNetworkMode(val label: String, val description: String) {
    VEHICLE_HOTSPOT("Vehicle hotspot (direct)", "Phone connects to the vehicle hotspot; AAOS receives CarPlay directly."),
    DONGLE_WIFI_TUNNEL("Dongle Wi-Fi via USB", "Phone connects to the adapter AP; TCP/UDP sockets are forwarded to this app over OCBM."),
    PI_WIFI_PROXY("Pi network proxy (test)", "Pi Bluetooth and software MFi start CarPlay; the phone joins the Wi-Fi network reported by the Pi, and the Pi relays sockets to AAOS."),
}

class CarPlayNetworkModeStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("carlink_receiver", Context.MODE_PRIVATE)
    var mode: CarPlayNetworkMode
        get() = runCatching {
            CarPlayNetworkMode.valueOf(prefs.getString(KEY_MODE, CarPlayNetworkMode.VEHICLE_HOTSPOT.name)!!)
        }.getOrDefault(CarPlayNetworkMode.VEHICLE_HOTSPOT)
        set(value) { check(prefs.edit().putString(KEY_MODE, value.name).commit()) { "Could not save CarPlay network mode" } }

    companion object { private const val KEY_MODE = "network_mode" }
}

/** Explicit identity selection for direct USB; the helper retains its own device allowlist. */
enum class UsbAdapterIdentity(val label: String, val vendorId: Int, val productId: Int) {
    STANDARD("Standard OCBM — 1314:2D00", 0x1314, 0x2d00),
    GM_TEST("Experimental — 0424:4917", 0x0424, 0x4917),
    ;

    fun matches(vid: Int, pid: Int): Boolean = vendorId == vid && productId == pid

    val hexId: String get() = "%04x:%04x".format(vendorId, productId)
}

class UsbTransportModeStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var mode: UsbTransportMode
        get() = runCatching {
            UsbTransportMode.valueOf(prefs.getString(KEY_MODE, UsbTransportMode.SPLIT_HELPER.name)!!)
        }.getOrDefault(UsbTransportMode.SPLIT_HELPER)
        set(value) {
            prefs.edit().putString(KEY_MODE, value.name).apply()
        }

    var adapterIdentity: UsbAdapterIdentity
        get() = runCatching {
            UsbAdapterIdentity.valueOf(prefs.getString("adapter_identity", UsbAdapterIdentity.STANDARD.name)!!)
        }.getOrDefault(UsbAdapterIdentity.STANDARD)
        set(value) {
            prefs.edit().putString("adapter_identity", value.name).apply()
        }

    companion object {
        private const val PREFS = "carlink_usb_transport"
        private const val KEY_MODE = "mode"
    }
}
