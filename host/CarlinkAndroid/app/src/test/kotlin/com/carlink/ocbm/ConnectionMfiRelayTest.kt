package com.carlink.ocbm

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test
import zeno.gmccpa.pair.MfiRelay

class ConnectionMfiRelayTest {
    private fun certificate() = requireNotNull(javaClass.getResourceAsStream("/mfi/test-certificate.p7b")).use { it.readBytes() }

    private class Fake(var bytes: ByteArray) : MfiRelay {
        val reads = AtomicInteger()
        val signatures = AtomicInteger()
        override fun copyCertificate(): ByteArray { reads.incrementAndGet(); return bytes.copyOf() }
        override fun createSignature(digest: ByteArray): ByteArray { signatures.incrementAndGet(); return digest.copyOf() }
    }

    @Test fun normal_and_fast_calls_share_a_defensive_certificate_copy_but_never_cache_signatures() {
        val original = certificate()
        val device = Fake(original)
        val relay = ConnectionMfiRelay(device, {})
        val returned = relay.copyCertificate()
        returned.fill(0)
        assertArrayEquals(original, relay.copyCertificateFast())
        assertEquals(1, device.reads.get())
        assertArrayEquals(byteArrayOf(1), relay.createSignature(byteArrayOf(1)))
        assertArrayEquals(byteArrayOf(2), relay.createSignatureFast(byteArrayOf(2)))
        assertEquals(2, device.signatures.get())
    }

    @Test fun invalid_certificate_is_not_cached_and_a_later_read_can_recover() {
        val device = Fake(byteArrayOf(1, 2, 3))
        val relay = ConnectionMfiRelay(device, {})
        assertThrows(Exception::class.java) { relay.copyCertificate() }
        device.bytes = certificate()
        assertArrayEquals(device.bytes, relay.copyCertificateFast())
        assertEquals(2, device.reads.get())
    }

    @Test fun retirement_is_checked_even_for_cached_certificates_and_a_rebind_reads_again() {
        var current = true
        val device = Fake(certificate())
        val first = ConnectionMfiRelay(device, { check(current) })
        first.copyCertificate()
        current = false
        assertThrows(IllegalStateException::class.java) { first.copyCertificateFast() }
        val replacement = ConnectionMfiRelay(device, {})
        replacement.copyCertificate()
        assertEquals(2, device.reads.get())
    }

    @Test fun overlapping_certificate_requests_read_the_coprocessor_once() {
        val device = Fake(certificate())
        val relay = ConnectionMfiRelay(device, {})
        val start = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()
        val workers = (1..4).map {
            thread {
                start.await()
                runCatching { assertArrayEquals(device.bytes, relay.copyCertificateFast()) }
                    .onFailure { error.set(it) }
            }
        }
        start.countDown()
        workers.forEach { it.join(2000); assertFalse(it.isAlive) }
        error.get()?.let { throw it }
        assertEquals(1, device.reads.get())
    }
}
