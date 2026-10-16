package com.carlink.usbbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsbBulkWriteLoopTest {
    @Test
    fun `continues after positive partial bulk writes`() {
        val calls = mutableListOf<Pair<Int, Int>>()
        val source = ByteArray(12) { (it + 1).toByte() }
        val wire = mutableListOf<Byte>()
        val results = ArrayDeque(listOf(5, 3, 4))

        val failure = UsbBulkWriteLoop.writeAll(12, 8) { offset, requested ->
            calls += offset to requested
            val accepted = results.removeFirst()
            wire += source.copyOfRange(offset, offset + accepted).toList()
            accepted
        }

        assertNull(failure)
        assertEquals(listOf(0 to 8, 5 to 7, 8 to 4), calls)
        assertEquals(source.toList(), wire)
        assertEquals(0, results.size)
    }

    @Test
    fun `reports nonpositive write result with exact progress`() {
        val failure = UsbBulkWriteLoop.writeAll(12, 8) { offset, _ ->
            if (offset == 0) 5 else -1
        }

        assertEquals(UsbBulkWriteLoop.Failure(5, 7, -1, "transfer returned no data"), failure)
    }

    @Test
    fun `rejects a result larger than the requested remainder`() {
        val failure = UsbBulkWriteLoop.writeAll(4, 4) { _, _ -> 5 }

        assertEquals(UsbBulkWriteLoop.Failure(0, 4, 5, "transfer exceeded requested length"), failure)
    }

    @Test
    fun `empty transfer succeeds without calling USB`() {
        var called = false
        val failure = UsbBulkWriteLoop.writeAll(0, 8) { _, _ ->
            called = true
            -1
        }

        assertNull(failure)
        assertEquals(false, called)
    }
}
