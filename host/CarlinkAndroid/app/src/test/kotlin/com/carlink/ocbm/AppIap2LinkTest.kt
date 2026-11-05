package com.carlink.ocbm

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppIap2LinkTest {
    @Test
    fun `startup matches the adapter's captured detect and syn bytes`() {
        val expected = byteArrayOf(
            0xFF.toByte(), 0x55, 0x02, 0x00, 0xEE.toByte(), 0x10,
            0xFF.toByte(), 0x5A, 0x00, 0x1A, 0x80.toByte(), 0x00, 0x00, 0x00, 0x0D,
            0x01, 0x20, 0x10, 0x00, 0x07, 0xD0.toByte(), 0x07, 0xD0.toByte(),
            0x1E, 0x06, 0x01, 0x00, 0x01, 0x02, 0x01, 0x01, 0xF7.toByte(),
        )
        assertArrayEquals(expected, AppIap2Link().begin())
    }

    @Test
    fun `split and coalesced frames preserve both messages`() {
        val sender = AppIap2Link()
        sender.begin()
        val first = sender.message(0xAA00, byteArrayOf(1, 2))
        val second = sender.message(0x1D00, byteArrayOf(3))
        val receiver = AppIap2Link()
        assertTrue(receiver.feed(first.copyOfRange(0, 5)).isEmpty())
        val packets = receiver.feed(first.copyOfRange(5, first.size) + second)
        assertEquals(listOf(0xAA00, 0x1D00), packets.map { it.messageId })
        assertArrayEquals(byteArrayOf(1, 2), packets[0].body)
        assertArrayEquals(byteArrayOf(3), packets[1].body)
        assertEquals(second[5], receiver.ack()[6])
    }
}
