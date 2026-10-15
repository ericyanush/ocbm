package com.carlink.ocbm

/**
 * A minimal reader for Apple's `bplist00` binary property lists.
 *
 * It exists for ONE input: the `META_CMD` payload on `CH_METADATA`, which is the raw plist of an
 * inbound iPhone `POST /command` — `{type: "<verb>", params: {...}}`. The macOS host gets this for
 * free from `PropertyListSerialization`; Android has no equivalent, and the alternative considered
 * (scanning the bytes for the ASCII verb) is not sound — a verb name can appear inside a URL or a
 * nested string, so a scan would fire on payloads that never asked for anything.
 *
 * Deliberately partial. It decodes the types Apple's command plists actually use — dict, array,
 * ASCII/UTF-16 string, integer, real, boolean, data — and returns null for anything malformed
 * rather than guessing. UID and SET are not implemented; they do not appear in this traffic and a
 * silent wrong answer would be worse than no answer.
 *
 * **This parses data that crossed the wire from the phone.** Every read is bounds-checked against
 * the buffer, object references are validated against the object count, and container recursion is
 * depth-capped, so a truncated or hostile payload yields null instead of an exception on the OCBM
 * read thread (which would take the whole dispatch loop with it).
 */
object BinaryPlist {
    private val MAGIC = "bplist00".toByteArray(Charsets.US_ASCII)
    private const val TRAILER_LEN = 32
    // Keep every container's object references at one fixed width. The plist trailer has a single
    // objectRefSize for the whole file; choosing a width as objects are appended can leave early
    // containers narrower than the final trailer says (especially after adding the three icon PNGs).
    private const val OBJECT_REF_SIZE = 4 // Reader caps object count at 2^24, so 4 bytes is sufficient.

    /** Deep enough for `{type, params:{...}}` and then some; cheap insurance against a cyclic table. */
    private const val MAX_DEPTH = 16

    /**
     * Parse [bytes] and return the top-level object as a map, or null if it is not a well-formed
     * `bplist00` whose root is a dictionary.
     */
    fun parseDict(bytes: ByteArray): Map<String, Any?>? = runCatching { Reader(bytes).topDict() }.getOrNull()

    /** Strict counterpart for validating plists produced locally; unlike wire parsing, keep errors. */
    internal fun parseDictOrThrow(bytes: ByteArray): Map<String, Any?>? = Reader(bytes).topDict()

    /** Encode the value types used by the receiver's `/info` document as a binary plist. */
    fun encodeDict(value: Map<String, Any?>): ByteArray = Writer().encode(value)

    private class Writer {
        private val objects = ArrayList<ByteArray>()

        fun encode(root: Map<String, Any?>): ByteArray {
            val top = add(root)
            val objectData = java.io.ByteArrayOutputStream()
            val offsets = ArrayList<Long>(objects.size)
            objectData.write(MAGIC)
            for (obj in objects) {
                offsets += objectData.size().toLong()
                objectData.write(obj)
            }
            val tableOffset = objectData.size().toLong()
            val offsetSize = bytesFor(tableOffset)
            for (offset in offsets) writeUnsigned(objectData, offset, offsetSize)
            objectData.write(ByteArray(6))
            objectData.write(offsetSize)
            objectData.write(OBJECT_REF_SIZE)
            writeUnsigned(objectData, objects.size.toLong(), 8)
            writeUnsigned(objectData, top.toLong(), 8)
            writeUnsigned(objectData, tableOffset, 8)
            return objectData.toByteArray()
        }

        private fun add(value: Any?): Int {
            val encoded = when (value) {
                null -> byteArrayOf(0x00)
                is Boolean -> byteArrayOf(if (value) 0x09 else 0x08)
                is ByteArray -> sized(0x40, value.size, value)
                is String -> encodeString(value)
                is Byte, is Short, is Int, is Long -> encodeInteger((value as Number).toLong())
                is Float, is Double -> {
                    val out = java.io.ByteArrayOutputStream(9)
                    out.write(0x23)
                    writeUnsigned(out, java.lang.Double.doubleToRawLongBits((value as Number).toDouble()), 8)
                    out.toByteArray()
                }
                is List<*> -> {
                    val refs = value.map(::add)
                    container(0xA0, refs)
                }
                is Map<*, *> -> {
                    val entries = value.entries.map { (key, item) ->
                        require(key is String) { "binary plist dictionary keys must be strings" }
                        add(key) to add(item)
                    }
                    val out = java.io.ByteArrayOutputStream()
                    writeCount(out, 0xD0, entries.size)
                    entries.forEach { (key, _) -> writeUnsigned(out, key.toLong(), OBJECT_REF_SIZE) }
                    entries.forEach { (_, item) -> writeUnsigned(out, item.toLong(), OBJECT_REF_SIZE) }
                    out.toByteArray()
                }
                else -> error("unsupported binary plist value ${value::class.java.name}")
            }
            objects += encoded
            return objects.lastIndex
        }

