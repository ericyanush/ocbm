package com.carlink.ocbm

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppOwnedIap2BodiesTest {
    @Test
    fun `StartSession uses the live receiver endpoint and a 32-bit port`() {
        val endpoint = JSONObject()
            .put("ssid", "Test AP").put("passphrase", "test-password")
            .put("channel", 36).put("security", 2).put("port", 7011)
            .put("addresses", JSONArray().put("192.0.2.1").put("fe80::1%br0"))
            .put("deviceId", "02:00:00:00:00:01")
            .put("publicKey", "abcd").put("sourceVersion", "320.17")
        val fields = tlvs(AppOwnedIap2Bodies.startSession(endpoint))
        assertEquals(listOf(1, 2, 3, 4, 5), fields.map { it.first })
        val wireless = tlvs(fields[0].second)
        assertEquals(listOf(0, 1, 2, 3, 3, 4), wireless.map { it.first })
        assertArrayEquals("192.0.2.1\u0000".toByteArray(), wireless[3].second)
        assertArrayEquals("fe80::1\u0000".toByteArray(), wireless[4].second)
        assertArrayEquals(byteArrayOf(2), wireless[5].second)
        assertArrayEquals(byteArrayOf(0, 0, 0x1B, 0x63), fields[1].second)
    }

    @Test
    fun `availability accepts wireless only when phone explicitly reports true`() {
        val yes = tlv(1, tlv(0, byteArrayOf(1)))
        val no = tlv(1, tlv(0, byteArrayOf(0)))
        assertTrue(AppOwnedIap2Bodies.wirelessAvailable(yes))
        assertFalse(AppOwnedIap2Bodies.wirelessAvailable(no))
        assertFalse(AppOwnedIap2Bodies.wirelessAvailable(tlv(0, tlv(0, byteArrayOf(1)))))
    }

    private fun tlv(id: Int, bytes: ByteArray): ByteArray =
        byteArrayOf(0, (bytes.size + 4).toByte(), 0, id.toByte()) + bytes

    private fun tlvs(bytes: ByteArray): List<Pair<Int, ByteArray>> {
        val result = mutableListOf<Pair<Int, ByteArray>>()
        var at = 0
        while (at < bytes.size) {
            val size = ((bytes[at].toInt() and 255) shl 8) or (bytes[at + 1].toInt() and 255)
            val id = ((bytes[at + 2].toInt() and 255) shl 8) or (bytes[at + 3].toInt() and 255)
            require(size >= 4 && at + size <= bytes.size)
            result += id to bytes.copyOfRange(at + 4, at + size)
            at += size
        }
        return result
    }
}
