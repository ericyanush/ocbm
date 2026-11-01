package com.carlink.ui.settings

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.carlink.diagnostics.DiagnosticHttpServer
import com.carlink.logging.logInfo
import com.carlink.ocbm.StartSessionHotspot
import com.carlink.ui.components.ControlCard
import com.carlink.ui.theme.AutomotiveDimens
import java.net.Inet4Address

@Composable
internal fun InboundTcpTestContent() {
    val test = remember { InboundTcpTest() }
    var port by remember { mutableStateOf("5000") }
    var bridgeOnly by remember { mutableStateOf(true) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(test, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) test.stop() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); test.stop() }
    }
    ControlCard(title = "Inbound TCP listener test", icon = Icons.Default.Wifi) {
        Text("Uses the existing vehicle network. Keep this tab open during the test; leaving it or backgrounding " +
            "Carlink closes the listener. Stop before changing the port or binding.", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = port, onValueChange = { port = it }, label = { Text("Listening TCP port") },
            supportingText = { Text("Compare 5000 and 18081. An occupied port reports a bind error.") },
            enabled = !test.busy, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
        )
        listOf(true to "Vehicle hotspot bridge (explicit IPv4 address)", false to "All interfaces (wildcard)").forEach { (value, label) ->
            Row(modifier = Modifier.fillMaxWidth().clickable(enabled = !test.busy) { bridgeOnly = value },
                verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = bridgeOnly == value, enabled = !test.busy, onClick = { bridgeOnly = value })
                Text(label, style = MaterialTheme.typography.bodyLarge)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = { test.start(port.toIntOrNull() ?: 0, bridgeOnly) }, enabled = !test.busy,
                modifier = Modifier.heightIn(min = AutomotiveDimens.ButtonMinHeight)) { Text("Start inbound listener") }
            Button(onClick = { test.stop() }, enabled = test.busy,
                modifier = Modifier.heightIn(min = AutomotiveDimens.ButtonMinHeight)) { Text("Stop listener") }
        }
        Text(test.status, style = MaterialTheme.typography.bodyMedium)
        test.command?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        test.lastEvent?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

private class InboundTcpTest {
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0
    private var server: DiagnosticHttpServer? = null
    var busy by mutableStateOf(false); private set
    var status by mutableStateOf("Not started"); private set
    var command by mutableStateOf<String?>(null); private set
    var lastEvent by mutableStateOf<String?>(null); private set

    private fun record(message: String) { logInfo("[TCP_INBOUND_TEST] $message", tag = "NETWORK") }

    fun start(port: Int, bridgeOnly: Boolean) {
        if (busy) return
        if (port !in 1024..65535) { status = "Enter a port between 1024 and 65535"; return }
        val token = ++generation
        busy = true; status = "Starting listener…"; lastEvent = null; command = null
        record("start port=$port binding=${if (bridgeOnly) "hotspot-IPv4" else "wildcard"}")
        Thread({
            var target: String? = null
            val result = runCatching {
                val iface = if (bridgeOnly) StartSessionHotspot.select(StartSessionHotspot.snapshot()) else null
                val address = iface?.addresses?.filterIsInstance<Inet4Address>()?.firstOrNull {
                    !it.isAnyLocalAddress && !it.isLoopbackAddress && !it.isMulticastAddress
                }
                if (bridgeOnly) checkNotNull(address) { "Hotspot bridge ${iface?.name} has no IPv4 address" }
                target = address?.hostAddress
                record("binding interface=${iface?.name ?: "all"} address=${address?.hostAddress ?: "wildcard"} port=$port")
                DiagnosticHttpServer(port = port, bindAddress = address) { event ->
                    record("port=$port $event")
                    main.post { if (generation == token) lastEvent = event }
                }
            }
            main.post {
                if (generation != token) { result.getOrNull()?.close(); return@post }
                result.fold(onSuccess = {
                    server = it
                    status = "Listening on ${it.localEndpoint}"
                    command = "On the Mac: curl --noproxy '*' --connect-timeout 5 --max-time 10 http://${target ?: "HEAD_UNIT_IP"}:$port/"
                    record("ready endpoint=${it.localEndpoint}; expected response=CARLINK_HOTSPOT_TEST_OK")
                }, onFailure = {
                    busy = false
                    status = "Listener failed: ${it.javaClass.simpleName}: ${it.message}"
                    record(status)
                })
            }
        }, "start-inbound-diagnostic").apply { isDaemon = true; start() }
    }

    fun stop() {
        ++generation
        runCatching { server?.close() }; server = null
        if (busy) record("stopped")
        busy = false; status = "Stopped"; command = null
    }
}
