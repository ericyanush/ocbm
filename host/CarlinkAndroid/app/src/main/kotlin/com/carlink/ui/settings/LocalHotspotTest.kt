package com.carlink.ui.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import com.carlink.diagnostics.DiagnosticHttpServer
import com.carlink.logging.logInfo
import com.carlink.ui.components.ControlCard
import com.carlink.ui.theme.AutomotiveDimens

@Composable
internal fun LocalHotspotTestContent() {
    if (Build.VERSION.SDK_INT >= 33) LocalHotspotTestSupported()
}

@RequiresApi(33)
@Composable
private fun LocalHotspotTestSupported() {
    val context = LocalContext.current
    val test = remember(context) { LocalHotspotTest(context.applicationContext) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(test, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) test.stop()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); test.stop() }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) test.start()
        else test.permissionDenied()
    }
    ControlCard(title = "Local-only hotspot test", icon = Icons.Default.Wifi) {
        Text("Creates a temporary Wi-Fi network without internet access and a diagnostic HTTP server on port 18081. " +
            "Turn off the vehicle hotspot first if startup reports incompatible mode. Keep this tab open: " +
            "leaving it or backgrounding Carlink stops the test. This test does not change CarPlay handoff settings.",
            style = MaterialTheme.typography.bodyMedium)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(
                enabled = !test.pending && !test.active,
                modifier = Modifier.heightIn(min = AutomotiveDimens.ButtonMinHeight),
                onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED)
                        test.start()
                    else permission.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
                },
            ) { Text(if (test.pending) "Starting…" else "Start test hotspot") }
            Button(onClick = { test.stop() }, enabled = test.active || test.pending,
                modifier = Modifier.heightIn(min = AutomotiveDimens.ButtonMinHeight)) { Text("Stop test") }
        }
        Text(test.status, style = MaterialTheme.typography.bodyMedium)
        if (test.active) {
            Text("SSID: ${test.ssid}", style = MaterialTheme.typography.titleMedium)
            Text("Password: ${test.password}", style = MaterialTheme.typography.titleMedium)
            Text("Join this network from the Mac or phone. Find the hotspot interface's IPv4 address in the network list below, " +
                "then open http://<hotspot-address>:18081/ . Success returns CARLINK_HOTSPOT_TEST_OK.", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@RequiresApi(33)
private class LocalHotspotTest(private val context: Context) {
    private val wifi = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var server: DiagnosticHttpServer? = null
    var pending by mutableStateOf(false); private set
    var active by mutableStateOf(false); private set
    var status by mutableStateOf("Not started"); private set
    var ssid by mutableStateOf(""); private set
    var password by mutableStateOf(""); private set

    private fun record(message: String) { logInfo("[LOCAL_HOTSPOT_TEST] $message", tag = "NETWORK") }
    fun permissionDenied() { status = "Nearby Wi-Fi permission not granted"; record(status) }

    fun start() {
        if (pending || active) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
            permissionDenied()
            return
        }
        val manager = wifi ?: run { status = "WifiManager unavailable"; record(status); return }
        val token = ++generation
        pending = true
        status = "Requesting local-only hotspot…"
        record("start requested")
        try {
            manager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(value: WifiManager.LocalOnlyHotspotReservation) {
                    pending = false
                    if (token != generation) { value.close(); record("late reservation closed after stop"); return }
                    reservation = value
                    val config = value.softApConfiguration
                    ssid = config.ssid.orEmpty()
                    password = config.passphrase.orEmpty()
                    active = true
                    status = "Hotspot ready; starting diagnostic listener…"
                    record("hotspot started ssid=$ssid security=${config.securityType}; password omitted")
                    Thread({
                        val result = runCatching { DiagnosticHttpServer(record = ::record) }
                        main.post {
                            if (token != generation || !active) { result.getOrNull()?.close(); return@post }
                            result.fold(onSuccess = {
                                server = it
                                status = "Hotspot ready; HTTP listener on port ${it.localPort}"
                                record("listener ready wildcard port=${it.localPort}")
                            }, onFailure = {
                                val message = "Listener failed: ${it.javaClass.simpleName}: ${it.message}"
                                stop(); status = message; record(message)
                            })
                        }
                    }, "start-hotspot-diagnostic").apply { isDaemon = true; start() }
                }
                override fun onFailed(reason: Int) {
                    pending = false
                    if (token != generation) return
                    val name = when (reason) {
                        ERROR_INCOMPATIBLE_MODE -> "INCOMPATIBLE_MODE (try turning off the vehicle hotspot)"
                        ERROR_TETHERING_DISALLOWED -> "TETHERING_DISALLOWED"
                        ERROR_NO_CHANNEL -> "NO_CHANNEL"
                        ERROR_GENERIC -> "GENERIC"
                        else -> "UNKNOWN"
                    }
                    status = "Hotspot failed: $name ($reason)"
                    record(status)
                }
                override fun onStopped() {
                    if (token != generation) return
                    stop(); status = "Hotspot stopped by Android"; record(status)
                }
            }, main)
        } catch (error: Exception) {
            pending = false
            status = "Hotspot request failed: ${error.javaClass.simpleName}: ${error.message}"
            record(status)
        }
    }

    fun stop() {
        ++generation
        val wasRunning = active || pending || server != null
        runCatching { server?.close() }; server = null
        runCatching { reservation?.close() }; reservation = null
        active = false
        ssid = ""; password = ""
        // Pending public-API requests cannot be cancelled directly. Close any late reservation.
        status = if (pending) "Stopped; waiting for pending Android callback" else "Stopped"
        if (wasRunning) record(status)
    }
}
