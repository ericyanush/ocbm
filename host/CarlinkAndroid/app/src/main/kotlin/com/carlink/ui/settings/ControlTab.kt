package com.carlink.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DisplaySettings
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PhoneDisabled
import androidx.compose.material.icons.filled.PowerOff
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SettingsInputComponent
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.VideoSettings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.carlink.CarlinkManager
import com.carlink.callui.CallNotificationMode
import zeno.gmccpa.av.MicSource
import com.carlink.logging.logInfo
import com.carlink.logging.logWarn
import com.carlink.ocbm.UsbTransportMode
import com.carlink.ocbm.UsbAdapterIdentity
import com.carlink.ocbm.CarPlayFrameRate
import com.carlink.ocbm.CarPlayNetworkMode
import com.carlink.ocbm.SoftwareMfiIdentityStore
import com.carlink.navigation.ClusterNavigationController
import com.carlink.ui.settings.LaneGuidanceStore
import com.carlink.ui.settings.TurnAlertStore
import com.carlink.ui.settings.AlertTiming
import com.carlink.ui.DisplayModeOutcome
import com.carlink.ui.adaptive.DashboardArrangement
import com.carlink.ui.adaptive.DashboardLayout
import com.carlink.ui.adaptive.rememberWindowLayoutInfo
import com.carlink.ui.components.BoxStatusLine
import com.carlink.ui.components.ButtonSeverity
import com.carlink.ui.components.ControlAction
import com.carlink.ui.components.ControlButton
import com.carlink.ui.components.ControlCard
import com.carlink.ui.theme.AutomotiveDimens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private val DISCONNECT_PHONE = ControlAction("Disconnect Phone", Icons.Default.PhoneDisabled, ButtonSeverity.WARNING)
private val RESTART_WIRELESS = ControlAction("Restart Wireless", Icons.Default.Wifi, ButtonSeverity.WARNING)
private val REBOOT_ADAPTER = ControlAction("Reboot Adapter", Icons.Default.RestartAlt, ButtonSeverity.WARNING)
private val DISCONNECT_ADAPTER = ControlAction("Disconnect Adapter", Icons.Default.PowerOff, ButtonSeverity.DESTRUCTIVE)
private val RESET_DECODER = ControlAction("Reset Decoder", Icons.Default.VideoSettings, ButtonSeverity.WARNING)
private val RESET_CONNECTION = ControlAction("Reset Connection", Icons.Default.Usb, ButtonSeverity.DESTRUCTIVE)

/** Why Disconnect Phone is disabled on every box today — see [CarlinkManager.phoneDisconnectSupported]. */
private const val DISCONNECT_PHONE_UNSUPPORTED = "Requires adapter firmware support"

/**
 * Control tab: the "Adapter" card (box status, Disconnect Phone, Restart Wireless, Reboot Adapter,
 * Disconnect Adapter) beside the "App Control" card (Display Mode, Reset Decoder, Reset
 * Connection). Side by side on an expanded landscape window, stacked otherwise — the same
 * breakpoint the previous dashboard used ([DashboardLayout]), so a portrait or narrow panel
 * never squeezes two cards into one row.
 *
 * What each control sends (Settings → OCBM mapping, corrected 2026-09-25; OCBMANDROID.md):
 *  - Disconnect Phone: NOTHING today — disabled with a reason until the box grows a
 *    phone-disconnect verb and advertises it. It used to send `MGMT_RESTART_WIRELESS` in disguise.
 *  - Restart Wireless: `MGMT_RESTART_WIRELESS`, confirmed; does not touch a wired phone.
 *  - Reboot Adapter: `MGMT_REBOOT`, confirmed; ~50 s absence, then helper-driven silent re-grant.
 *  - Disconnect Adapter: CT_STOP + release USB, confirmed, off the main thread.
 *  - Reset Connection: the clean-slate reset ([CarlinkManager.cleanSlateReset]), confirmed.
 */
