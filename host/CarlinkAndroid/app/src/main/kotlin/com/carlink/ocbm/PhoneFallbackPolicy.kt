package com.carlink.ocbm

/** A failed Wi-Fi dial-back makes a ready preferred iAP2 slot temporarily unusable. */
internal fun readyAlternateAfterDialBackTimeout(
    preferredMac: String?,
    timedOutMac: String,
    currentTargetMac: String?,
    slots: Map<String, Byte>,
    sessionLive: Boolean,
    explicitTargetMac: String?,
    switchPending: Boolean,
): String? {
    val preferred = preferredMac?.uppercase() ?: return null
    if (sessionLive || explicitTargetMac != null || switchPending ||
        timedOutMac.uppercase() != preferred || currentTargetMac?.uppercase() != preferred
    ) return null
    return slots.entries.firstOrNull { (mac, phase) ->
        mac.uppercase() != preferred && phase == Ocbm.BTP_WIFI_HANDOFF
    }?.key?.uppercase()
}
