package com.carlink.diagnostics

import java.net.InetSocketAddress
import java.net.Socket

/** A small outbound probe, independent of the receiver and phone session state. */
internal fun outboundTcpProbe(host: String, port: Int): String {
    val octets = host.split('.')
    if (octets.size != 4 || octets.any { part ->
            part.isEmpty() || part.any { !it.isDigit() } || part.toIntOrNull() !in 0..255
        }) return "FAIL: enter a numeric IPv4 address"
    if (port !in 1..65535) return "FAIL: port must be between 1 and 65535"

    val started = System.nanoTime()
    return try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), 3000)
            socket.getOutputStream().write("CARLINK_TCP_PROBE\n".toByteArray(Charsets.US_ASCII))
            socket.getOutputStream().flush()
            "PASS connected and marker written local=${socket.localAddress.hostAddress}:${socket.localPort} " +
                "remote=$host:$port elapsed_ms=${(System.nanoTime() - started) / 1_000_000}; " +
                "confirm CARLINK_TCP_PROBE appeared on the receiving computer"
        }
    } catch (error: Exception) {
        "FAIL remote=$host:$port elapsed_ms=${(System.nanoTime() - started) / 1_000_000} " +
            "error=${error.javaClass.simpleName}: ${error.message}"
    }
}
