package com.carlink.ocbm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VehicleHotspotCredentialsTest {
    @Test
    fun `accepts a normal truck hotspot configuration`() {
        assertNull(VehicleHotspotCredentials("myChevrolet 32D4", "truckpass123", 36).validationError())
    }

    @Test
    fun `rejects incomplete or malformed credentials before subscription`() {
        assertEquals(
            "Enter the hotspot SSID without leading or trailing spaces.",
            VehicleHotspotCredentials("", "truckpass123", 36).validationError(),
        )
        assertEquals(
            "The hotspot password must be 8–63 bytes.",
            VehicleHotspotCredentials("truck-wifi", "short", 36).validationError(),
        )
        assertEquals(
            "Enter a valid Wi-Fi channel number.",
            VehicleHotspotCredentials("truck-wifi", "truckpass123", 0).validationError(),
        )
        assertEquals(
            "SSID and password cannot contain line breaks or YAML-sensitive patterns.",
            VehicleHotspotCredentials("truck: wifi", "truckpass123", 36).validationError(),
        )
    }
}
