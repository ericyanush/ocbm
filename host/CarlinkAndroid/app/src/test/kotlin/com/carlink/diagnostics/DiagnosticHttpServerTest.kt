package com.carlink.diagnostics

import java.net.Socket
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.*
import org.junit.Test

class DiagnosticHttpServerTest {
    @Test fun http_client_receives_success_marker_and_connection_is_logged() {
        val events = CopyOnWriteArrayList<String>()
        DiagnosticHttpServer(port = 0, bindAddress = InetAddress.getByName("127.0.0.1"), record = { events.add(it) }).use { server ->
            assertTrue(server.localEndpoint.contains("127.0.0.1"))
            Socket("127.0.0.1", server.localPort).use { client ->
                client.soTimeout = 3000
                client.getOutputStream().write("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
                client.getOutputStream().flush()
                val response = client.getInputStream().bufferedReader().readText()
                assertTrue(response.startsWith("HTTP/1.1 200 OK\r\n"))
                assertTrue(response.endsWith("CARLINK_HOTSPOT_TEST_OK\n"))
                assertTrue(events.any { it.startsWith("INBOUND local=") })
            }
        }
    }
}
