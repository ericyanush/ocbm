package com.carlink.ocbm

import android.content.Context
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext

/** Finds the Pi by sending and receiving mDNS directly on GM's hotspot bridge interface. */
internal class PiDiscovery(private val context: Context, private val log: (String) -> Unit) {
    data class Endpoint(val host: String, val port: Int, val serviceName: String)

    suspend fun first(timeoutMs: Long = 25_000, waiting: (String) -> Unit = {}): Endpoint = withTimeout(timeoutMs) {
        withContext(Dispatchers.IO) {
            var lastWait: String? = null
            var readyBridge: Pair<NetworkInterface, Inet4Address>? = null
            while (readyBridge == null) {
                coroutineContext.ensureActive()
                val candidate = Collections.list(NetworkInterface.getNetworkInterfaces())
                    .firstOrNull { it.name.startsWith("ap_br_") && it.isUp && it.supportsMulticast() }
                val address = candidate?.interfaceAddresses?.mapNotNull { it.address as? Inet4Address }
                    ?.firstOrNull { !it.isLoopbackAddress }
                if (candidate != null && address != null) {
                    readyBridge = candidate to address
                    break
                }
                val reason = if (candidate == null) "Waiting for vehicle hotspot bridge" else "Waiting for ${candidate.name} IPv4 address"
                if (reason != lastWait) {
                    log(reason)
                    waiting(reason)
                    lastWait = reason
                }
                delay(500)
            }
            val (bridge, bridgeIpv4) = checkNotNull(readyBridge)
            waiting("Finding Pi CarPlay proxy")

            val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
            val lock = wifi?.createMulticastLock("carlink-pi-bridge-discovery")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            try {
                log("bridge mDNS browse starting interface=${bridge.name} address=${bridgeIpv4.hostAddress}")
                MulticastSocket(null).use { socket ->
                    socket.reuseAddress = true
                    socket.bind(InetSocketAddress(MDNS_PORT))
                    socket.soTimeout = SOCKET_TIMEOUT_MS
                    socket.networkInterface = bridge
                    socket.joinGroup(InetSocketAddress(MDNS_GROUP, MDNS_PORT), bridge)
                    val query = queryPacket()
                    val target = InetSocketAddress(MDNS_GROUP, MDNS_PORT)
                    val buffer = ByteArray(9000)
                    var lastQuery = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val now = System.currentTimeMillis()
                        if (now - lastQuery >= QUERY_INTERVAL_MS) {
                            socket.send(java.net.DatagramPacket(query, query.size, target))
                            lastQuery = now
                            log("bridge mDNS query sent interface=${bridge.name} type=$SERVICE_TYPE")
                        }
                        try {
                            val packet = java.net.DatagramPacket(buffer, buffer.size)
                            socket.receive(packet)
                            parseResponse(packet.data, packet.length)?.let { endpoint ->
                                log("bridge mDNS resolved name=${endpoint.serviceName} address=${endpoint.host}:${endpoint.port} interface=${bridge.name}")
                                return@withContext endpoint
                            }
                        } catch (_: SocketTimeoutException) {
                            // Re-query periodically while honoring the caller's timeout/cancellation.
                        }
                    }
                    @Suppress("UNREACHABLE_CODE")
                    error("unreachable")
                }
            } finally {
                runCatching { if (lock?.isHeld == true) lock.release() }
            }
        }
    }

    private data class DnsRecord(val name: String, val type: Int, val data: ByteArray, val dataOffset: Int)

    private fun parseResponse(bytes: ByteArray, length: Int): Endpoint? {
        if (length < 12) return null
        val answerCount = u16(bytes, 6)
        val authorityCount = u16(bytes, 8)
        val additionalCount = u16(bytes, 10)
        var offset = 12
        val questionCount = u16(bytes, 4)
        repeat(questionCount) {
            offset = readName(bytes, offset).second + 4
            if (offset > length) return null
        }
        val records = mutableListOf<DnsRecord>()
        repeat(answerCount + authorityCount + additionalCount) {
            if (offset >= length) return null
            val (name, afterName) = readName(bytes, offset)
            if (afterName + 10 > length) return null
            val type = u16(bytes, afterName)
            val dataLength = u16(bytes, afterName + 8)
            val dataOffset = afterName + 10
            if (dataOffset + dataLength > length) return null
            records += DnsRecord(name, type, bytes.copyOfRange(dataOffset, dataOffset + dataLength), dataOffset)
            offset = dataOffset + dataLength
        }

        val ptr = records.firstOrNull { it.type == TYPE_PTR && it.name.equals(SERVICE_FQDN, true) } ?: return null
        val instance = readName(bytes, ptr.dataOffset).first
        val srv = records.firstOrNull { it.type == TYPE_SRV && it.name.equals(instance, true) } ?: return null
        if (srv.data.size < 7) return null
        val port = u16(srv.data, 4)
        val hostName = readName(bytes, srv.dataOffset + 6).first
        val host = records.firstOrNull { it.type == TYPE_A && it.name.equals(hostName, true) && it.data.size == 4 }
            ?.data?.let { InetAddress.getByAddress(it) as? Inet4Address } ?: return null
        if (port !in 1..65535) return null
        return Endpoint(host.hostAddress, port, instance.removeSuffix("."))
    }

    private fun queryPacket(): ByteArray {
        val labels = SERVICE_FQDN.trimEnd('.').split('.')
        val name = buildList<Byte> {
            labels.forEach { label ->
                val bytes = label.toByteArray(Charsets.US_ASCII)
                add(bytes.size.toByte())
                bytes.forEach(::add)
            }
            add(0)
        }.toByteArray()
        val packet = ByteArray(12 + name.size + 4)
        packet[5] = 1 // QDCOUNT
        name.copyInto(packet, 12)
        val qtypeOffset = 12 + name.size
        packet[qtypeOffset] = 0
        packet[qtypeOffset + 1] = TYPE_PTR.toByte()
        packet[qtypeOffset + 2] = 0
        packet[qtypeOffset + 3] = CLASS_IN.toByte() // unicast response is fine; multicast answers also accepted
        return packet
    }

    /** DNS name decoder supporting compression pointers; returns the name and next wire offset. */
    private fun readName(bytes: ByteArray, start: Int): Pair<String, Int> {
        val labels = mutableListOf<String>()
        var cursor = start
        var next = -1
        var jumps = 0
        while (cursor < bytes.size) {
            val size = bytes[cursor].toInt() and 0xff
            if (size == 0) {
                if (next < 0) next = cursor + 1
                break
            }
            if ((size and 0xc0) == 0xc0) {
                if (cursor + 1 >= bytes.size || jumps++ > 24) return "" to bytes.size
                val pointer = ((size and 0x3f) shl 8) or (bytes[cursor + 1].toInt() and 0xff)
                if (next < 0) next = cursor + 2
                cursor = pointer
                continue
            }
            if (size > 63 || cursor + 1 + size > bytes.size) return "" to bytes.size
            labels += String(bytes, cursor + 1, size, Charsets.US_ASCII)
            cursor += size + 1
        }
        return labels.joinToString(".", postfix = ".") to if (next >= 0) next else bytes.size
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private companion object {
        const val MDNS_PORT = 5353
        const val MDNS_GROUP_TEXT = "224.0.0.251"
        val MDNS_GROUP: InetAddress = InetAddress.getByName(MDNS_GROUP_TEXT)
        const val SERVICE_TYPE = "_carlink-pi._tcp"
        const val SERVICE_FQDN = "_carlink-pi._tcp.local."
        const val TYPE_PTR = 12
        const val TYPE_SRV = 33
        const val TYPE_A = 1
        const val CLASS_IN = 1
        const val SOCKET_TIMEOUT_MS = 500
        const val QUERY_INTERVAL_MS = 1_000L
    }
}
