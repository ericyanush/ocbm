package com.carlink.diagnostics

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/** Network-only Pi proxy proof: AAOS opens the persistent connection, Pi relays phone HTTP to us. */
internal class PiProxyPoc(private val report: (String) -> Unit) : Closeable {
    private val running = AtomicBoolean(false)
    @Volatile private var socket: Socket? = null
    @Volatile private var worker: Thread? = null

    fun start(host: String, port: Int, token: String) {
        require(!running.get()) { "test already running" }
        require(host.matches(Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")) &&
            host.split('.').all { it.toInt() in 0..255 }) { "enter the Pi's vehicle-hotspot IPv4 address" }
        require(port in 1..65535) { "port must be 1–65535" }
        require(token.isNotEmpty() && token.length <= 128 && token.all { it.code in 33..126 }) {
            "enter the Pi test token (printable ASCII, no spaces)"
        }
        running.set(true)
        worker = Thread({ run(host, port, token) }, "pi-proxy-poc").apply { isDaemon = true; start() }
    }

    private fun run(host: String, port: Int, token: String) {
        try {
            Socket().use { connection ->
                socket = connection
                connection.connect(InetSocketAddress(host, port), 5_000)
                connection.soTimeout = 5_000
                val input = DataInputStream(connection.getInputStream())
                val output = DataOutputStream(connection.getOutputStream())
                output.write("PI_PROXY_POC/1 $token\n".toByteArray(StandardCharsets.US_ASCII))
                output.flush()
                val ready = ByteArray(6)
                input.readFully(ready)
                require(ready.contentEquals("READY\n".toByteArray(StandardCharsets.US_ASCII))) {
                    "Pi rejected handshake"
                }
                connection.soTimeout = 0 // Closing the socket stops a blocked read without losing frame alignment.
                report("connected local=${connection.localAddress.hostAddress}:${connection.localPort} " +
                    "remote=$host:$port; waiting for phone HTTP request")
                while (running.get()) {
                    val size = input.readInt()
                    if (size == 0) {
                        output.writeInt(0)
                        output.flush()
                        continue
                    }
                    require(size in 1..8192) { "invalid request length=$size" }
                    val request = ByteArray(size)
                    input.readFully(request)
                    val firstLine = String(request, StandardCharsets.US_ASCII).lineSequence().firstOrNull().orEmpty().trim()
                    val body = "Carlink AAOS Pi proxy PoC reached the app. Request: $firstLine\n"
                    val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
                    val response = ("HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\n" +
                        "Content-Length: ${bodyBytes.size}\r\nConnection: close\r\n\r\n")
                        .toByteArray(StandardCharsets.US_ASCII) + bodyBytes
                    output.writeInt(response.size)
                    output.write(response)
                    output.flush()
                    report("phone request relayed requestBytes=$size responseBytes=${response.size} path=$firstLine")
                }
            }
        } catch (error: Exception) {
            if (running.get()) report("FAIL ${error.javaClass.simpleName}: ${error.message}")
        } finally {
            socket = null
            running.set(false)
            report("stopped")
        }
    }

    override fun close() {
        running.set(false)
        runCatching { socket?.close() }
        worker?.interrupt()
    }
}
