package com.carlink.av

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoInputPumpTest {
    @Test
    fun servicesOutputAfterOneFrameWhileTheNextNetworkReadIsBlocked() {
        val unblock = CountDownLatch(1)
        val reads = AtomicInteger()
        val live = AtomicBoolean(true)
        val outputServiced = CountDownLatch(1)
        var readyAt = Long.MAX_VALUE
        val failures = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val owner = Thread {
            try {
                VideoInputPump(Closeable { unblock.countDown() }) {
                    if (reads.getAndIncrement() == 0) byteArrayOf(1) else { unblock.await(); null }
                }.use { input ->
                    input.consume(
                        running = { live.get() },
                        output = {
                            if (System.nanoTime() >= readyAt) {
                                outputServiced.countDown()
                                live.set(false)
                            }
                        },
                        onFrame = { readyAt = System.nanoTime() + 20_000_000L },
                        waitMs = { 8 },
                    )
                }
            } catch (t: Throwable) { failures.set(t) }
        }
        owner.start()
        try {
            assertTrue("output must not wait for a second input frame", outputServiced.await(2, TimeUnit.SECONDS))
        } finally {
            live.set(false); unblock.countDown(); owner.join(1000)
        }
        failures.get()?.let { throw AssertionError(it) }
        assertFalse(owner.isAlive)
    }

    @Test
    fun boundedInputStopsReadingAndCloseUnblocksTheProducer() {
        val reads = AtomicInteger()
        val second = CountDownLatch(1)
        val closed = AtomicBoolean(false)
        val input = VideoInputPump(Closeable { closed.set(true) }) {
            val n = reads.incrementAndGet()
            if (n == 2) second.countDown()
            byteArrayOf(1)
        }
        try {
            assertTrue(second.await(1, TimeUnit.SECONDS))
            assertEquals(2, reads.get())
        } finally { input.close() }
        assertTrue(closed.get())
    }

    @Test
    fun forwardsReadFailureToTheOwner() {
        VideoInputPump(Closeable {}) { throw IOException("read failed") }.use { input ->
            try {
                input.consume({ true }, {}, {}, { 10 })
                throw AssertionError("expected read failure")
            } catch (e: IOException) { assertEquals("read failed", e.message) }
        }
    }
}
