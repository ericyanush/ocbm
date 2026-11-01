package com.carlink.av

import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Reads complete messages only. All codec calls remain on the consume/owner thread. */
internal class VideoInputPump(
    private val source: Closeable,
    private val readMessage: () -> ByteArray?,
) : Closeable {
    data class Frame(val bytes: ByteArray, val arrivedNs: Long)
    private data class Delivery(val frame: Frame? = null, val failure: Throwable? = null)
    // One queued message plus the reader's current message; bounded backpressure, no frame drops.
    private val queue = ArrayBlockingQueue<Delivery>(1)
    private val closed = AtomicBoolean(false)
    private val reader = Thread({
        try {
            while (!closed.get()) {
                val bytes = readMessage()
                val delivery = Delivery(bytes?.let { Frame(it, System.nanoTime()) })
                queue.put(delivery)
                if (bytes == null) break
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (failure: Throwable) {
            if (!closed.get()) {
                try { queue.put(Delivery(failure = failure)) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            }
        }
    }, "carplay-video-input").apply { isDaemon = true; start() }

    fun consume(
        running: () -> Boolean,
        output: () -> Unit,
        onFrame: (Frame) -> Unit,
        waitMs: () -> Long,
    ) {
        while (running()) {
            output()
            val delivery = queue.poll(waitMs(), TimeUnit.MILLISECONDS) ?: continue
            delivery.failure?.let { throw it }
            val frame = delivery.frame ?: break
            if (running()) onFrame(frame)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Closing the source unblocks its read; interrupt unblocks a full queue put.
        reader.interrupt()
        runCatching { source.close() }
        try { reader.join(250) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        queue.clear()
    }
}
