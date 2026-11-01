package com.carlink.ocbm

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import org.junit.Assert.*
import org.junit.Test
import zeno.gmccpa.ReceiverTcpDiagnostics

class ReceiverTcpDiagnosticsTest {
    @Test fun successful_local_probe_is_identified_before_phone_session_handling() {
        val loopback = InetAddress.getByName("127.0.0.1")
        ServerSocket(0, 1, loopback).use { server ->
            server.soTimeout = 3000
            assertTrue(ReceiverTcpDiagnostics.connect(loopback, server.localPort).startsWith("PASS"))
            server.accept().use { accepted ->
                assertTrue(ReceiverTcpDiagnostics.consume(accepted))
                assertFalse(ReceiverTcpDiagnostics.consume(accepted))
            }
        }
    }

    @Test fun ordinary_connection_is_not_classified_as_diagnostic() {
        val loopback = InetAddress.getByName("127.0.0.1")
        ServerSocket(0, 1, loopback).use { server ->
            server.soTimeout = 3000
            Socket(loopback, server.localPort).use {
                server.accept().use { accepted -> assertFalse(ReceiverTcpDiagnostics.consume(accepted)) }
            }
        }
    }
}
