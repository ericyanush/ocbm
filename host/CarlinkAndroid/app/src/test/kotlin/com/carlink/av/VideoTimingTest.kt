package com.carlink.av

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoTimingTest {
    @Test
    fun separatesArrivalDecodePresentationAndCallbackDelay() {
        var now = 1_000_000_000L
        val timing = VideoTiming { now }
        timing.arrived(now, 100)
        now += 1_000_000
        timing.queued(10, 1_000_000_000L)
        now += 2_000_000
        timing.released(10)
        now += 8_000_000
        timing.rendered(10, now - 3_000_000)
        assertNull(timing.report())
        now += 5_000_000_000L
        val report = requireNotNull(timing.report())
        assertTrue(report.contains("maxDecodeUs=2000"))
        assertTrue(report.contains("maxPresentationUs=5000"))
        assertTrue(report.contains("maxArrivalToRenderUs=8000"))
        assertTrue(report.contains("maxCallbackLagUs=3000"))
        assertTrue(report.contains("inflight=0"))
    }

    @Test
    fun boundsFramesAndResetsSessionState() {
        var now = 1_000_000_000L
        val timing = VideoTiming { now }
        repeat(300) { timing.queued(it.toLong(), now) }
        timing.rendered(0, now)
        now += 6_000_000_000L
        val report = requireNotNull(timing.report())
        assertTrue(report.contains("inflight=256"))
        assertTrue(report.contains("evicted=44"))
        assertTrue(report.contains("unmatched=1"))
        timing.reset()
        now += 6_000_000_000L
        val clean = requireNotNull(timing.report())
        assertTrue(clean.contains("inflight=0"))
        assertTrue(clean.contains("evicted=0"))
        assertTrue(clean.contains("unmatched=0"))
    }
}
