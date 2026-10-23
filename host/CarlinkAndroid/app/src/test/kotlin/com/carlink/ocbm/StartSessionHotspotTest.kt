package com.carlink.ocbm

import java.net.InetAddress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class StartSessionHotspotTest {
    private fun iface(name: String = "br0", up: Boolean = true, vararg addresses: String) =
        HotspotInterface(name, up, addresses.map { InetAddress.getByName(it) })

    @Test fun ipv6_only_bridge_is_ready_and_non_bridge_networks_are_not_candidates() {
        val bridge = iface(addresses = arrayOf("fe80::1"))
        val selected = StartSessionHotspot.select(listOf(iface("tun0", true, "192.168.2.1"), bridge))
        assertEquals("br0", selected.name)
        assertEquals(1, selected.usableAddresses.size)
    }

    @Test fun down_preferred_bridge_waits_instead_of_selecting_another_bridge() {
        try {
            StartSessionHotspot.select(listOf(iface(up = false, addresses = arrayOf("192.168.5.1")), iface("br1", true, "10.0.0.1")))
            fail("br0 is still recovering")
        } catch (e: HotspotNotReady) { assertTrue(e.message.orEmpty().contains("br0[up=false")) }
        try {
            StartSessionHotspot.select(listOf(iface("br1", true, "10.0.0.1"), iface("br2", true, "10.1.0.1")))
            fail("ambiguous bridges must not be guessed")
        } catch (_: HotspotNotReady) { }
    }

    @Test fun missing_or_addressless_bridge_recovers_without_a_slot_failure() = runBlocking {
        var polls = 0
        val waiting = mutableListOf<String>()
        val selected = awaitHotspotReady(
            isCurrent = { true },
            read = {
                val interfaces = when (polls++) {
                    0 -> emptyList()
                    1 -> listOf(iface())
                    else -> listOf(iface(addresses = arrayOf("fe80::1", "192.168.5.1")))
                }
                StartSessionHotspot.select(interfaces)
            },
            waiting = { waiting.add(it) },
            pause = {},
        )
        assertEquals(3, polls)
        assertEquals(2, waiting.size)
        assertEquals(2, selected.usableAddresses.size)
    }

    @Test fun stopped_or_replaced_attempt_cannot_resume_when_bridge_returns() = runBlocking {
        var current = true
        var reads = 0
        try {
            awaitHotspotReady(
                isCurrent = { current },
                read = { reads++; throw HotspotNotReady("down") },
                waiting = {},
                pause = { current = false },
            )
            fail("stale owner should cancel")
        } catch (_: CancellationException) { assertEquals(1, reads) }
    }

    @Test fun credential_failure_then_bridge_failure_waits_until_both_recover() = runBlocking {
        var polls = 0
        val waiting = mutableListOf<String>()
        val recovered = mutableListOf<String>()
        val selected = awaitHotspotReady(
            isCurrent = { true },
            read = {
                when (polls++) {
                    0, 1 -> throw HotspotNotReady("Saved hotspot password temporarily unreadable")
                    2 -> throw HotspotNotReady("Hotspot bridge not ready")
                    else -> "br0"
                }
            },
            waiting = { waiting.add(it) },
            pause = {},
            recovered = { recovered.add(it) },
        )
        assertEquals("br0", selected)
        assertEquals(4, polls)
        assertEquals(2, waiting.size) // Repeated failures do not flood diagnostics.
        assertEquals(listOf("br0"), recovered)
    }

    @Test fun owner_replaced_during_successful_read_does_not_report_recovery() = runBlocking {
        var current = true
        var reads = 0
        var recovered = false
        try {
            awaitHotspotReady(
                isCurrent = { current },
                read = {
                    if (reads++ == 0) throw HotspotNotReady("password temporarily unreadable")
                    current = false
                    "br0"
                },
                waiting = {},
                pause = {},
                recovered = { recovered = true },
            )
            fail("An obsolete read must not resume connection preparation")
        } catch (_: CancellationException) {
            assertFalse(recovered)
        }
    }
}
