package com.carlink.ocbm

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import zeno.gmccpa.pair.MfiRelay

class RebindableMfiRelayTest {
    @Test
    fun `uses replacement adapter relay without recreating receiver relay`() {
        val relay = RebindableMfiRelay()
        val first = FakeRelay(byteArrayOf(1))
        val replacement = FakeRelay(byteArrayOf(2))

        relay.bind(10, first)
        assertArrayEquals(byteArrayOf(1), relay.createSignature(byteArrayOf(9)))

        relay.bind(11, replacement)
        assertArrayEquals(byteArrayOf(2), relay.createSignature(byteArrayOf(9)))
        assertEquals(1, first.signatureCalls)
        assertEquals(1, replacement.signatureCalls)
    }

    @Test
    fun `fails clearly while no subscribed adapter relay is bound`() {
        val relay = RebindableMfiRelay()

        val error = assertThrows(IllegalStateException::class.java) {
            relay.createSignature(byteArrayOf(9))
        }

        assertTrue(error.message.orEmpty().contains("MFi adapter unavailable"))
    }

    @Test
    fun `retired generation cannot clear its replacement`() {
        val relay = RebindableMfiRelay()
        val replacement = FakeRelay(byteArrayOf(2))
        relay.bind(11, replacement)

        relay.clear(10)

        assertArrayEquals(byteArrayOf(2), relay.copyCertificate())
    }

    @Test
    fun `does not return a response from a relay replaced during the operation`() {
        val relay = RebindableMfiRelay()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        relay.bind(10, FakeRelay(byteArrayOf(1), entered, release))

        var failure: Throwable? = null
        val worker = Thread {
            runCatching { relay.createSignature(byteArrayOf(9)) }
                .onFailure { failure = it }
        }
        worker.start()
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        relay.bind(11, FakeRelay(byteArrayOf(2)))
        release.countDown()
        worker.join(1000)

        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("session changed"))
    }

    private class FakeRelay(
        private val response: ByteArray,
        private val entered: CountDownLatch? = null,
        private val release: CountDownLatch? = null,
    ) : MfiRelay {
        var signatureCalls = 0
            private set

        override fun copyCertificate(): ByteArray = response

        override fun createSignature(digest: ByteArray): ByteArray {
            signatureCalls++
            entered?.countDown()
            release?.await(1, TimeUnit.SECONDS)
            return response
        }
    }
}
