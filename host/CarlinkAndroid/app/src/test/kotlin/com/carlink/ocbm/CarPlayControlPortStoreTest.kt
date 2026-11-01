package com.carlink.ocbm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CarPlayControlPortStoreTest {
    @Test fun default_is_preserved_and_override_survives_store_recreation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("carlink_receiver", Context.MODE_PRIVATE).edit().clear().commit()
        assertEquals(7011, CarPlayControlPortStore(context).port)
        assertFalse(CarPlayControlPortStore(context).bridgeOnly)
        assertTrue(CarPlayControlPortStore(context).save(5000, bridgeOnly = true))
        assertEquals(5000, CarPlayControlPortStore(context).port)
        assertTrue(CarPlayControlPortStore(context).bridgeOnly)
        assertTrue(CarPlayControlPortStore(context).save(5001))
        assertTrue(CarPlayControlPortStore(context).bridgeOnly)
    }

    @Test fun malformed_saved_setting_falls_back_and_invalid_edit_preserves_current_port() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("carlink_receiver", Context.MODE_PRIVATE)
        prefs.edit().clear().putInt("control_port", 0).commit()
        assertEquals(7011, CarPlayControlPortStore(context).port)
        prefs.edit().putString("control_port", "invalid").commit()
        assertEquals(7011, CarPlayControlPortStore(context).port)
        val store = CarPlayControlPortStore(context)
        assertTrue(store.save(5000))
        try {
            store.save(65536)
            fail("Invalid edit must be rejected")
        } catch (_: IllegalArgumentException) { }
        assertEquals(5000, CarPlayControlPortStore(context).port)
    }
}
