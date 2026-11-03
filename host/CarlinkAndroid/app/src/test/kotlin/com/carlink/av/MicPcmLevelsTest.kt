package com.carlink.av

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import zeno.gmccpa.av.MicPcmLevels
import zeno.gmccpa.av.MicZeroDetector

class MicPcmLevelsTest {
    @Test fun fallback_requires_consecutive_exact_zero_chunks() {
        val detector = MicZeroDetector(3)
        assertFalse(detector.observe(true))
        assertFalse(detector.observe(true))
        assertFalse(detector.observe(false))
        assertFalse(detector.observe(true))
        assertFalse(detector.observe(true))
        assertTrue(detector.observe(true))
        detector.reset()
        assertFalse(detector.observe(true))
    }
    @Test fun signedSamplesAndSilenceAreMeasuredWithoutRetainingAudio() {
        val levels = MicPcmLevels()
        levels.add(byteArrayOf(0, 0, 0, -128, -1, 127, 0, 0), 8)
        assertEquals("samples=4 peak=32768 rms=23170 zeroPct=50", levels.summary())
        levels.reset()
        assertEquals("samples=0 peak=0 rms=0 zeroPct=0", levels.summary())
        levels.add(byteArrayOf(0, 0, 123), 3)
        assertEquals("samples=1 peak=0 rms=0 zeroPct=100", levels.summary())
    }
}