        private fun container(marker: Int, refs: List<Int>): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            writeCount(out, marker, refs.size)
            refs.forEach { writeUnsigned(out, it.toLong(), OBJECT_REF_SIZE) }
            return out.toByteArray()
        }

        private fun encodeString(value: String): ByteArray {
            val ascii = value.all { it.code <= 0x7F }
            val data = if (ascii) value.toByteArray(Charsets.US_ASCII) else value.toByteArray(Charsets.UTF_16BE)
            val out = java.io.ByteArrayOutputStream(data.size + 10)
            writeCount(out, if (ascii) 0x50 else 0x60, if (ascii) value.length else data.size / 2)
            out.write(data)
            return out.toByteArray()
        }

        private fun encodeInteger(value: Long): ByteArray {
            val width = when {
                value in Byte.MIN_VALUE..Byte.MAX_VALUE -> 1
                value in Short.MIN_VALUE..Short.MAX_VALUE -> 2
                value in Int.MIN_VALUE..Int.MAX_VALUE -> 4
                else -> 8
            }
            val out = java.io.ByteArrayOutputStream(width + 1)
            out.write(0x10 or Integer.numberOfTrailingZeros(width))
            writeUnsigned(out, value, width)
            return out.toByteArray()
        }

        private fun sized(marker: Int, count: Int, data: ByteArray): ByteArray {
            val out = java.io.ByteArrayOutputStream(data.size + 10)
            writeCount(out, marker, count)
            out.write(data)
            return out.toByteArray()
        }

        private fun writeCount(out: java.io.ByteArrayOutputStream, marker: Int, count: Int) {
            if (count < 15) {
                out.write(marker or count)
            } else {
                out.write(marker or 0x0F)
                val width = when {
                    count <= 0xFF -> 1
                    count <= 0xFFFF -> 2
                    else -> 4
                }
                out.write(0x10 or Integer.numberOfTrailingZeros(width))
                writeUnsigned(out, count.toLong(), width)
            }
        }

        private fun bytesFor(value: Long): Int = when {
            value <= 0xFF -> 1
            value <= 0xFFFF -> 2
            value <= 0xFFFFFFFFL -> 4
            else -> 8
        }

