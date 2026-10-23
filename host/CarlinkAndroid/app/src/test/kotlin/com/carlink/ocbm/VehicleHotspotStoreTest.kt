package com.carlink.ocbm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
class VehicleHotspotStoreTest {
    @Test fun unreadable_saved_password_is_retryable_preserves_settings_and_logs_no_secrets() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("vehicle_hotspot", Context.MODE_PRIVATE)
        prefs.edit().clear()
            .putString("ssid", "Test hotspot")
            .putString("channel", "149")
            .putString("password_ciphertext", "sensitive-ciphertext-marker")
            .putString("password_iv", "%sensitive-iv-marker%")
            .commit()
        val before = prefs.all.toMap()
        ShadowLog.clear()
        val store = VehicleHotspotStore(context)

        try {
            store.credentialsForStartSession()
            fail("A saved password read failure must wait rather than reject the phone")
        } catch (e: HotspotNotReady) {
            assertTrue(e.message.orEmpty().contains("temporarily unreadable"))
            assertFalse(e.message.orEmpty().contains("sensitive-"))
        }
        assertNull(store.credentials()) // Settings retains its nullable API.
        assertEquals(before, prefs.all)
        val logs = ShadowLog.getLogs().joinToString("\n") { it.msg }
        assertTrue(logs.contains("Saved password read failed"))
        assertFalse(logs.contains("sensitive-"))
    }

    @Test fun never_configured_credentials_still_return_null_instead_of_waiting_forever() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("vehicle_hotspot", Context.MODE_PRIVATE).edit().clear().commit()
        assertNull(VehicleHotspotStore(context).credentialsForStartSession())
    }
}
