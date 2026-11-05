package com.carlink.ocbm

import java.io.ByteArrayOutputStream

/** iAP2 link framing for an app-owned RFCOMM stream. One instance belongs to one phone socket. */
internal class AppIap2Link {
    data class Packet(val control: Int, val sequence: Int, val session: Int, val messageId: Int?, val body: ByteArray) {
        val isSynAck: Boolean get() = control and 0xC0 == 0xC0
    }

    private var nextSequence = 0
    private var peerSequence = 0
    private var pending = ByteArray(0)

    fun begin(): ByteArray = DETECT + frame(0x80, 0, SYN_PARAMS, true)

    fun ack(): ByteArray = frame(0x40, 0, byteArrayOf(), false)

    fun message(messageId: Int, body: ByteArray): ByteArray {
        require(messageId in 0..65535 && body.size <= 4096 - 16)
        val payload = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x40, 0x40))
            writeU16(6 + body.size)
            writeU16(messageId)
            write(body)
        }.toByteArray()
        return frame(0x40, 1, payload, true)
    }

    fun feed(bytes: ByteArray): List<Packet> {
        require(pending.size + bytes.size <= 256 * 1024) { "iAP2 receive buffer exceeded" }
        pending += bytes
        val packets = mutableListOf<Packet>()
        var offset = 0
        while (offset < pending.size) {
            if (pending[offset].toInt() and 255 != 0xFF) { offset++; continue }
            if (offset + 1 >= pending.size) break
            if (pending[offset + 1].toInt() and 255 != 0x5A) { offset++; continue }
            if (offset + 4 > pending.size) break
            val length = u16(pending, offset + 2)
            if (length !in 9..65535) { offset++; continue }
            if (offset + length > pending.size) break
            val frame = pending.copyOfRange(offset, offset + length)
            offset += length
            if (checksum(frame, 0, 8) != frame[8]) continue
            if (length > 9 && checksum(frame, 9, length - 10) != frame[length - 1]) continue
            val control = frame[4].toInt() and 255
            peerSequence = frame[5].toInt() and 255
            val session = frame[7].toInt() and 255
            val payload = if (length == 9) byteArrayOf() else frame.copyOfRange(9, length - 1)
            val hasMessage = session == 1 && payload.size >= 6 &&
                payload[0] == 0x40.toByte() && payload[1] == 0x40.toByte() &&
                u16(payload, 2) == payload.size
            packets += Packet(control, peerSequence, session,
                if (hasMessage) u16(payload, 4) else null,
                if (hasMessage) payload.copyOfRange(6, payload.size) else payload)
        }
        pending = pending.copyOfRange(offset, pending.size)
        return packets
    }

    private fun frame(control: Int, session: Int, payload: ByteArray, consumeSequence: Boolean): ByteArray {
        val size = 9 + payload.size + if (payload.isEmpty()) 0 else 1
        require(size <= 65535)
        val sequence = if (consumeSequence) nextSequence else (nextSequence + 255) and 255
        val out = byteArrayOf(0xFF.toByte(), 0x5A, (size shr 8).toByte(), size.toByte(),
            control.toByte(), sequence.toByte(), peerSequence.toByte(), session.toByte(), 0)
        out[8] = checksum(out, 0, 8)
        if (consumeSequence) nextSequence = (nextSequence + 1) and 255
        return if (payload.isEmpty()) out else out + payload + checksum(payload, 0, payload.size)
    }

    private fun ByteArrayOutputStream.writeU16(value: Int) {
        write(value shr 8)
        write(value)
    }

    private fun u16(bytes: ByteArray, offset: Int) =
        ((bytes[offset].toInt() and 255) shl 8) or (bytes[offset + 1].toInt() and 255)

    private fun checksum(bytes: ByteArray, offset: Int, count: Int): Byte {
        var sum = 0
        for (index in offset until offset + count) sum += bytes[index].toInt() and 255
        return (-sum).toByte()
    }

    companion object {
        private val DETECT = byteArrayOf(0xFF.toByte(), 0x55, 0x02, 0x00, 0xEE.toByte(), 0x10)
        private val SYN_PARAMS = byteArrayOf(1, 32, 0x10, 0, 7, 0xD0.toByte(), 7, 0xD0.toByte(),
            30, 6, 1, 0, 1, 2, 1, 1)
    }
}
