package com.carlink.ocbm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeStartSessionTest {
    private val peer = "02:00:00:AA:BB:01"
    private fun event(stage: String, available: Boolean) = JSONObject()
        .put("version", 1).put("peerMac", peer).put("slotId", "slot-1")
        .put("requestId", "").put("receiverEpoch", "").put("stage", stage)
        .put("reason", "").put("available", available)

    @Test fun readiness_requires_authoritative_available_event_and_preserves_failure() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sent = mutableListOf<JSONObject>()
        val states = mutableListOf<String>()
        val controller = NativeStartSession(scope, { null }, { sent.add(it); true }, { null }, { states.add(it) }, {})
        try {
            assertNull(controller.event(event("available", true))) // Default is legacy/off.
            controller.enabled = true
            assertEquals(peer to false, controller.event(event("identified", false)))
            controller.select(peer)
            assertTrue(sent.isEmpty()) // Identification alone never sends a bootstrap.
            assertEquals(peer to true, controller.event(event("available", true)))
            assertNull(controller.event(event("queued", false))) // Queue ACK cannot erase readiness.
            assertEquals(peer to false, controller.event(event("unsupported", true).put("reason", "rejected")))
            controller.select(peer)
            assertTrue(sent.isEmpty()) // No implicit downgrade or retry of a rejected slot.
            assertTrue(states.single().contains("rejected"))
            assertTrue(controller.progressText.orEmpty().contains("rejected"))
            controller.cancel(clearSlots = false)
            assertTrue(controller.progressText.orEmpty().contains("rejected"))
            controller.cancel()
            assertNull(controller.progressText)
            assertNull(controller.event(event("available", true).put("version", 2)))
        } finally { scope.cancel() }
    }
}
