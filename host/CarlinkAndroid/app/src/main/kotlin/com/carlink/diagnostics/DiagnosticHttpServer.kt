package com.carlink.diagnostics

import java.io.Closeable
import java.net.InetSocketAddress
import java.net.InetAddress
import java.net.ServerSocket

/** Isolated diagnostic listener; it never touches a CarPlay receiver or phone session. */
internal class DiagnosticHttpServer(
    port: Int = 18081,
    bindAddress: InetAddress? = null,
    private val record: (String) -> Unit,
) : Closeable {
    private val server = ServerSocket().apply {
        try {
            reuseAddress = true
            bind(InetSocketAddress(bindAddress, port))
        } catch (error: Exception) {
            close()
            throw error
        }
    }
    val localPort: Int get() = server.localPort
    val localEndpoint: String get() = server.localSocketAddress.toString()

    init {
        Thread({
            while (!server.isClosed) {
                try {
                    server.accept().use { client ->
                        record("INBOUND local=${client.localAddress.hostAddress}:${client.localPort} peer=${client.inetAddress.hostAddress}:${client.port}")
                        client.soTimeout = 1000
                        // Accept both a raw nc connection and a normal HTTP request, bounded in size/time.
                        runCatching {
                            val input = client.getInputStream()
                            var tail = 0
                            val deadline = System.nanoTime() + 1_000_000_000L
                            for (i in 0 until 4096) {
                                if (System.nanoTime() > deadline) break
                                val byte = input.read()
                                if (byte < 0) break
                                tail = (tail shl 8) or byte
                                if (tail == 0x0d0a0d0a) break
                            }
                        }
                        val body = "CARLINK_HOTSPOT_TEST_OK\n"
                        val response = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n" +
                            "Content-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
                        runCatching {
                            client.getOutputStream().write(response.toByteArray(Charsets.US_ASCII))
                            client.getOutputStream().flush()
                        }.onFailure { record("response write failed: ${it.javaClass.simpleName}") }
                    }
                } catch (error: Exception) {
                    if (!server.isClosed) record("listener error: ${error.javaClass.simpleName}: ${error.message}")
                    runCatching { server.close() }
                    break
                }
            }
        }, "hotspot-diagnostic-http").apply { isDaemon = true; start() }
    }

    override fun close() { server.close() }
}
