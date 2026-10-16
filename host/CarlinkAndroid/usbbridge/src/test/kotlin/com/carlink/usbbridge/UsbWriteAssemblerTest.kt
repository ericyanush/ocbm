package com.carlink.usbbridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbWriteAssemblerTest {
    @Test
    fun `assembles ordered and out of order chunks exactly`() {
        val first = ByteArray(UsbBridgeConstants.CHUNK_SIZE) { (it and 0xff).toByte() }
        val last = byteArrayOf(4, 5, 6)
        val assembler = UsbWriteAssembler(2, first.size + last.size, 0)

        assertNull(assembler.add(1, last))
        assertFalse(assembler.complete())
        assertNull(assembler.add(0, first))
        assertTrue(assembler.complete())
        assertArrayEquals(first + last, assembler.join())
    }

    @Test
    fun `rejects incorrect chunk geometry and duplicates`() {
        val assembler = UsbWriteAssembler(2, UsbBridgeConstants.CHUNK_SIZE + 1, 0)
        assertNotNull(assembler.add(0, byteArrayOf(1)))
        assertNull(assembler.add(0, ByteArray(UsbBridgeConstants.CHUNK_SIZE)))
        assertNotNull(assembler.add(0, ByteArray(UsbBridgeConstants.CHUNK_SIZE)))
        assertNotNull(assembler.add(1, byteArrayOf(1, 2)))
        assertNull(assembler.join())
    }

    @Test
    fun `supports empty transfer as one empty chunk`() {
        val assembler = UsbWriteAssembler(1, 0, 0)
        assertNull(assembler.add(0, byteArrayOf()))
        assertArrayEquals(byteArrayOf(), assembler.join())
    }
}
