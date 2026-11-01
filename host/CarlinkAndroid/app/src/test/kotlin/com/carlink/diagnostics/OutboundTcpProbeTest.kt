package com.carlink.diagnostics

import java.net.InetAddress
import java.net.ServerSocket
import org.junit.Assert.*
import org.junit.Test

class OutboundTcpProbeTest {
    @Test fun remote_listener_receives_marker_and_connection_closes() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 3000
            assertTrue(outboundTcpProbe("127.0.0.1", server.localPort).startsWith("PASS"))
            server.accept().use { client ->
                client.soTimeout = 3000
                assertEquals("CARLINK_TCP_PROBE\n", client.getInputStream().bufferedReader().readText())
            }
        }
    }
}
