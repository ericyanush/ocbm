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
            assertTrue(states.last().contains("rejected"))
            assertTrue(controller.progressText.orEmpty().contains("rejected"))
            controller.cancel(clearSlots = false)
            assertTrue(controller.progressText.orEmpty().contains("rejected"))
            controller.cancel()
            assertNull(controller.progressText)
            assertNull(controller.event(event("available", true).put("version", 2)))
        } finally { scope.cancel() }
    }

    @Test fun stale_slot_events_cannot_remove_or_fail_a_new_phone_connection() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val states = mutableListOf<String>()
        val controller = NativeStartSession(scope, { null }, { true }, { null }, { states.add(it) }, {})
        controller.enabled = true
        fun slot(id: String, started: Long, stage: String) = event(stage, stage == "available")
            .put("slotId", id).put("slotStartedAt", started)
        try {
            assertEquals(peer to true, controller.event(slot("old", 10, "available")))
            // The file poll may first observe available, with connected already replaced on disk.
            assertEquals(peer to true, controller.event(slot("new", 20, "available")))
            for (stage in listOf("ended", "failed", "unsupported", "connected", "available")) {
                assertNull(controller.event(slot("old", 10, stage)))
            }
            assertTrue(states.isEmpty())
            assertEquals(peer to true, controller.event(slot("new", 20, "available")))
            assertEquals(peer to false, controller.event(slot("new", 20, "ended")))
            assertNull(controller.event(slot("new", 20, "available"))) // No resurrection.
            assertEquals(peer to true, controller.event(slot("reconnect", 30, "available")))
        } finally { scope.cancel() }
    }

    @Test fun failure_then_termination_does_not_poison_the_next_bluetooth_slot() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = NativeStartSession(scope, { null }, { true }, { null }, {}, {})
        controller.enabled = true
        try {
            controller.event(event("available", true))
            controller.event(event("failed", false).put("reason", "write failed"))
            assertEquals(peer to false, controller.event(event("ended", false).put("failureReason", "write failed")))
            assertNull(controller.event(event("available", true)))
            assertEquals(peer to false, controller.event(event("connected", false).put("slotId", "replacement")))
            assertEquals(peer to true, controller.event(event("available", true).put("slotId", "replacement")))
        } finally { scope.cancel() }
    }

    @Test fun obsolete_request_response_cannot_downgrade_current_slot_readiness() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val states = mutableListOf<String>()
        val controller = NativeStartSession(scope, { null }, { true }, { null }, { states.add(it) }, {})
        controller.enabled = true
        try {
            controller.event(event("available", true))
            for (stage in listOf("sent", "failed", "cancelled")) {
                assertNull(controller.event(event(stage, false).put("requestId", "obsolete-request")))
            }
            assertTrue(states.isEmpty())
            assertEquals(peer to true, controller.event(event("available", true)))
        } finally { scope.cancel() }
    }
}
