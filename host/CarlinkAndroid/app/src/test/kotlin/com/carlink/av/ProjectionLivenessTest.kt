package com.carlink.av

import org.junit.Assert.*
import org.junit.Test

class ProjectionLivenessTest {
    @Test fun frozen_frame_requires_an_unanswered_probe_before_showing_controls() {
        val p = ProjectionLiveness()
        assertTrue(p.update(1, 1, 10, true, 0, 0).visible)
        assertEquals(ProjectionLiveness.Decision(true, true), p.update(1, 1, 10, true, 10_000, 10_000))
        assertTrue(p.update(1, 1, 10, true, 14_999, 14_999).visible)
        assertEquals(ProjectionLiveness.Decision(false), p.update(1, 1, 10, true, 15_000, 15_000))
        assertTrue(p.update(1, 1, 11, true, 0, 16_000).visible)
    }

    @Test fun static_video_with_audio_or_a_probe_response_remains_visible() {
        val p = ProjectionLiveness()
        assertTrue(p.update(1, 1, 10, true, 5, 100_000).visible)
        assertTrue(p.update(1, 1, 10, true, 10_000, 110_000).requestKeyframe)
        assertTrue(p.update(1, 1, 11, true, 11_000, 111_000).visible)
        assertFalse(p.update(1, 1, 11, true, 0, 112_000).requestKeyframe)
    }

    @Test fun ended_session_and_new_surface_cannot_reuse_historical_frames() {
        val p = ProjectionLiveness()
        p.update(1, 1, 10, true, 10_000, 10_000)
        assertFalse(p.update(1, 1, 10, false, null, 11_000).visible)
        assertFalse(p.update(2, 2, 0, true, null, 12_000).visible)
        assertTrue(p.update(2, 2, 1, true, 10_000, 20_000).requestKeyframe)
        assertTrue(p.update(2, 3, 1, true, 10_000, 30_000).visible)
    }
}