@Composable
internal fun ControlTabContent(
    carlinkManager: CarlinkManager,
    connectionState: CarlinkManager.State,
    displayMode: DisplayMode,
    onDisplayModeSelected: (DisplayMode) -> DisplayModeOutcome,
) {
    val window = rememberWindowLayoutInfo()
    val twoPane = DashboardLayout.arrangement(window) == DashboardArrangement.TWO_PANE
    // Sized like carlink_native: 75 % of the window, clamped to a legible range.
    val maxContentWidth = (window.widthDp * 0.75f).dp.coerceIn(400.dp, 1200.dp)
    val connected = connectionState != CarlinkManager.State.DISCONNECTED
    val context = LocalContext.current
    val identityStore = remember(context) { SoftwareMfiIdentityStore(context) }
    val identityScope = rememberCoroutineScope()
    var identityStatus by remember { mutableStateOf("Checking identity…") }
    LaunchedEffect(identityStore) {
        val ready = kotlinx.coroutines.withContext(Dispatchers.IO) { identityStore.isReady() }
        if (identityStatus == "Checking identity…") identityStatus = if (ready) "Identity ready" else "No identity imported"
    }
    val identityPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            identityStatus = "Validating identity…"
            identityScope.launch {
                val result = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    runCatching { identityStore.importDocuments(uris) }
                }
                identityStatus = if (result.isSuccess) "Identity ready" else "Import failed. Select the matching key and certificate."
            }
        }
    }
    var piBluetoothStatus by remember { mutableStateOf("Not checked") }
    var piBluetoothPhones by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    var piConnectedPhones by remember { mutableStateOf(emptySet<String>()) }
    var piAdvertisedName by remember { mutableStateOf("Carlink-Pi") }
    var piForgetCandidate by remember { mutableStateOf<Pair<String, String>?>(null) }
    var piModeApplied by remember(carlinkManager) { mutableStateOf(carlinkManager.carPlayNetworkMode == CarPlayNetworkMode.PI_WIFI_PROXY) }
    var selectedBluetoothPhone by remember(carlinkManager) { mutableStateOf(carlinkManager.appBluetoothPhone) }
    var applyingSessionTransport by remember { mutableStateOf(false) }
    var sessionTransportMessage by remember { mutableStateOf<String?>(null) }
    var gpsEnabled by remember(carlinkManager) { mutableStateOf(carlinkManager.gpsForwardingEnabled) }
    var callNotificationMode by remember(carlinkManager) { mutableStateOf(carlinkManager.callNotificationMode) }
    var microphoneSource by remember(carlinkManager) { mutableStateOf(carlinkManager.microphoneSource) }
    var startSessionEnabled by remember(carlinkManager) { mutableStateOf(carlinkManager.startSessionExperimentEnabled) }
    var clusterNavigationEnabled by remember(context) { mutableStateOf(ClusterNavigationController.isEnabled(context)) }
    val laneStore = remember(context) { LaneGuidanceStore.get(context) }
    val turnAlertStore = remember(context) { TurnAlertStore.get(context) }
    var laneDisplayEnabled by remember(context) { mutableStateOf(laneStore.current.strip) }
    var laneIconMode by remember(context) { mutableStateOf(laneStore.current.icon) }
    var laneColor by remember(context) { mutableStateOf(laneStore.current.laneColor) }
    var turnAlertEnabled by remember(context) { mutableStateOf(turnAlertStore.current.enabled) }
    var turnAlertTiming by remember(context) { mutableStateOf(turnAlertStore.current.timing) }
    var turnAlertSpeed by remember(context) { mutableStateOf(turnAlertStore.current.speed) }
    var turnAlertColor by remember(context) { mutableStateOf(turnAlertStore.current.color) }
    var turnAlertStyle by remember(context) { mutableStateOf(turnAlertStore.current.style) }
    var turnAlertSolidLast by remember(context) { mutableStateOf(turnAlertStore.current.solidLast) }
    val locationPermission =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            if (granted) {
                gpsEnabled = true
                carlinkManager.setGpsForwardingEnabled(true)
            } else {
                gpsEnabled = false
                carlinkManager.setGpsForwardingEnabled(false)
            }
        }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier =
                Modifier
                    .widthIn(max = maxContentWidth)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            if (twoPane) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    AdapterCard(carlinkManager, connected, Modifier.weight(1f), onNetworkModeApplied = { piModeApplied = it == CarPlayNetworkMode.PI_WIFI_PROXY })
                    AppControlCard(carlinkManager, connected, displayMode, onDisplayModeSelected, Modifier.weight(1f))
                }
            } else {
                AdapterCard(carlinkManager, connected, Modifier.fillMaxWidth(), onNetworkModeApplied = { piModeApplied = it == CarPlayNetworkMode.PI_WIFI_PROXY })
                AppControlCard(carlinkManager, connected, displayMode, onDisplayModeSelected, Modifier.fillMaxWidth())
            }
            ControlCard(title = "Software MFi identity", icon = Icons.Default.SettingsInputComponent) {
                Text("Import your authorized identity.pk8 and certificate.p7b together, or build with -PbundleOfflineMfi=true. The active identity is copied to private app storage and excluded from backups.")
                Text(identityStatus, style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { identityPicker.launch(arrayOf("*/*")) }) {
                    Text("Import identity files")
                }
                Text("Used with Android or Pi Bluetooth. Adapter mode uses its hardware signer.", style = MaterialTheme.typography.bodySmall)
            }
            ControlCard(title = "Pi Bluetooth", icon = Icons.Default.SettingsInputComponent) {
                Text("Pair the phone with the Pi Bluetooth radio. The Pi exposes RFCOMM only; AAOS processes iAP2 and software MFi.")
                Text(piBluetoothStatus)
                OutlinedTextField(value = piAdvertisedName, onValueChange = { piAdvertisedName = it },
                    label = { Text("Pi Bluetooth name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = {
                    identityScope.launch {
                        val result = runCatching { carlinkManager.setPiBluetoothName(piAdvertisedName.trim()) }
                        piBluetoothStatus = result.fold({ "Pi Bluetooth name set to $it" },
                            { "Could not set name: ${it.message}" })
                    }
                }) { Text("Set Pi Bluetooth name") }
                OutlinedButton(onClick = {
                    piBluetoothStatus = "Checking Pi Bluetooth…"
                    identityScope.launch {
                        val result = kotlinx.coroutines.withContext(Dispatchers.IO) {
                            runCatching { carlinkManager.piBluetoothStatus() }
                        }
                        result.fold({ status ->
                            piBluetoothPhones = status.pairedPhones
                            piConnectedPhones = status.connectedPhones
                            piAdvertisedName = status.name
                            piBluetoothStatus = "Pi ${status.address}; ${status.pairedPhones.size} paired phone(s)"
                        }, { error ->
                            piBluetoothPhones = emptyList()
                            piBluetoothStatus = "Pi unavailable: ${error.message?.take(120)}"
                        })
                    }
                }) { Text("Check Pi Bluetooth") }
                if (piModeApplied) piBluetoothPhones.forEach { (address, name) ->
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Text("$name: ${if (address in piConnectedPhones) "Bluetooth connected" else "Paired"}",
                            modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = {
                            identityScope.launch {
                                val result = runCatching { carlinkManager.switchPiPhone(address) }
                                piBluetoothStatus = result.fold(
                                    { if (it) "Switching to $name; preferred phone unchanged" else "Switch unavailable" },
                                    { "Switch failed: ${it.message}" })
                            }
                        }) { Text("Use now") }
                        OutlinedButton(onClick = { piForgetCandidate = address to name }) { Text("Forget") }
                    }
                }
            }
            piForgetCandidate?.let { (address, name) ->
                AlertDialog(onDismissRequest = { piForgetCandidate = null },
                    title = { Text("Forget $name?") },
                    text = { Text("Remove this Bluetooth pairing from the Pi. Also forget Carlink in the iPhone's Bluetooth settings before pairing again.") },
                    confirmButton = { TextButton(onClick = {
                        piForgetCandidate = null
                        identityScope.launch {
                            val result = runCatching { carlinkManager.forgetPiBluetoothPhone(address) }
                            piBluetoothStatus = result.fold({ "Removed $name from Pi" }, { "Remove failed: ${it.message}" })
                            if (result.isSuccess) { piBluetoothPhones = piBluetoothPhones.filterNot { it.first == address }; piConnectedPhones -= address }
                        }
                    }) { Text("Forget") } },
                    dismissButton = { TextButton(onClick = { piForgetCandidate = null }) { Text("Cancel") } })
            }
            ControlCard(title = "CarPlay session transport", icon = Icons.Default.SettingsInputComponent) {
                Text("The selected network mode determines who handles Bluetooth and MFi.")
                val piMode = piModeApplied
                Text(if (piMode) "Pi Bluetooth + software MFi" else "OCBM adapter Bluetooth + hardware MFi")
                if (piMode) {
                    Text("Select a phone paired with the Pi. Tap Check Pi Bluetooth above. Import a software MFi identity before applying.")
                    if (piBluetoothPhones.isEmpty()) Text("Tap Check Pi Bluetooth above to list paired phones.")
                    piBluetoothPhones.forEach { (address, name) ->
                        Row(modifier = Modifier.fillMaxWidth().clickable { selectedBluetoothPhone = address },
                            verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = selectedBluetoothPhone == address, onClick = { selectedBluetoothPhone = address })
                            Text("$name ($address)")
                        }
                    }
                }
                Button(
                    enabled = !applyingSessionTransport && (!piMode || selectedBluetoothPhone == null ||
                        piBluetoothPhones.any { it.first.equals(selectedBluetoothPhone, true) }),
                    onClick = {
                        applyingSessionTransport = true
                        identityScope.launch {
                            try {
                                val applied = carlinkManager.applySessionTransport(piMode, if (piMode) selectedBluetoothPhone else null)
                                sessionTransportMessage = if (applied) "Session transport saved; connection restarted." else "Could not apply session transport; check the phone and Wi-Fi settings."
                            } finally { applyingSessionTransport = false }
                        }
                    },
                ) { Text(if (applyingSessionTransport) "Restarting…" else "Apply transport and reconnect") }
                sessionTransportMessage?.let { Text(it) }
            }
            ControlCard(title = "Adapter iAP2 StartSession", icon = Icons.Default.Wifi) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Use iAP2 StartSession", style = MaterialTheme.typography.titleMedium)
                        Text("Applies to OCBM adapter mode; Android Bluetooth always uses iAP2 StartSession. Requires updated adapter firmware. Disables HTTP connection nudges. " +
                            "Takes effect after a complete session restart; saving does not stop playback.",
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    Switch(checked = startSessionEnabled, onCheckedChange = {
                        startSessionEnabled = it; carlinkManager.setStartSessionExperimentEnabled(it)
                    })
                }
            }
            ControlCard(title = "Vehicle location", icon = Icons.Default.LocationOn) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Forward vehicle GPS to CarPlay", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Uses the head unit's GPS provider and sends location only over the native CarPlay session. " +
                                "The adapter does not receive GPS data.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = gpsEnabled,
                        onCheckedChange = { checked ->
                            if (!checked) {
                                gpsEnabled = false
                                carlinkManager.setGpsForwardingEnabled(false)
                            } else if (
                                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                                PackageManager.PERMISSION_GRANTED
                            ) {
                                gpsEnabled = true
                                carlinkManager.setGpsForwardingEnabled(true)
                            } else {
                                locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                            }
                        },
                    )
                }
                Text(
                    "Changes to the advertised iAP2 capability take effect on the next CarPlay connection.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ControlCard(title = "Cluster navigation", icon = Icons.Default.DisplaySettings) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Show CarPlay guidance on the instrument cluster", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Relays CarPlay turn-by-turn guidance, exit labels, junction geometry, and lane guidance " +
                                "to the vehicle cluster. It does not mirror the CarPlay map.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = clusterNavigationEnabled,
                        onCheckedChange = { enabled ->
                            clusterNavigationEnabled = enabled
                            ClusterNavigationController.setEnabled(context, enabled)
                        },
                    )
                }
            }
            ControlCard(title = "Cluster guidance details", icon = Icons.Default.DisplaySettings) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Show lane guidance", style = MaterialTheme.typography.titleMedium)
                        Text("Display recommended lanes with the upcoming turn.", style = MaterialTheme.typography.bodyMedium)
                    }
                    Switch(checked = laneDisplayEnabled, onCheckedChange = {
                        laneDisplayEnabled = it
                        laneStore.setStrip(it)
                    })
                }
                Text("Lane icon", style = MaterialTheme.typography.titleSmall)
                LaneIconMode.entries.forEach { option ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable {
                        laneIconMode = option
                        laneStore.setIcon(option)
                    }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = laneIconMode == option, onClick = {
                            laneIconMode = option
                            laneStore.setIcon(option)
                        })
                        Text(option.label)
                    }
                }
                Text("Recommended lane color", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { laneColor = null; laneStore.setLaneColor(null) }) { Text("White") }
                    InlayColor.entries.forEach { color ->
                        TextButton(onClick = { laneColor = color; laneStore.setLaneColor(color) }) {
                            Text(if (laneColor == color) "✓ ${color.label}" else color.label)
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Turn alert", style = MaterialTheme.typography.titleMedium)
                        Text("Flash the turn icon as the vehicle approaches a maneuver.", style = MaterialTheme.typography.bodyMedium)
                    }
                    Switch(checked = turnAlertEnabled, onCheckedChange = {
                        turnAlertEnabled = it
                        turnAlertStore.setEnabled(it)
                    })
                }
                Text("Alert distance", style = MaterialTheme.typography.titleSmall)
                AlertTiming.entries.forEach { option ->
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable {
                            turnAlertTiming = option
                            turnAlertStore.setTiming(option)
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = turnAlertTiming == option, onClick = {
                            turnAlertTiming = option
                            turnAlertStore.setTiming(option)
                        })
                        Column {
                            Text(option.label, style = MaterialTheme.typography.bodyLarge)
                            Text(option.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Text("Flash speed", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FlashSpeed.entries.forEach { option ->
                        TextButton(onClick = { turnAlertSpeed = option; turnAlertStore.setSpeed(option) }) {
                            Text(if (turnAlertSpeed == option) "✓ ${option.label}" else option.label)
                        }
                    }
                }
                Text("Alert color", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InlayColor.entries.forEach { option ->
                        TextButton(onClick = { turnAlertColor = option; turnAlertStore.setColor(option) }) {
                            Text(if (turnAlertColor == option) "✓ ${option.label}" else option.label)
                        }
                    }
                }
                Text("Alert style", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AlertStyle.entries.forEach { option ->
                        TextButton(onClick = { turnAlertStyle = option; turnAlertStore.setStyle(option) }) {
                            Text(if (turnAlertStyle == option) "✓ ${option.label}" else option.label)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Hold alert color for the final 3 seconds", modifier = Modifier.weight(1f))
                    Switch(checked = turnAlertSolidLast, onCheckedChange = {
                        turnAlertSolidLast = it
                        turnAlertStore.setSolidLast(it)
                    })
                }
            }
            ControlCard(title = "CarPlay microphone source", icon = Icons.Default.SettingsInputComponent) {
                Text("Choose the Android microphone route used for Siri and calls. Changes apply the next time the microphone starts.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                MicSource.entries.forEach { option ->
                    Row(modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable {
                        microphoneSource = option
                        carlinkManager.setMicrophoneSource(option)
                    }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = microphoneSource == option, onClick = {
                            microphoneSource = option
                            carlinkManager.setMicrophoneSource(option)
                        })
                        Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
                            Text(option.title, style = MaterialTheme.typography.titleMedium)
                            Text(option.description, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            ControlCard(title = "CarPlay call notifications", icon = Icons.Default.PhoneDisabled) {
                Text(
                    "Choose how AAOS displays calls reported by the iPhone over CarPlay.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                CallNotificationMode.entries.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp)
                            .clickable {
                                callNotificationMode = option
                                carlinkManager.setCallNotificationMode(option)
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = callNotificationMode == option,
                            onClick = {
                                callNotificationMode = option
                                carlinkManager.setCallNotificationMode(option)
                            },
                        )
                        Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
                            Text(option.title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                option.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Which of the Adapter card's confirmations is open, if any. */
private enum class AdapterConfirm { RESTART_WIRELESS, REBOOT, DISCONNECT_ADAPTER }

/** Box-side status and the adapter-level actions. */
@Composable
private fun AdapterCard(
    carlinkManager: CarlinkManager,
    connected: Boolean,
    modifier: Modifier = Modifier,
    onNetworkModeApplied: (CarPlayNetworkMode) -> Unit = {},
) {
    var confirm by remember { mutableStateOf<AdapterConfirm?>(null) }
    val scope = rememberCoroutineScope()
    // Read once per composition; the box cannot gain the capability mid-session (it is a
    // HELLO_ACK caps bit), and `connected` recomposes this card on every session edge anyway.
    val phoneDisconnectSupported = carlinkManager.phoneDisconnectSupported
    var selectedUsbMode by remember(carlinkManager) { mutableStateOf(carlinkManager.usbTransportMode) }
    var selectedNetworkMode by remember(carlinkManager) { mutableStateOf(carlinkManager.carPlayNetworkMode) }
    var selectedUsbIdentity by remember(carlinkManager) { mutableStateOf(carlinkManager.usbAdapterIdentity) }
    var controlPort by remember(carlinkManager) { mutableStateOf(carlinkManager.configuredCarPlayControlPort.toString()) }
    var bridgeOnly by remember(carlinkManager) { mutableStateOf(carlinkManager.carPlayBridgeOnly) }
    var applyingPort by remember(carlinkManager) { mutableStateOf(false) }
    var portMessage by remember(carlinkManager) { mutableStateOf<String?>(null) }

    ControlCard(title = "Connection and receiver", icon = Icons.Default.SettingsInputComponent, modifier = modifier) {
        Text("USB connection path", style = MaterialTheme.typography.titleMedium)
        if (carlinkManager.appOwnedBluetoothEnabled) Text("USB settings take effect when OCBM adapter transport is selected.")
        UsbTransportMode.entries.forEach { mode ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable { selectedUsbMode = mode },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selectedUsbMode == mode, onClick = { selectedUsbMode = mode })
                Column {
                    Text(mode.label, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (mode == UsbTransportMode.SPLIT_HELPER)
                            "Uses the separate GM USB handler. Default for the truck."
                        else
                            "Opens the adapter from this app and requests its own USB grant. For head units that block helper sideloading.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (selectedUsbMode == UsbTransportMode.DIRECT) {
            Text("Adapter USB identity", style = MaterialTheme.typography.titleMedium)
            UsbAdapterIdentity.entries.forEach { identity ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { selectedUsbIdentity = identity },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selectedUsbIdentity == identity, onClick = { selectedUsbIdentity = identity })
                    Text(identity.label, style = MaterialTheme.typography.bodyLarge)
                }
            }
            Text("Select the identity already configured on the adapter. This setting does not change its firmware.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selectedUsbMode != carlinkManager.usbTransportMode ||
            (selectedUsbMode == UsbTransportMode.DIRECT && selectedUsbIdentity != carlinkManager.usbAdapterIdentity)) {
            Button(
                onClick = {
                    carlinkManager.setUsbTransportMode(selectedUsbMode)
                    if (selectedUsbMode == UsbTransportMode.DIRECT) carlinkManager.setUsbAdapterIdentity(selectedUsbIdentity)
                    scope.launch(Dispatchers.IO) { carlinkManager.cleanSlateReset() }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Apply USB path and reconnect")
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text("CarPlay phone network", style = MaterialTheme.typography.titleMedium)
        if (selectedNetworkMode == CarPlayNetworkMode.DONGLE_WIFI_TUNNEL && !carlinkManager.socketTunnelSupported) {
            Text("The connected adapter firmware does not advertise socket-tunnel support yet. Direct vehicle-hotspot mode remains active.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        CarPlayNetworkMode.entries.forEach { mode ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable {
                    if (mode != CarPlayNetworkMode.DONGLE_WIFI_TUNNEL || carlinkManager.socketTunnelSupported) {
                        selectedNetworkMode = mode
                    }
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = selectedNetworkMode == mode,
                    enabled = mode != CarPlayNetworkMode.DONGLE_WIFI_TUNNEL || carlinkManager.socketTunnelSupported,
                    onClick = {
                        if (mode != CarPlayNetworkMode.DONGLE_WIFI_TUNNEL || carlinkManager.socketTunnelSupported) {
                            selectedNetworkMode = mode
                        }
                    },
                )
                Column {
                    Text(mode.label, style = MaterialTheme.typography.bodyLarge)
                    Text(mode.description, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (selectedNetworkMode == CarPlayNetworkMode.PI_WIFI_PROXY) {
            Text("The app discovers the Pi on the vehicle network. Pair the phone with the Pi in iPhone Bluetooth settings, then select it under CarPlay session transport.",
                style = MaterialTheme.typography.bodySmall)
            Button(onClick = {
                carlinkManager.applyCarPlayNetworkMode(CarPlayNetworkMode.PI_WIFI_PROXY)
                onNetworkModeApplied(CarPlayNetworkMode.PI_WIFI_PROXY)
            }) { Text("Find Pi and reconnect") }
        }
        if (selectedNetworkMode != carlinkManager.carPlayNetworkMode) {
            Button(onClick = {
                // The reconnect can outlive this settings composition as the overlay/activity
                // changes. Let CarlinkManager own the coroutine so Compose cancellation cannot
                // abort adapter startup midway through the dongle-AP wait.
                carlinkManager.applyCarPlayNetworkMode(selectedNetworkMode)
                onNetworkModeApplied(selectedNetworkMode)
            }, modifier = Modifier.fillMaxWidth()) {
                Text("Apply phone network and reconnect")
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text("CarPlay control listener", style = MaterialTheme.typography.titleMedium)
        val parsedControlPort = controlPort.toIntOrNull()
        val validControlPort = parsedControlPort != null && parsedControlPort in 1024..65535
        OutlinedTextField(
            value = controlPort, onValueChange = { controlPort = it; portMessage = null },
            label = { Text("Listening TCP port") }, singleLine = true, enabled = !applyingPort,
            isError = !validControlPort,
            supportingText = { Text("Default 7011; try 5000 on the Blazer. Used for the listener, mDNS, and iAP2 StartSession. Stop any diagnostic listener using this port first.") },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        listOf(false to "All local addresses (wildcard)", true to "Vehicle hotspot bridge IPv4 only").forEach { (value, label) ->
            Row(modifier = Modifier.fillMaxWidth().clickable(enabled = !applyingPort) { bridgeOnly = value },
                verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = bridgeOnly == value, enabled = !applyingPort, onClick = { bridgeOnly = value })
                Text(label, style = MaterialTheme.typography.bodyLarge)
            }
        }
        Text("Bridge-only mode resolves the current bridge address when the receiver starts and sends only that IPv4 address in StartSession. If the bridge address changes, restart the session.",
            style = MaterialTheme.typography.bodySmall)
        Button(
            enabled = validControlPort && !applyingPort && !carlinkManager.resetInFlight,
            modifier = Modifier.fillMaxWidth().heightIn(min = AutomotiveDimens.ButtonMinHeight),
            onClick = {
                val port = controlPort.toIntOrNull() ?: return@Button
                applyingPort = true
                scope.launch {
                    try {
                        portMessage = if (carlinkManager.applyCarPlayControlPort(port, bridgeOnly)) "Listener settings saved; session restart attempted."
                            else "Could not apply port; retry when the app is ready."
                    } finally { applyingPort = false }
                }
            },
        ) { Text(if (applyingPort) "Restarting session…" else "Apply listener settings and reconnect") }
        portMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Spacer(modifier = Modifier.height(16.dp))
        if (carlinkManager.appOwnedBluetoothEnabled) {
            Text("${if (carlinkManager.carPlayNetworkMode == CarPlayNetworkMode.PI_WIFI_PROXY) "Pi" else "Android"} Bluetooth phone: ${carlinkManager.appBluetoothPhone ?: "none selected"}",
                style = MaterialTheme.typography.titleMedium)
        } else {
            Text(
                text = "Connect phone to: ${carlinkManager.adapterName}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            BoxStatusLine(carlinkManager, Modifier.padding(top = 4.dp))
            Spacer(modifier = Modifier.height(16.dp))

            // Shown DISABLED rather than hidden: the control belongs on this card (carlink_native
            // had it, and the driver expects it here), and a visible reason beats a missing button.
            ControlButton(DISCONNECT_PHONE, enabled = connected && phoneDisconnectSupported, onClick = {
                logWarn("[UI_ACTION] Disconnect Phone button clicked", tag = "UI")
                carlinkManager.disconnectPhone()
            })
            if (!phoneDisconnectSupported) {
                Text(
                    text = DISCONNECT_PHONE_UNSUPPORTED,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            ControlButton(RESTART_WIRELESS, enabled = connected, onClick = { confirm = AdapterConfirm.RESTART_WIRELESS })
            Spacer(modifier = Modifier.height(12.dp))
            ControlButton(REBOOT_ADAPTER, enabled = true, onClick = { confirm = AdapterConfirm.REBOOT })
            Spacer(modifier = Modifier.height(12.dp))
            ControlButton(DISCONNECT_ADAPTER, enabled = connected, onClick = { confirm = AdapterConfirm.DISCONNECT_ADAPTER })
        }
    }

    AdapterConfirmDialog(confirm, carlinkManager, scope, onDone = { confirm = null })
}

/** The confirmation for whichever Adapter action is pending, and the action it guards. */
@Composable
private fun AdapterConfirmDialog(
    confirm: AdapterConfirm?,
    carlinkManager: CarlinkManager,
    scope: CoroutineScope,
    onDone: () -> Unit,
) {
    when (confirm) {
        AdapterConfirm.RESTART_WIRELESS ->
            ConfirmDialog(RESTART_WIRELESS_CONFIRM, onDismiss = onDone, onConfirm = {
                logWarn("[UI_ACTION] Restart Wireless confirmed", tag = "UI")
                onDone()
                carlinkManager.restartWireless()
            })
        AdapterConfirm.REBOOT ->
            ConfirmDialog(REBOOT_CONFIRM, onDismiss = onDone, onConfirm = {
                logWarn("[UI_ACTION] Reboot Adapter confirmed", tag = "UI")
                onDone()
                scope.launch(Dispatchers.IO) { carlinkManager.rebootAdapter() }
            })
        AdapterConfirm.DISCONNECT_ADAPTER ->
            ConfirmDialog(DISCONNECT_ADAPTER_CONFIRM, onDismiss = onDone, onConfirm = {
                logWarn("[UI_ACTION] Disconnect Adapter confirmed", tag = "UI")
                onDone()
                scope.launch { carlinkManager.disconnect() }
            })
        null -> Unit
    }
}

/** Text of one confirmation dialog. */
private class ConfirmSpec(
    val icon: ImageVector,
    val title: String,
    val text: String,
    val confirmLabel: String,
)

private val RESTART_WIRELESS_CONFIRM =
    ConfirmSpec(
        icon = Icons.Default.Wifi,
        title = "Restart wireless stack?",
        text =
            "The adapter restarts its Bluetooth and Wi-Fi and advertises again. " +
                "A wirelessly connected phone will drop and reconnect; a phone on the USB cable is not affected.",
        confirmLabel = "Restart Wireless",
    )

private val REBOOT_CONFIRM =
    ConfirmSpec(
        icon = Icons.Default.RestartAlt,
        title = "Reboot Adapter?",
        text =
            "The adapter will reboot and any live projection session will drop. " +
                "It is gone for about 50 seconds while it restarts and re-enumerates on USB. " +
                "The USB helper should reconnect automatically when it returns.",
        confirmLabel = "Reboot",
    )

private val DISCONNECT_ADAPTER_CONFIRM =
    ConfirmSpec(
        icon = Icons.Default.PowerOff,
        title = "Disconnect Adapter?",
        text =
            "Ends the session with the adapter and releases it. Any live projection drops; " +
                "the app stays open and reconnects when you use Reset Connection or replug the adapter.",
        confirmLabel = "Disconnect",
    )

private val RESET_CONNECTION_CONFIRM =
    ConfirmSpec(
        icon = Icons.Default.Usb,
        title = "Reset Connection?",
        text =
            "Ends the current phone session and the link to the adapter, waits for the adapter to " +
                "finish shutting the session down, then reconnects and starts a new session. " +
                "The phone reconnects on its own; expect about 10 seconds without projection.",
        confirmLabel = "Reset",
    )

/** Wide confirmation with the same touch targets as the automotive controls. */
@Composable
private fun ConfirmDialog(
    spec: ConfirmSpec,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth(0.92f),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(
                        imageVector = spec.icon,
                        contentDescription = null,
                        tint = colorScheme.tertiary,
                        modifier = Modifier.size(40.dp),
                    )
                    Text(spec.title, style = MaterialTheme.typography.headlineMedium)
                }
                Text(spec.text, style = MaterialTheme.typography.bodyLarge)
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().heightIn(min = AutomotiveDimens.ButtonMinHeight),
                ) { Text("Cancel", style = MaterialTheme.typography.titleLarge) }
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.fillMaxWidth().heightIn(min = AutomotiveDimens.ButtonMinHeight),
                    colors = ButtonDefaults.buttonColors(containerColor = colorScheme.error, contentColor = colorScheme.onError),
                ) { Text(spec.confirmLabel, style = MaterialTheme.typography.titleLarge) }
            }
        }
    }
}

/** Display mode, decoder and session resets. */
@Composable
private fun AppControlCard(
    carlinkManager: CarlinkManager,
    connected: Boolean,
    displayMode: DisplayMode,
    onDisplayModeSelected: (DisplayMode) -> DisplayModeOutcome,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var showDeferredNotice by remember { mutableStateOf(false) }
    var showDisplayModeDialog by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var showFrameRateDialog by remember { mutableStateOf(false) }
    var selectedFrameRate by remember(carlinkManager) { mutableStateOf(carlinkManager.carPlayFrameRate) }
    var frameRateMessage by remember { mutableStateOf<String?>(null) }

    var isRestarting by remember(carlinkManager) { mutableStateOf(carlinkManager.resetInFlight) }

    ControlCard(title = "App Control", icon = Icons.Default.DisplaySettings, modifier = modifier) {
        TonalActionButton(label = "Display Mode", icon = displayMode.icon, onClick = { showDisplayModeDialog = true })
        Spacer(modifier = Modifier.height(12.dp))
        TonalActionButton(label = "Frame Rate: ${carlinkManager.carPlayFrameRate.label}", icon = Icons.Default.VideoSettings, onClick = {
            selectedFrameRate = carlinkManager.carPlayFrameRate
            showFrameRateDialog = true
        })
        frameRateMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

        Spacer(modifier = Modifier.height(12.dp))
        ControlButton(RESET_DECODER, enabled = !isRestarting, onClick = {
            logWarn("[UI_ACTION] Reset Decoder button clicked", tag = "UI")
            carlinkManager.resetVideoDecoder()
        })
        Spacer(modifier = Modifier.height(12.dp))
        ControlButton(
            RESET_CONNECTION,
            enabled = connected,
            isProcessing = isRestarting,
            onClick = { showResetConfirm = true },
        )
    }

    if (showFrameRateDialog) {
        AlertDialog(
            onDismissRequest = { showFrameRateDialog = false },
            title = { Text("CarPlay frame rate") },
            text = {
                Column {
                    Text("Maximum frame rate offered to the phone. Applying restarts the session.")
                    CarPlayFrameRate.entries.forEach { mode ->
                        Row(modifier = Modifier.fillMaxWidth().clickable { selectedFrameRate = mode }.heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = selectedFrameRate == mode, onClick = { selectedFrameRate = mode })
                            Text(mode.label)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !isRestarting, onClick = {
                    showFrameRateDialog = false
                    isRestarting = true
                    scope.launch {
                        try {
                            frameRateMessage = if (carlinkManager.applyCarPlayFrameRate(selectedFrameRate)) "Frame rate saved; reconnecting."
                                else "Could not save or restart the session."
                        } finally { isRestarting = false }
                    }
                }) { Text("Apply and reconnect") }
            },
            dismissButton = { TextButton(onClick = { showFrameRateDialog = false }) { Text("Cancel") } },
        )
    }

    if (showResetConfirm) {
        ResetConnectionDialog(
            onConfirm = {
                logWarn("[UI_ACTION] Reset Connection confirmed", tag = "UI")
                showResetConfirm = false
                isRestarting = true
                scope.launch {
                    try {
                        carlinkManager.cleanSlateReset()
                    } finally {
                        isRestarting = false
                    }
                }
            },
            onDismiss = { showResetConfirm = false },
        )
    }


    if (showDisplayModeDialog) {
        DisplayModeDialog(
            currentMode = displayMode,
            onDismiss = { showDisplayModeDialog = false },
            onApply = { mode ->
                showDisplayModeDialog = false
                logInfo("[DISPLAY] User applied display mode: ${mode.name}", tag = "UI")
                if (onDisplayModeSelected(mode) == DisplayModeOutcome.DEFERRED) showDeferredNotice = true
            },
        )
    }

    if (showDeferredNotice) DisplayModeDeferredNotice(onDismiss = { showDeferredNotice = false })
}

/** Shown when a display-mode choice was persisted but held back by a live phone session. */
@Composable
private fun DisplayModeDeferredNotice(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(imageVector = Icons.Default.DisplaySettings, contentDescription = null) },
        title = { Text("Display mode saved") },
        text = {
            Text(
                "A phone session is active, so the new display mode applies at the next connection. " +
                    "The current session keeps its layout.",
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

/**
 * Confirmation for the clean-slate reset — shared with the loading overlay's Reset Device
 * (`MainScreen`), which runs the same [CarlinkManager.cleanSlateReset].
 */
@Composable
internal fun ResetConnectionDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ConfirmDialog(RESET_CONNECTION_CONFIRM, onConfirm = onConfirm, onDismiss = onDismiss)
}

/** Neutral (non-destructive) tonal action: icon + label, automotive touch height. */
@Composable
private fun TonalActionButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = AutomotiveDimens.ButtonMinHeight),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Icon(imageVector = icon, contentDescription = label, modifier = Modifier.size(AutomotiveDimens.IconSize))
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
