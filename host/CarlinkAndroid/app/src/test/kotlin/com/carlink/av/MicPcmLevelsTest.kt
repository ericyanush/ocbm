package com.carlink.av

import org.junit.Assert.assertEquals
import org.junit.Test
import zeno.gmccpa.av.MicPcmLevels

class MicPcmLevelsTest {
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
