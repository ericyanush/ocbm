package com.carlink.ocbm

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/** One live Pi broker link. The receiver remains the sole TCP/UDP protocol endpoint on AAOS. */
internal class PiCarPlayLink(
    private val log: (String) -> Unit,
    private val onLost: () -> Unit,
) : Closeable {
    private val open = AtomicBoolean(false)
    private val sendLock = Any()
    private val socket = Socket()
    private lateinit var input: DataInputStream
    private lateinit var output: DataOutputStream
    private var reader: Thread? = null
    lateinit var tunnel: OcbmSocketTunnel
        private set
    lateinit var ap: Ap
        private set
    lateinit var endpoint: PiDiscovery.Endpoint
        private set

    data class Ap(val address: Inet4Address, val ssid: String, val passphrase: String, val channel: Int)

    fun connect(discovered: PiDiscovery.Endpoint, controlPort: Int) {
        check(!open.get())
        endpoint = discovered
        socket.tcpNoDelay = true
        socket.keepAlive = true
        socket.connect(InetSocketAddress(discovered.host, discovered.port), 5000)
        socket.soTimeout = 10000
        input = DataInputStream(socket.getInputStream())
        output = DataOutputStream(socket.getOutputStream())
        output.write("PI_CARPLAY/2\n".toByteArray(StandardCharsets.US_ASCII))
        output.flush()
        val line = StringBuilder()
        while (line.length < 2048) {
            val byte = input.read()
            if (byte < 0) throw IOException("Pi closed during handshake")
            if (byte == 10) break
            line.append(byte.toChar())
        }
        if (!line.startsWith("READY ")) {
            throw IOException(if (line.startsWith("ERROR ")) "Pi CarPlay broker: ${line.drop(6).take(120)}"
                else "Pi rejected CarPlay tunnel handshake")
        }
        val details = JSONObject(line.substring(6))
        val address = InetAddress.getByName(details.getString("ap_ip")) as? Inet4Address
            ?: error("Pi AP has no IPv4 address")
        val ssid = details.getString("ssid")
        val passphrase = details.getString("passphrase")
        val channel = details.getInt("channel")
        require(ssid.isNotBlank() && passphrase.length in 8..63 && channel in 1..196) {
            "Pi AP configuration is incomplete"
        }
        ap = Ap(address, ssid, passphrase, channel)
        socket.soTimeout = 20_000 // A silent vehicle-network loss must retire the receiver promptly.
        open.set(true)
        tunnel = OcbmSocketTunnel(::send, address, controlPort = controlPort, log = log)
        tunnel.startExternal()
        reader = Thread(::readLoop, "pi-carplay-link").apply { isDaemon = true; start() }
        Thread({
            while (open.get()) {
                try { Thread.sleep(5_000) } catch (_: InterruptedException) { return@Thread }
                if (!open.get()) return@Thread
                val ageMs = tunnel.piHeartbeatAgeMs()
                if (ageMs >= 17_000 || !tunnel.pingExternal()) {
                    log("Pi broker heartbeat lost ageMs=$ageMs; closing link")
                    runCatching { socket.close() }
                    return@Thread
                }
            }
        }, "pi-carplay-heartbeat").apply { isDaemon = true; start() }
        log("link ready remote=${discovered.host}:${discovered.port} ap=${address.hostAddress} ssid=$ssid channel=$channel")
    }

    private fun send(frame: ByteArray): Boolean = runCatching {
        require(frame.size in 9..65535)
        synchronized(sendLock) {
            check(open.get()) { "Pi link closed" }
            output.writeInt(frame.size)
            output.write(frame)
            output.flush()
        }
    }.onFailure {
        if (open.get()) runCatching { socket.close() } // Wake the reader so the manager can reconnect.
    }.isSuccess

    private fun readLoop() {
        try {
            while (open.get()) {
                val length = input.readInt()
                if (length !in 9..65535) throw IOException("invalid Pi broker frame length=$length")
                val frame = ByteArray(length)
                input.readFully(frame)
                tunnel.offerExternalFrame(frame)
            }
        } catch (error: Exception) {
            if (open.get()) log("link lost: ${error.javaClass.simpleName}: ${error.message}")
        } finally {
            val wasOpen = open.get()
            close()
            if (wasOpen) onLost()
        }
    }

    override fun close() {
        if (!open.compareAndSet(true, false)) {
            runCatching { socket.close() }
            return
        }
        runCatching { tunnel.close() }
        runCatching { socket.close() }
    }
}
