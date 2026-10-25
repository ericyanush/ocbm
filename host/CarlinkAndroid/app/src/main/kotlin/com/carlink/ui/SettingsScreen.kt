package com.carlink.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.carlink.CarlinkManager
import com.carlink.logging.RollingDiagnosticLog
import com.carlink.logging.logInfo
import com.carlink.logging.logWarn
import com.carlink.ui.adaptive.asWindowInsets
import com.carlink.ui.settings.ControlTabContent
import com.carlink.ui.settings.DisplayMode
import com.carlink.ui.settings.HotspotTabContent
import com.carlink.ui.settings.PhonesTabContent
import com.carlink.ui.settings.SettingsTab
import com.carlink.ui.theme.AutomotiveDimens
import com.carlink.util.EdgeInsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings screen: a navigation rail (back, Phones / Control tabs, close-app, version) beside the
 * selected tab's content. Composed OVER [MainScreen] so the video surface underneath survives;
 * closing it goes through [SettingsActions.onNavigateBack], which also nudges the decoder
 * (`CarlinkManager.recoverVideoFromOverlay`) so a frame that went stale under the overlay is
 * replaced without waiting for the next natural keyframe.
 */
@Composable
fun SettingsScreen(
    carlinkManager: CarlinkManager,
    connectionState: CarlinkManager.State,
    displayMode: DisplayMode,
    actions: SettingsActions,
) {
    var selectedTab by remember { mutableStateOf(SettingsTab.PHONES) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val diagnostics = remember(context) { RollingDiagnosticLog.getInitializedInstance() }
    var exporting by remember { mutableStateOf(false) }
    var logRevision by remember { mutableStateOf(0) }
    var showPurgeConfirmation by remember { mutableStateOf(false) }
    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
            if (uri != null && diagnostics != null) {
                exporting = true
                scope.launch {
                    val result = runCatching { withContext(Dispatchers.IO) { diagnostics.copyArchiveTo(uri) } }
                    exporting = false
                    logRevision++
                    Toast.makeText(context, result.fold({ "Exported $it bytes of diagnostics" }, { "Log export failed: ${it.message}" }), Toast.LENGTH_LONG).show()
                }
            } else {
                exporting = false
            }
        }

    LaunchedEffect(Unit) {
        logInfo("[UI_STATE] SettingsScreen opened - user is in app settings (NOT viewing CarPlay projection)", tag = "UI")
    }
    LaunchedEffect(selectedTab) {
        logInfo("[UI_STATE] Settings tab changed: $selectedTab", tag = "UI")
    }

    // The detected safe area (cutout / waterfall / corner arcs) pads in addition to the live bars.
    val safeArea = carlinkManager.displayProfile?.safeAreaInsets ?: EdgeInsets.NONE

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Row(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.union(safeArea.asWindowInsets()))) {
            SettingsRail(
                selectedTab = selectedTab,
                onTabSelected = { selectedTab = it },
                onNavigateBack = actions.onNavigateBack,
                onCloseApp = {
                    logWarn("[UI_ACTION] Close App confirmed", tag = "UI")
                    // Off the main thread (USB release + CT_STOP block), under the lifecycle
                    // mutex; the button finishes the Activity only once this returns.
                    carlinkManager.disconnect()
                },
            )
            Box(modifier = Modifier.fillMaxHeight().weight(1f)) {
                when (selectedTab) {
                    SettingsTab.PHONES -> PhonesPane(carlinkManager)
                    SettingsTab.WIFI -> HotspotTabContent(carlinkManager)
                    SettingsTab.CONTROL ->
                        ControlTabContent(carlinkManager, connectionState, displayMode, actions.onDisplayModeSelected)
                    SettingsTab.LOGS ->
                        LogsPane(diagnostics, exporting, logRevision, onExport = {
                            exporting = true
                            exportLauncher.launch("carlink-diagnostics-${System.currentTimeMillis()}.zip")
                        }, onOpenFiles = { openFilesApp(context) }, onPurge = { showPurgeConfirmation = true })
                }
            }
        }
    }

    if (showPurgeConfirmation) {
        AlertDialog(
            onDismissRequest = { showPurgeConfirmation = false },
            title = { Text("Delete diagnostic logs?") },
            text = { Text("This removes the app's private rolling logs. It does not delete files already exported to USB or other storage.") },
            confirmButton = {
                TextButton(onClick = {
                    showPurgeConfirmation = false
                    scope.launch {
                        val count = withContext(Dispatchers.IO) { diagnostics?.purge() ?: 0 }
                        logRevision++
                        Toast.makeText(context, "Purged $count log files", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showPurgeConfirmation = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun LogsPane(
    diagnostics: RollingDiagnosticLog?,
    exporting: Boolean,
    revision: Int,
    onExport: () -> Unit,
    onOpenFiles: () -> Unit,
    onPurge: () -> Unit,
) {
    val files = remember(diagnostics, revision) { diagnostics?.files().orEmpty() }
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement =
            androidx.compose.foundation.layout.Arrangement
                .spacedBy(16.dp),
    ) {
        Text("Diagnostics", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Rolling logs are captured automatically, including session and adapter-protocol diagnostics. " +
                "Export saves a ZIP through the system file picker; choose an attached USB drive if desired.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("${files.size} files · ${diagnostics?.totalBytes() ?: 0} bytes", style = MaterialTheme.typography.bodySmall)
        androidx.compose.material3.Button(onClick = onExport, enabled = diagnostics != null && !exporting) {
            Text(if (exporting) "Preparing export…" else "Export logs to USB / storage")
        }
        OutlinedButton(
            onClick = onOpenFiles,
            modifier = Modifier.heightIn(min = AutomotiveDimens.ButtonMinHeight),
        ) { Text("Open Files app") }
        TextButton(onClick = onPurge, enabled = diagnostics != null) { Text("Purge all app logs") }
        files.forEach { file ->
            Text("${file.name} · ${file.length()} bytes", style = MaterialTheme.typography.bodySmall)
        }
        if (diagnostics == null) {
            Text("Logging has not initialized yet.", color = MaterialTheme.colorScheme.error)
        }
    }
}

/** Open the AAOS file browser directly, using the working components from FilesLauncher. */
private fun openFilesApp(context: Context) {
    val targets = listOf(
        "com.android.documentsui" to "com.android.documentsui.files.FilesActivity",
        "com.android.documentsui" to "com.android.documentsui.LauncherActivity",
        "com.google.android.documentsui" to "com.android.documentsui.LauncherActivity",
    )
    for ((packageName, activityName) in targets) {
        val intent = Intent().setClassName(packageName, activityName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
            logInfo("[UI_ACTION] Open Files app: $packageName/$activityName", tag = "UI")
            return
        } catch (error: RuntimeException) {
            logWarn("[UI_ACTION] Files target unavailable: $packageName/$activityName (${error.javaClass.simpleName})", tag = "UI")
        }
    }
    Toast.makeText(context, "Files app is unavailable on this head unit", Toast.LENGTH_LONG).show()
}

/** The known-device grid, centred in the pane and scrolling when the pane is shorter than it. */
@Composable
private fun PhonesPane(carlinkManager: CarlinkManager) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp)) {
            PhonesTabContent(carlinkManager)
        }
    }
}

/** Left column: back button, the tab rail (items centred), close-app button and the version. */
@Composable
private fun SettingsRail(
    selectedTab: SettingsTab,
    onTabSelected: (SettingsTab) -> Unit,
    onNavigateBack: () -> Unit,
    onCloseApp: suspend () -> Unit,
) {
    val view = LocalView.current
    Column(modifier = Modifier.fillMaxHeight()) {
        Box(modifier = Modifier.padding(vertical = 20.dp, horizontal = 12.dp)) {
            FilledTonalIconButton(
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onNavigateBack()
                },
                modifier = Modifier.size(AutomotiveDimens.ButtonMinHeight),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    modifier = Modifier.size(AutomotiveDimens.IconSize),
                )
            }
        }
        NavigationRail(modifier = Modifier.weight(1f), containerColor = MaterialTheme.colorScheme.surface) {
            Spacer(modifier = Modifier.weight(1f))
            SettingsTab.entries.forEach { tab ->
                NavigationRailItem(
                    selected = selectedTab == tab,
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        onTabSelected(tab)
                    },
                    icon = { Icon(imageVector = tab.icon, contentDescription = tab.title) },
                    label = { Text(tab.title) },
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
            Spacer(modifier = Modifier.weight(1f))
        }
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CloseAppButton(onCloseApp)
            Spacer(modifier = Modifier.height(8.dp))
            VersionLabel()
        }
    }
}

/**
 * Close-app button with its confirmation; [onCloseApp] tears the session down (a suspend call —
 * it runs off the main thread), and the Activity finishes once it has returned, so onDestroy's
 * own synchronous `release()` never overlaps a teardown still in flight.
 */
@Composable
private fun CloseAppButton(onCloseApp: suspend () -> Unit) {
    val view = LocalView.current
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var showCloseConfirm by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }

    FilledTonalIconButton(
        onClick = {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            showCloseConfirm = true
        },
        modifier = Modifier.size(AutomotiveDimens.ButtonMinHeight),
    ) {
        Icon(
            imageVector = Icons.Default.Close,
            contentDescription = "Close App",
            modifier = Modifier.size(AutomotiveDimens.IconSize),
            tint = colorScheme.error,
        )
    }
    if (showCloseConfirm) {
        AlertDialog(
            onDismissRequest = { showCloseConfirm = false },
            title = { Text("Close App?") },
            text = { Text("This will stop all connections and close the app.") },
            confirmButton = {
                TextButton(
                    enabled = !closing,
                    onClick = {
                        closing = true
                        scope.launch {
                            try {
                                onCloseApp()
                            } finally {
                                (context as? Activity)?.finishAffinity()
                            }
                        }
                    },
                ) {
                    Text("Close", color = colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCloseConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun VersionLabel() {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val appVersion =
        remember {
            try {
                val pi = context.packageManager.getPackageInfo(context.packageName, 0)
                "${pi.versionName}+${pi.longVersionCode}"
            } catch (e: PackageManager.NameNotFoundException) {
                logWarn("[SettingsScreen] Failed to get package info: ${e.message}", tag = "UI")
                ""
            }
        }
    Text(text = "Version:", style = MaterialTheme.typography.bodySmall, color = colorScheme.onSurfaceVariant)
    Text(
        text = appVersion.ifEmpty { "- - -" },
        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
        color = if (appVersion.isEmpty()) colorScheme.onSurfaceVariant else colorScheme.onSurface,
    )
}