        private fun writeUnsigned(out: java.io.ByteArrayOutputStream, value: Long, width: Int) {
            for (shift in (width - 1) * 8 downTo 0 step 8) out.write((value ushr shift).toInt() and 0xFF)
        }
    }

    private class Reader(
        private val b: ByteArray,
    ) {
        private val offsetSize: Int
        private val refSize: Int
        private val count: Int
        private val topRef: Int
        private val tableOff: Long

        init {
            require(b.size >= MAGIC.size + TRAILER_LEN) { "too short" }
            require(MAGIC.indices.all { b[it] == MAGIC[it] }) { "bad magic" }
            val t = b.size - TRAILER_LEN
            offsetSize = b[t + 6].toInt() and 0xFF
            refSize = b[t + 7].toInt() and 0xFF
            require(offsetSize in 1..8 && refSize in 1..8) { "bad trailer widths" }
            val n = be(t + 8, 8)
            val top = be(t + 16, 8)
            tableOff = be(t + 24, 8)
            require(n in 1..(1 shl 24) && top < n) { "bad object count/root" }
            require(tableOff >= MAGIC.size && tableOff + n * offsetSize <= t) { "bad offset table" }
            count = n.toInt()
            topRef = top.toInt()
        }

        /** Big-endian unsigned integer of [len] bytes at [at]. */
        private fun be(
            at: Int,
            len: Int,
        ): Long {
            require(at >= 0 && len >= 0 && at + len <= b.size) { "read out of range" }
            var v = 0L
            for (i in 0 until len) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
            return v
        }

        private fun offsetOf(ref: Int): Int {
            require(ref in 0 until count) { "object ref out of range" }
            val o = be((tableOff + ref.toLong() * offsetSize).toInt(), offsetSize)
            require(o >= 0 && o < b.size) { "object offset out of range" }
            return o.toInt()
        }

        @Suppress("UNCHECKED_CAST")
        fun topDict(): Map<String, Any?>? = obj(topRef, 0) as? Map<String, Any?>

        /**
         * Decode the object at [ref].
         *
         * Apple's encoding puts the type in the high nibble and, for sized types, the count in the
         * low nibble — with 0xF meaning "an integer object follows carrying the real count". The
         * split below follows that: fixed-width scalars need no count, everything else does.
         */
        private fun obj(
            ref: Int,
            depth: Int,
        ): Any? {
            require(depth <= MAX_DEPTH) { "too deep" }
            val at = offsetOf(ref)
            val marker = b[at].toInt() and 0xFF
            val hi = marker ushr 4
            val lo = marker and 0x0F
            val p = at + 1
            return when (hi) {
                0x0 -> singleton(lo)
                0x1 -> be(p, 1 shl lo) // int, 2^lo bytes, big-endian
                0x2 -> real(lo, p)
                else -> {
                    val c = Cursor(p)
                    val n = sizedCount(lo, c)
                    sized(hi, n, c.p, depth)
                }
            }
        }

        private class Cursor(
            var p: Int,
        )

        private fun singleton(lo: Int): Any? =
            when (lo) {
                0x8 -> false
                0x9 -> true
                else -> null // 0x0 null, and every unassigned low nibble
            }

        private fun real(
            lo: Int,
            p: Int,
        ): Any? =
            when (lo) {
                2 ->
                    java.lang.Float
                        .intBitsToFloat(be(p, 4).toInt())
                        .toDouble()
                3 -> java.lang.Double.longBitsToDouble(be(p, 8))
                else -> null
            }

        /** Element count for a sized type, consuming the trailing int object when [lo] is 0xF. */
        private fun sizedCount(
            lo: Int,
            c: Cursor,
        ): Int {
            if (lo != 0x0F) return lo
            val m = b[c.p].toInt() and 0xFF
            require(m ushr 4 == 0x1) { "bad count marker" }
            val n = 1 shl (m and 0x0F)
            c.p++
            val v = be(c.p, n)
            c.p += n
            require(v in 0..(1 shl 24)) { "implausible count" }
            return v.toInt()
        }

        private fun sized(
            hi: Int,
            n: Int,
            p: Int,
            depth: Int,
        ): Any? =
            when (hi) {
                0x4 -> { // data
                    require(p + n <= b.size) { "data overruns" }
                    b.copyOfRange(p, p + n)
                }
                0x5 -> { // ASCII string
                    require(p + n <= b.size) { "ascii overruns" }
                    String(b, p, n, Charsets.US_ASCII)
                }
                0x6 -> { // UTF-16BE string — n is in CHARACTERS, not bytes
                    require(p + n * 2 <= b.size) { "utf16 overruns" }
                    String(b, p, n * 2, Charsets.UTF_16BE)
                }
                0xA -> { // array
                    require(p + n * refSize <= b.size) { "array overruns" }
                    (0 until n).map { i -> obj(be(p + i * refSize, refSize).toInt(), depth + 1) }
                }
                0xD -> dict(n, p, depth)
                else -> null
            }

        /** A dict is n key refs followed by n value refs. */
        private fun dict(
            n: Int,
            p: Int,
            depth: Int,
        ): Map<String, Any?> {
            require(p + n * 2 * refSize <= b.size) { "dict overruns" }
            val out = LinkedHashMap<String, Any?>(n.coerceAtMost(64))
            for (i in 0 until n) {
                val k = obj(be(p + i * refSize, refSize).toInt(), depth + 1)
                val v = obj(be(p + (n + i) * refSize, refSize).toInt(), depth + 1)
                // A non-string key cannot be addressed by the callers here; skip rather than coerce,
                // so `d["type"]` never matches something that was not a string key.
                if (k is String) out[k] = v
            }
            return out
        }
    }
}
