package com.carlink.usbbridge

/** Validates and assembles one app-to-helper AIDL transfer before any USB bytes are written. */
class UsbWriteAssembler(
    val chunkCount: Int,
    val totalLength: Int,
    val createdAtMs: Long,
) {
    private val chunks = arrayOfNulls<ByteArray>(chunkCount)
    private var received = 0

    fun add(index: Int, data: ByteArray): String? {
        if (index !in chunks.indices) return "chunk index $index outside 0..${chunks.lastIndex}"
        if (chunks[index] != null) return "duplicate chunk $index"
        val expectedSize = minOf(UsbBridgeConstants.CHUNK_SIZE, totalLength - index * UsbBridgeConstants.CHUNK_SIZE)
        if (data.size != expectedSize) return "chunk $index has ${data.size} bytes; expected $expectedSize"
        chunks[index] = data.copyOf()
        received++
        return null
    }

    fun complete(): Boolean = received == chunks.size

    fun join(): ByteArray? {
        if (!complete()) return null
        val result = ByteArray(totalLength)
        var offset = 0
        for (chunk in chunks) {
            val value = chunk ?: return null
            value.copyInto(result, offset)
            offset += value.size
        }
        return result.takeIf { offset == totalLength }
    }
}
