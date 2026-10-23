package zeno.gmccpa

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayPeerSelectionTest {
    @Test
    fun `inbound control must come from the selected phones resolved endpoint`() {
        assertTrue(
            isExpectedInboundPeer(
                "192.168.43.17",
                setOf("192.168.43.17", "fe80::1234%wlan0"),
            ),
        )
        assertTrue(
            isExpectedInboundPeer(
                "fe80::1234%br0",
                setOf("fe80::1234%wlan0"),
            ),
        )
        assertFalse(isExpectedInboundPeer("192.168.43.99", setOf("192.168.43.17")))
        assertFalse(isExpectedInboundPeer("192.168.43.17", emptySet()))
    }

    @Test
    fun `does not dial before adapter handoff`() {
        assertFalse(
            isAllowedCarPlayCandidate(
                handoffReady = false,
                activeBluetoothPeer = "02:00:00:AA:BB:01",
                serviceName = "Test Phone A",
                advertisedBluetoothMac = "02:00:00:AA:BB:01",
                // An alternate phone may already be visible on Bonjour. It must not be selected
                // while the configured target is still waiting for its own iAP2 handoff.
                discoveredServiceNames = setOf("Test Phone B", "Test Phone A"),
            ),
        )
    }

    @Test
    fun `once selected phone is ready alternate Bonjour peer remains excluded`() {
        assertFalse(
            isAllowedCarPlayCandidate(
                handoffReady = true,
                activeBluetoothPeer = "02:00:00:AA:BB:02",
                serviceName = "Test Phone A",
                advertisedBluetoothMac = "02:00:00:AA:BB:01",
                discoveredServiceNames = setOf("Test Phone B", "Test Phone A"),
            ),
        )
        assertTrue(
            isAllowedCarPlayCandidate(
                handoffReady = true,
                activeBluetoothPeer = "02:00:00:AA:BB:02",
                serviceName = "Test Phone B",
                advertisedBluetoothMac = "02:00:00:AA:BB:02",
                discoveredServiceNames = setOf("Test Phone B", "Test Phone A"),
            ),
        )
    }

    @Test
    fun `pins to exact adapter-selected phone among multiple advertisements`() {
        assertTrue(
            isAllowedCarPlayCandidate(
                handoffReady = true,
                activeBluetoothPeer = "02:00:00:AA:BB:01",
                serviceName = "Test Phone B",
                advertisedBluetoothMac = "02:00:00:AA:BB:01",
                discoveredServiceNames = setOf("Test Phone B", "Test Phone A"),
            ),
        )
        assertFalse(
            isAllowedCarPlayCandidate(
                handoffReady = true,
                activeBluetoothPeer = "02:00:00:AA:BB:01",
                serviceName = "Test Phone A",
                advertisedBluetoothMac = "AA:BB:CC:DD:EE:FF",
                discoveredServiceNames = setOf("Test Phone B", "Test Phone A"),
            ),
        )
    }

    @Test
    fun `without adapter identity only allows one unambiguous candidate`() {
        assertTrue(
            isAllowedCarPlayCandidate(
                handoffReady = true,
                activeBluetoothPeer = null,
                serviceName = "Test Phone B",
                advertisedBluetoothMac = "",
                discoveredServiceNames = setOf("Test Phone B"),
            ),
        )
        assertFalse(
            isAllowedCarPlayCandidate(
                handoffReady = true,
                activeBluetoothPeer = null,
                serviceName = "Test Phone B",
                advertisedBluetoothMac = "",
                discoveredServiceNames = setOf("Test Phone B", "Test Phone A"),
            ),
        )
    }
}
