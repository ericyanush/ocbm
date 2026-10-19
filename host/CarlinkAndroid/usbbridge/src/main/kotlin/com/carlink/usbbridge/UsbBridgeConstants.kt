package com.carlink.usbbridge

object UsbBridgeConstants {
    const val STATE_DISCONNECTED = 0
    const val STATE_CONNECTED = 1
    const val STATE_ERROR = 2
    const val STATE_DIAGNOSTIC = 3
    // Additive to the existing OCBM state values so the expanded AIDL contract remains compatible.
    const val STATE_CONNECTING = 4

    const val MAX_MESSAGE_SIZE = 2 * 1024 * 1024
    const val CHUNK_SIZE = 64 * 1024
    const val MAX_PENDING_WRITES = 8
    const val PENDING_WRITE_TTL_MS = 10_000L
    const val BRIDGE_PACKAGE = "android.car.usb.handler"
    const val BRIDGE_SERVICE_CLASS = "com.carlink.usbhelper.UsbBridgeService"
}
