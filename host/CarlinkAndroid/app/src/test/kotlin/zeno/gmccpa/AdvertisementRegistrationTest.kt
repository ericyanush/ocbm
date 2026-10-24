package zeno.gmccpa

import org.junit.Assert.*
import org.junit.Test

class AdvertisementRegistrationTest {
    @Test fun synchronous_callback_finishes_an_already_armed_watchdog() {
        val events = mutableListOf<String>()
        val state = AdvertisementRegistration { 100L }
        state.register(pending = { events.add("pending") }, submit = { attempt ->
            assertTrue(state.complete(attempt, true) { events.add("registered") })
        })
        assertEquals(listOf("pending", "registered"), events)
        assertTrue(state.await(0))
        assertEquals(100L, state.registeredAt())
    }

    @Test fun old_registration_failure_or_unregistration_cannot_replace_new_success() {
        val state = AdvertisementRegistration { 200L }
        lateinit var old: AdvertisementRegistration.Attempt
        state.register({}, { old = it })
        state.register({}, { state.complete(it, true) {} })
        assertFalse(state.complete(old, false) { fail("old failure notified") })
        state.invalidate(old) { fail("old unregistration notified") }
        assertTrue(state.await(0))
        assertFalse(state.failed())
        state.clear()
        assertFalse(state.complete(old, true) { fail("callback after stop notified") })
        assertFalse(state.await(0))
    }

    @Test fun failed_or_unregistered_attempt_is_not_ready() {
        val state = AdvertisementRegistration { 300L }
        state.register({}, { state.complete(it, false) {} })
        assertFalse(state.await(0))
        lateinit var active: AdvertisementRegistration.Attempt
        state.register({}, { active = it; state.complete(it, true) {} })
        state.invalidate(active) {}
        assertFalse(state.await(0))
        assertEquals(0L, state.registeredAt())
    }
}
