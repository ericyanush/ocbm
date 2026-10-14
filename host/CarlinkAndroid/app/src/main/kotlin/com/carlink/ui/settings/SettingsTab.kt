package com.carlink.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Tabs in the Settings screen's navigation rail, in display order. Logs provides an app-private
 * rolling capture with SAF export and deliberately has no noisy level controls.
 */
enum class SettingsTab(
    val title: String,
    val icon: ImageVector,
) {
    PHONES("Phones", Icons.Default.PhoneAndroid),
    WIFI("Wi-Fi", Icons.Default.Wifi),
    CONTROL("Control", Icons.Default.Settings),
    LOGS("Logs", Icons.AutoMirrored.Filled.Article),
}
