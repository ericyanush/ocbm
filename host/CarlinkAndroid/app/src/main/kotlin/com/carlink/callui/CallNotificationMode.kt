package com.carlink.callui

/** How Carlink surfaces iPhone call state in the AAOS notification UI. */
enum class CallNotificationMode(
    val title: String,
    val description: String,
) {
    HIGH_PRIORITY(
        "Enabled — high priority",
        "Call popover with call actions. This is the current behavior.",
    ),
    SILENT(
        "Enabled — silent",
        "Quiet status notification without the call popover or full-screen launch.",
    ),
    DISABLED(
        "Disabled",
        "Do not post call notifications. CarPlay call audio and in-app controls still work.",
    ),
    ;

    companion object {
        fun fromStored(value: String?): CallNotificationMode =
            entries.firstOrNull { it.name == value } ?: HIGH_PRIORITY
    }
}
