package com.carlink.ui.settings

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.carlink.logging.logInfo
import com.carlink.CarlinkManager
import com.carlink.diagnostics.outboundTcpProbe
import com.carlink.ocbm.StartSessionHotspot
import com.carlink.ui.components.ControlCard
import com.carlink.ui.theme.AutomotiveDimens
import androidx.compose.foundation.layout.heightIn
import java.net.InetAddress
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface
import zeno.gmccpa.ReceiverTcpDiagnostics
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class InterfaceEntry(
    val name: String,
    val up: String,
    val mtu: String,
    val flags: String,
    val addresses: List<String>,
)

private data class AndroidNetworkEntry(
    val network: String,
    val active: Boolean,
    val transports: String,
    val capabilities: String,
    val interfaceName: String,
    val addresses: List<String>,
    val routes: List<String>,
    val dnsServers: List<String>,
)

private data class UsbInterfaceEntry(
    val id: Int,
    val alternateSetting: Int,
    val classTuple: String,
    val endpoints: List<String>,
)

private data class UsbDeviceEntry(
    val name: String,
    val vendorId: Int,
    val productId: Int,
    val classTuple: String,
    val permissionGranted: Boolean,
    val interfaces: List<UsbInterfaceEntry>,
)

private data class NetworkSnapshot(
    val capturedAt: String,
    val usbDevices: List<UsbDeviceEntry>,
    val interfaces: List<InterfaceEntry>,
    val networks: List<AndroidNetworkEntry>,
    val errors: List<String>,
) {
    fun asLogLines(): List<String> = buildList {
        add("[NETWORK_DIAGNOSTICS] captured=$capturedAt usbDevices=${usbDevices.size} interfaces=${interfaces.size} androidNetworks=${networks.size}")
        usbDevices.forEach { device ->
            add(
                "[USB_DIAGNOSTICS] device path=${device.name} vid=0x${device.vendorId.toString(16).padStart(4, '0')} " +
                    "pid=0x${device.productId.toString(16).padStart(4, '0')} class=${device.classTuple} permission=${device.permissionGranted}",
            )
            device.interfaces.forEach { intf ->
                add(
                    "[USB_DIAGNOSTICS] interface device=${device.name} id=${intf.id} alt=${intf.alternateSetting} " +
                        "class=${intf.classTuple} endpoints=${intf.endpoints.joinToString().ifBlank { "none" }}",
                )
            }
        }
        interfaces.forEach { item ->
            add("[NETWORK_DIAGNOSTICS] interface name=${item.name} up=${item.up} mtu=${item.mtu} flags=${item.flags} addresses=${item.addresses.joinToString()}")
        }
        networks.forEach { item ->
            add("[NETWORK_DIAGNOSTICS] network=${item.network} active=${item.active} transports=${item.transports} capabilities=${item.capabilities} interface=${item.interfaceName} addresses=${item.addresses.joinToString()} routes=${item.routes.joinToString()} dns=${item.dnsServers.joinToString()}")
        }
        errors.forEach { add("[NETWORK_DIAGNOSTICS] error=$it") }
    }
}

