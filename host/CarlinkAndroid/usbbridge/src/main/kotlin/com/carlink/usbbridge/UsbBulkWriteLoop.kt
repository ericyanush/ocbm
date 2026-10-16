package com.carlink.usbbridge

/** Writes a byte range completely while accepting positive partial USB bulk-transfer results.
 *
 * OCBM is a byte stream: USB transfer boundaries do not delimit OCBM frames. After a positive
 * short OUT result, only the remaining suffix is submitted, under the caller's serialized write
 * lock. Replaying the already-written prefix would corrupt the frame stream.
 */
object UsbBulkWriteLoop {
    data class Failure(
        val offset: Int,
        val requested: Int,
        val result: Int,
        val reason: String,
    )

    /**
     * Calls [transfer] with bounded slices until [totalBytes] have been accepted. Android's USB
     * API returns the actual byte count, which may be positive but smaller than requested; that
     * remainder must be submitted again instead of treating the whole OCBM frame as failed.
     */
    fun writeAll(
        totalBytes: Int,
        maxChunkSize: Int,
        transfer: (offset: Int, requested: Int) -> Int,
    ): Failure? {
        require(totalBytes >= 0) { "totalBytes must not be negative" }
        require(maxChunkSize > 0) { "maxChunkSize must be positive" }

        var offset = 0
        while (offset < totalBytes) {
            val requested = minOf(maxChunkSize, totalBytes - offset)
            val result = transfer(offset, requested)
            if (result <= 0) return Failure(offset, requested, result, "transfer returned no data")
            if (result > requested) return Failure(offset, requested, result, "transfer exceeded requested length")
            offset += result
        }
        return null
    }
}
