package com.carlink.ocbm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneFallbackPolicyTest {
    private val preferred = "02:00:00:AA:BB:01"
    private val secondary = "02:00:00:AA:BB:02"
    private val readySlots = mapOf(
        preferred to Ocbm.BTP_WIFI_HANDOFF,
        secondary to Ocbm.BTP_WIFI_HANDOFF,
    )

    private fun candidate(
        timedOutMac: String = preferred,
        currentTargetMac: String? = preferred,
        slots: Map<String, Byte> = readySlots,
        sessionLive: Boolean = false,
        explicitTargetMac: String? = null,
        switchPending: Boolean = false,
    ) = readyAlternateAfterDialBackTimeout(
        preferred, timedOutMac, currentTargetMac, slots,
        sessionLive, explicitTargetMac, switchPending,
    )

    @Test fun ready_preferred_that_fails_to_dial_back_yields_to_ready_secondary() {
        assertEquals(secondary, candidate())
    }

    @Test fun timeout_cannot_preempt_live_or_explicitly_selected_phone() {
        assertNull(candidate(sessionLive = true))
        assertNull(candidate(explicitTargetMac = secondary))
        assertNull(candidate(switchPending = true))
        assertNull(candidate(timedOutMac = secondary))
        assertNull(candidate(currentTargetMac = secondary))
    }

    @Test fun alternate_must_still_have_a_ready_iap2_slot() {
        assertNull(candidate(slots = mapOf(preferred to Ocbm.BTP_WIFI_HANDOFF)))
        assertNull(candidate(slots = mapOf(preferred to Ocbm.BTP_WIFI_HANDOFF, secondary to Ocbm.BTP_IDENTIFIED)))
    }
}