@Composable
internal fun NetworkDiagnosticsTabContent(carlinkManager: CarlinkManager) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<NetworkSnapshot?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var outboundHost by remember { mutableStateOf("") }
    var outboundPort by remember { mutableStateOf("8081") }
    var testingOutbound by remember { mutableStateOf(false) }
    var outboundResult by remember { mutableStateOf<String?>(null) }

    fun refresh(logSnapshot: Boolean) {
        if (refreshing) return
        refreshing = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { collectNetworkSnapshot(context) }
            snapshot = result
            refreshing = false
            if (logSnapshot) result.asLogLines().forEach { logInfo(it, tag = "NETWORK") }
        }
    }

    LaunchedEffect(Unit) {
        refresh(logSnapshot = true)
        while (isActive) {
            delay(3_000)
            refresh(logSnapshot = false)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Network diagnostics", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Live view of interfaces and Android network links. Keep this tab open while testing NCM. " +
                "USB descriptors and permission state are included; this does not request permission or open devices. " +
                "The list refreshes every 3 seconds; use Refresh and export Logs to save a snapshot.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = { refresh(logSnapshot = true) }, enabled = !refreshing) {
            Text(if (refreshing) "Refreshing…" else "Refresh and record snapshot")
        }
        ControlCard(title = "Outbound TCP test", icon = Icons.Default.Wifi) {
            Text("On a computer connected to the same network, run nc -l 8081. Enter that computer's IPv4 address " +
                "below, then run the test. The computer should display CARLINK_TCP_PROBE. Results are saved in Logs.",
                style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = outboundHost,
                onValueChange = { outboundHost = it; outboundResult = null },
                label = { Text("Computer IPv4 address") },
                placeholder = { Text("192.168.1.100") },
                enabled = !testingOutbound,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            OutlinedTextField(
                value = outboundPort,
                onValueChange = { outboundPort = it; outboundResult = null },
                label = { Text("TCP port") },
                enabled = !testingOutbound,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            Button(
                enabled = !testingOutbound && outboundHost.isNotBlank(),
                modifier = Modifier.heightIn(min = AutomotiveDimens.ButtonMinHeight),
                onClick = {
                    val host = outboundHost.trim()
                    val port = outboundPort.toIntOrNull() ?: 0
                    testingOutbound = true
                    outboundResult = null
                    scope.launch {
                        try {
                            val result = withContext(Dispatchers.IO) {
                                collectNetworkSnapshot(context).asLogLines().forEach { logInfo(it, tag = "NETWORK") }
                                logInfo("[TCP_OUTBOUND_TEST] starting remote=$host:$port timeout_ms=3000", tag = "NETWORK")
                                outboundTcpProbe(host, port).also {
                                    logInfo("[TCP_OUTBOUND_TEST] $it", tag = "NETWORK")
                                }
                            }
                            outboundResult = result
                        } finally {
                            testingOutbound = false
                        }
                    }
                },
            ) { Text(if (testingOutbound) "Connecting…" else "Run outbound TCP test") }
            outboundResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        snapshot?.let { current ->
            Text("Captured ${current.capturedAt}", style = MaterialTheme.typography.bodySmall)
            ControlCard(title = "USB devices (${current.usbDevices.size})", icon = Icons.Default.Wifi) {
                if (current.usbDevices.isEmpty()) Text("UsbManager reports no attached USB devices")
                current.usbDevices.forEach { device ->
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(
                            "${device.name} · %04X:%04X".format(device.vendorId, device.productId),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "Device class ${device.classTuple} · permission ${if (device.permissionGranted) "granted" else "not granted"}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (device.interfaces.isEmpty()) Text("No interfaces reported", style = MaterialTheme.typography.bodySmall)
                        device.interfaces.forEach { intf ->
                            Text(
                                "Interface ${intf.id}.${intf.alternateSetting}: class ${intf.classTuple}; " +
                                    "endpoints ${intf.endpoints.ifEmpty { listOf("none") }.joinToString()}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
            ControlCard(title = "OS interfaces (${current.interfaces.size})", icon = Icons.Default.Wifi) {
                if (current.interfaces.isEmpty()) Text("No interfaces reported")
                current.interfaces.forEach { item ->
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(item.name, style = MaterialTheme.typography.titleMedium)
                        Text("State: ${item.up} · MTU: ${item.mtu} · ${item.flags}", style = MaterialTheme.typography.bodySmall)
                        Text("Addresses: ${item.addresses.ifEmpty { listOf("none") }.joinToString()}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            ControlCard(title = "Android network links (${current.networks.size})", icon = Icons.Default.Wifi) {
                if (current.networks.isEmpty()) Text("Android reports no active network links")
                current.networks.forEach { item ->
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(item.network, style = MaterialTheme.typography.titleMedium)
                            Text(if (item.active) "Active" else "Available", style = MaterialTheme.typography.bodySmall)
                        }
                        Text("Transport: ${item.transports} · ${item.capabilities}", style = MaterialTheme.typography.bodySmall)
                        Text("Interface: ${item.interfaceName.ifBlank { "unknown" }}", style = MaterialTheme.typography.bodySmall)
                        Text("Addresses: ${item.addresses.ifEmpty { listOf("none") }.joinToString()}", style = MaterialTheme.typography.bodySmall)
                        Text("Routes: ${item.routes.ifEmpty { listOf("none") }.joinToString()}", style = MaterialTheme.typography.bodySmall)
                        Text("DNS: ${item.dnsServers.ifEmpty { listOf("none") }.joinToString()}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (current.errors.isNotEmpty()) {
                ControlCard(title = "Read errors", icon = Icons.Default.Wifi) {
                    current.errors.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
        } ?: Text("Collecting interface information…")
    }
}

private fun collectNetworkSnapshot(context: Context): NetworkSnapshot {
    val errors = mutableListOf<String>()
    val usbDevices = collectUsbDevices(context, errors)
    val interfaces =
        try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .sortedBy { it.name }
                .map { networkInterface ->
                    InterfaceEntry(
                        name = networkInterface.name,
                        up = runCatching { networkInterface.isUp.toString() }.getOrElse { "error:${it.javaClass.simpleName}" },
                        mtu = runCatching { networkInterface.mtu.toString() }.getOrElse { "error:${it.javaClass.simpleName}" },
                        flags = buildList {
                            if (runCatching { networkInterface.isLoopback }.getOrDefault(false)) add("loopback")
                            if (runCatching { networkInterface.isPointToPoint }.getOrDefault(false)) add("point-to-point")
                            if (runCatching { networkInterface.supportsMulticast() }.getOrDefault(false)) add("multicast")
                        }.joinToString().ifBlank { "-" },
                        addresses = networkInterface.interfaceAddresses.mapNotNull { address ->
                            val host = address.address?.hostAddress ?: return@mapNotNull null
                            "$host/${address.networkPrefixLength}"
                        },
                    )
                }
        } catch (error: Exception) {
            errors += "NetworkInterface enumeration: ${error.javaClass.simpleName}: ${error.message}"
            emptyList()
        }

    val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    val activeNetwork = runCatching { connectivity?.activeNetwork }.getOrNull()
    val networks =
        try {
            connectivity?.allNetworks.orEmpty().mapNotNull { network ->
                val capabilities = connectivity?.getNetworkCapabilities(network)
                val links = connectivity?.getLinkProperties(network)
                if (capabilities == null && links == null) return@mapNotNull null
                AndroidNetworkEntry(
                    network = network.toString(),
                    active = network == activeNetwork,
                    transports = capabilities?.let(::transportNames).orEmpty().ifBlank { "unknown" },
                    capabilities = capabilities?.let(::capabilitySummary).orEmpty().ifBlank { "unknown" },
                    interfaceName = links?.interfaceName.orEmpty(),
                    addresses = links?.linkAddresses.orEmpty().map { it.toString() },
                    routes = links?.routes.orEmpty().map { it.toString() },
                    dnsServers = links?.dnsServers.orEmpty().map { it.hostAddress.orEmpty() },
                )
            }.sortedWith(compareByDescending<AndroidNetworkEntry> { it.active }.thenBy { it.interfaceName })
        } catch (error: Exception) {
            errors += "ConnectivityManager enumeration: ${error.javaClass.simpleName}: ${error.message}"
            emptyList()
        }

    return NetworkSnapshot(
        capturedAt = DateFormat.getDateTimeInstance().format(Date()),
        usbDevices = usbDevices,
        interfaces = interfaces,
        networks = networks,
        errors = errors,
    )
}

private fun collectUsbDevices(context: Context, errors: MutableList<String>): List<UsbDeviceEntry> {
    val usb = context.getSystemService(Context.USB_SERVICE) as? UsbManager
    if (usb == null) {
        errors += "UsbManager unavailable"
        return emptyList()
    }

    return try {
        usb.deviceList.values.sortedWith(compareBy<UsbDevice> { it.vendorId }.thenBy { it.productId }).map { device ->
            val interfaces =
                runCatching {
                    (0 until device.interfaceCount).map { index ->
                        val intf = device.getInterface(index)
                        UsbInterfaceEntry(
                            id = intf.id,
                            alternateSetting = intf.alternateSetting,
                            classTuple = classTuple(intf.interfaceClass, intf.interfaceSubclass, intf.interfaceProtocol),
                            endpoints =
                                (0 until intf.endpointCount).map { endpointIndex ->
                                    val endpoint = intf.getEndpoint(endpointIndex)
                                    val direction = if (endpoint.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"
                                    "0x${endpoint.address.toString(16).padStart(2, '0')} $direction " +
                                        "${endpointType(endpoint.type)} maxPacket=${endpoint.maxPacketSize}"
                                },
                        )
                    }
                }.getOrElse { error ->
                    errors += "USB interfaces for ${device.deviceName}: ${error.javaClass.simpleName}: ${error.message}"
                    emptyList()
                }
            UsbDeviceEntry(
                name = device.deviceName,
                vendorId = device.vendorId,
                productId = device.productId,
                classTuple = classTuple(device.deviceClass, device.deviceSubclass, device.deviceProtocol),
                permissionGranted = runCatching { usb.hasPermission(device) }.getOrDefault(false),
                interfaces = interfaces,
            )
        }
    } catch (error: Exception) {
        errors += "UsbManager device enumeration: ${error.javaClass.simpleName}: ${error.message}"
        emptyList()
    }
}

private fun classTuple(clazz: Int, subclass: Int, protocol: Int): String =
    "%02x/%02x/%02x".format(clazz, subclass, protocol)

private fun endpointType(type: Int): String =
    when (type) {
        UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "control"
        UsbConstants.USB_ENDPOINT_XFER_ISOC -> "isochronous"
        UsbConstants.USB_ENDPOINT_XFER_BULK -> "bulk"
        UsbConstants.USB_ENDPOINT_XFER_INT -> "interrupt"
        else -> "type-$type"
    }

private fun transportNames(capabilities: NetworkCapabilities): String = buildList {
    if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("Ethernet")
    if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("Wi-Fi")
    if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("Cellular")
    if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
    if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add("Bluetooth")
    if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI_AWARE)) add("Wi-Fi Aware")
}.joinToString().ifBlank { "other/unspecified" }

private fun capabilitySummary(capabilities: NetworkCapabilities): String = buildList {
    if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) add("internet")
    if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) add("validated")
    if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) add("unmetered")
    if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)) add("not-restricted")
}.joinToString().ifBlank { "no common capabilities" }
