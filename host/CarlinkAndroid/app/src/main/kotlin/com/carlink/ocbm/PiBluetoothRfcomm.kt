package com.carlink.ocbm

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import org.json.JSONObject

/** Separate outbound control socket; media frames cannot block RFCOMM bytes. */
class PiBluetoothRfcomm internal constructor(private val endpoint: PiDiscovery.Endpoint) {
    data class Status(val address: String, val name: String, val pairedPhones: List<Pair<String, String>>, val connectedPhones: Set<String>)
    data class Link(val accessoryAddress: String, private val socket: Socket) : AppBluetoothRfcomm.ByteLink {
        override val input get() = socket.getInputStream()
        override val output get() = socket.getOutputStream()
        override fun close() = socket.close()
    }

    fun status(): Status = connect().use { socket ->
        socket.getOutputStream().write("PI_STATUS/2\n".toByteArray(StandardCharsets.US_ASCII))
        val line = readLine(socket)
        if (!line.startsWith("READY ")) throw IOException("Pi Bluetooth status rejected: ${line.take(80)}")
        val json = JSONObject(line.substring(6))
        val peers = json.getJSONArray("paired")
        Status(json.getString("bt_mac"), json.optString("name", "Carlink-Pi"), List(peers.length()) { i ->
            val item = peers.getJSONObject(i)
            item.getString("address") to item.getString("name")
        }, (0 until peers.length()).mapNotNull { i ->
            peers.getJSONObject(i).takeIf { it.optBoolean("connected") }?.getString("address")
        }.toSet())
    }

    fun setName(name: String): String = control("set_name", "name", name).getString("name")

    fun removePairing(phone: String) { control("remove_pairing", "address", phone) }
    fun disconnectPhone(phone: String) { control("disconnect_phone", "address", phone) }

    private fun control(action: String, key: String, value: String): JSONObject = connect().use { socket ->
        socket.getOutputStream().write("PI_CTRL/2\n".toByteArray(StandardCharsets.US_ASCII))
        socket.getOutputStream().write((JSONObject().put("action", action).put(key, value).toString() + "\n")
            .toByteArray(StandardCharsets.UTF_8))
        val line = readLine(socket)
        if (!line.startsWith("READY ")) throw IOException("Pi control rejected: ${line.take(120)}")
        JSONObject(line.substring(6))
    }

    fun dial(phone: String, onSocketCreated: (Socket) -> Unit = {}): Link {
        require(phone.matches(Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")))
        val socket = connect()
        try {
            onSocketCreated(socket)
            socket.getOutputStream().write("PI_RFCOMM/2 $phone\n".toByteArray(StandardCharsets.US_ASCII))
            val line = readLine(socket)
            if (!line.startsWith("READY ")) throw IOException("Pi RFCOMM dial rejected: ${line.take(400)}")
            val address = line.substring(6).trim()
            if (!address.matches(Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}"))) {
                throw IOException("Pi returned invalid Bluetooth address")
            }
            socket.soTimeout = 0
            return Link(address, socket)
        } catch (error: Throwable) {
            socket.close()
            throw error
        }
    }

    private fun connect(): Socket {
        return Socket().apply {
            tcpNoDelay = true
            keepAlive = true
            connect(InetSocketAddress(endpoint.host, endpoint.port), 5_000)
            soTimeout = 40_000 // Pi may need both SDP and RFCOMM dial time.
        }
    }

    private fun readLine(socket: Socket): String {
        val bytes = ArrayList<Byte>()
        while (bytes.size < 2048) {
            val value = socket.getInputStream().read()
            if (value < 0) throw IOException("Pi closed during Bluetooth handshake")
            if (value == 10) return String(bytes.toByteArray(), StandardCharsets.UTF_8)
            bytes.add(value.toByte())
        }
        throw IOException("Oversized Pi Bluetooth handshake")
    }
}
