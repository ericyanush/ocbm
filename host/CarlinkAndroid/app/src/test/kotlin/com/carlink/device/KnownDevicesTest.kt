package com.carlink.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The persisted document format and the merge rules.
 *
 * The document is a compatibility surface — one app version writes it, the next reads it — so it is
 * pinned two ways: a golden string for what the writer emits, and a hand-authored fixture the writer
 * never produced for what the reader must accept. `read(write(x)) == x` alone would pass for any
 * self-consistent format, including one a future version cannot parse.
 */
class KnownDevicesTest {
    @Test
    fun `native phone identity is accepted only for the current control session and phone`() {
        val identity = org.json.JSONObject("""{"kind":"phoneIdentity","controlGeneration":42,"deviceID":"02:00:00:AA:BB:01","name":"Test Phone A"}""")
        assertTrue(nativePhoneIdentityMatches(identity, 42, "02:00:00:aa:bb:01"))
        assertTrue(nativePhoneIdentityMatches(identity, 42, null))
        assertEquals(false, nativePhoneIdentityMatches(identity, 43, null))
        assertEquals(false, nativePhoneIdentityMatches(identity, 42, "02:00:00:AA:BB:02"))
        identity.put("deviceID", "")
        assertEquals(false, nativePhoneIdentityMatches(identity, 42, null))
    }
    private val liveMac = "02:00:00:aa:bb:01"

    @Test
    fun `live iap2 slot appears before the bond snapshot and history arrive`() {
        val out = mergeDeviceList(emptyMap(), emptySet(), emptySet(), null,
            iap2Slots = mapOf(liveMac.uppercase() to com.carlink.ocbm.Ocbm.BTP_WIFI_HANDOFF)) { "t$it" }
        assertEquals(listOf(liveMac), out.map { it.btMac })
        assertEquals(com.carlink.ocbm.Ocbm.BTP_WIFI_HANDOFF, out.single().iap2Phase)
        assertEquals(false, out.single().bonded) // A live slot does not invent a persisted bond.
    }

    @Test
    fun `native active phone appears even without an adapter slot or bond`() {
        val out = mergeDeviceList(emptyMap(), emptySet(), emptySet(), null,
            activePhoneMac = liveMac.uppercase()) { "t$it" }
        assertEquals(liveMac, out.single().btMac)
    }

    @Test
    fun `forgotten phone stays hidden from stale active or slot observations`() {
        val out = mergeDeviceList(emptyMap(), setOf(liveMac), setOf(liveMac), null,
            iap2Slots = mapOf(liveMac.uppercase() to com.carlink.ocbm.Ocbm.BTP_WIFI_HANDOFF),
            activePhoneMac = liveMac) { "t$it" }
        assertTrue(out.isEmpty())
    }

    @Test
    fun `confirmed native reconnect restores history while preserving preference and identity`() {
        val original = KnownDeviceSnapshot(
            devices = mapOf(liveMac to dev(liveMac, name = "Test Phone A", first = 10, last = 20)),
            preferredMac = "another-phone",
        )
        val restored = original.withConnectedPhone(liveMac.uppercase(), null, 30)
        assertEquals("another-phone", restored.preferredMac)
        assertEquals("Test Phone A", restored.devices[liveMac]?.name)
        assertEquals(10L, restored.devices[liveMac]?.firstSeenMs)
        assertEquals(30L, restored.devices[liveMac]?.lastConnectedMs)
        val fresh = KnownDeviceSnapshot().withConnectedPhone(liveMac, "New name", 30)
        val merged = mergeDeviceList(fresh.devices, setOf(liveMac), emptySet(), null,
            iap2Slots = mapOf(liveMac.uppercase() to com.carlink.ocbm.Ocbm.BTP_WIFI_HANDOFF), activePhoneMac = liveMac) { "t$it" }
        assertEquals(1, merged.size)
        assertEquals("New name", merged.single().name)
    }

    @Test
    fun `expired suppression is removed before this merge and bond learning`() {
        val suppressed = mutableMapOf(liveMac to 25L, "other" to 50L)
        val hidden = activeForgetSuppressions(suppressed, 25)
        assertEquals(setOf("other"), hidden)
        assertEquals(listOf(liveMac), merge(emptyMap(), setOf(liveMac), hidden).map { it.btMac })
    }

    @Test
    fun `idle slot alone does not resurrect a removed phone`() {
        assertTrue(mergeDeviceList(emptyMap(), emptySet(), emptySet(), null,
            iap2Slots = mapOf(liveMac.uppercase() to com.carlink.ocbm.Ocbm.BTP_IDLE)) { "t$it" }.isEmpty())
    }
    private fun dev(
        mac: String,
        name: String? = null,
        first: Long = 1_000L,
        last: Long? = null,
    ) = KnownDevice(mac = mac, name = name, firstSeenMs = first, lastConnectedMs = last)

    // ---- codec -----------------------------------------------------------------------------------

    @Test
    fun `encodes to the expected document`() {
        val snap =
            KnownDeviceSnapshot(
                devices =
                    mapOf(
                        "aa:bb:cc:dd:ee:ff" to
                            KnownDevice(
                                mac = "aa:bb:cc:dd:ee:ff",
                                name = "Test iPhone",
                                model = "iPhone18,4",
                                osName = "iPhone OS",
                                osVersion = "27.0",
                                firstSeenMs = 1_700_000_000_000L,
                                lastConnectedMs = 1_700_000_009_999L,
                            ),
                    ),
                preferredMac = "aa:bb:cc:dd:ee:ff",
            )
        assertEquals(
            """{"v":1,"preferredMac":"aa:bb:cc:dd:ee:ff","devices":[{"mac":"aa:bb:cc:dd:ee:ff",""" +
                """"name":"Test iPhone","model":"iPhone18,4","osName":"iPhone OS","osVersion":"27.0",""" +
                """"firstSeenMs":1700000000000,"lastConnectedMs":1700000009999}]}""",
            KnownDeviceCodec.encode(snap),
        )
    }

    /** A document this test wrote by hand, NOT one produced by [KnownDeviceCodec.encode]. */
    @Test
    fun `reads a hand-authored v1 document`() {
        val snap =
            KnownDeviceCodec.decode(
                """
                {"v":1,"preferredMac":"11:22:33:44:55:66","devices":[
                  {"mac":"11:22:33:44:55:66","name":"Spare","firstSeenMs":5,"lastConnectedMs":9},
                  {"mac":"AA:BB:CC:DD:EE:FF","firstSeenMs":1}
                ]}
                """.trimIndent(),
            )
        assertEquals(2, snap.devices.size)
        assertEquals("Spare", snap.devices["11:22:33:44:55:66"]?.name)
        assertEquals(9L, snap.devices["11:22:33:44:55:66"]?.lastConnectedMs)
        // MACs normalise to lower case, so the join against MGMT_INFO cannot miss on case alone.
        assertEquals("aa:bb:cc:dd:ee:ff", snap.devices["aa:bb:cc:dd:ee:ff"]?.mac)
        // Absent optional fields decode as null, not "".
        assertNull(snap.devices["aa:bb:cc:dd:ee:ff"]?.name)
        assertNull(snap.devices["aa:bb:cc:dd:ee:ff"]?.lastConnectedMs)
        assertEquals("11:22:33:44:55:66", snap.preferredMac)
    }

    /** Device names are typed by the user on the phone, so they are hostile input. */
    @Test
    fun `round-trips a name with quotes backslashes newlines and emoji`() {
        val nasty = "Owner\"s \\ iPhone 🚗\nline2"
        val snap = KnownDeviceSnapshot(devices = mapOf("aa" to dev("aa", name = nasty)))
        val back = KnownDeviceCodec.decode(KnownDeviceCodec.encode(snap))
        assertEquals(nasty, back.devices["aa"]?.name)
    }

    @Test
    fun `decode never throws and yields empty on anything unreadable`() {
        for (bad in listOf(null, "", "   ", "not json", "[]", "{", """{"v":99,"devices":[]}""")) {
            assertEquals(KnownDeviceSnapshot(), KnownDeviceCodec.decode(bad))
        }
        // A record with no MAC cannot be joined against anything, so it is dropped rather than kept.
        assertTrue(KnownDeviceCodec.decode("""{"v":1,"devices":[{"name":"x"}]}""").devices.isEmpty())
    }

    @Test
    fun `encoding is deterministic regardless of map order`() {
        val a = KnownDeviceSnapshot(devices = linkedMapOf("bb" to dev("bb"), "aa" to dev("aa")))
        val b = KnownDeviceSnapshot(devices = linkedMapOf("aa" to dev("aa"), "bb" to dev("bb")))
        assertEquals(KnownDeviceCodec.encode(a), KnownDeviceCodec.encode(b))
    }

    // ---- merge -----------------------------------------------------------------------------------

    private fun merge(
        known: Map<String, KnownDevice>,
        bonded: Set<String>,
        suppressed: Set<String> = emptySet(),
        preferred: String? = null,
    ) = mergeDeviceList(known, bonded, suppressed, preferred) { "t$it" }

    @Test
    fun `a bonded device the app has never seen appears under its MAC`() {
        val out = merge(emptyMap(), setOf("aa:bb:cc:dd:ee:ff"))
        assertEquals(1, out.size)
        assertEquals("aa:bb:cc:dd:ee:ff", out[0].name)
        assertTrue(out[0].bonded)
        assertNull(out[0].lastConnected)
    }

    @Test
    fun `a remembered device carries its name and last-seen`() {
        val out = merge(mapOf("aa" to dev("aa", name = "Test iPhone", last = 42L)), setOf("aa"))
        assertEquals("Test iPhone", out[0].name)
        assertEquals("t42", out[0].lastConnected)
    }

    /**
     * History outlives the bond, but must never render as connectable — the box cannot page a phone
     * it has no link key for, and offering Connect is a lie the user discovers by tapping it.
     */
    @Test
    fun `history without a bond is listed but marked not bonded`() {
        val out = merge(mapOf("aa" to dev("aa", name = "Old")), bonded = emptySet())
        assertEquals(1, out.size)
        assertEquals("Old", out[0].name)
        assertEquals(false, out[0].bonded)
    }

    @Test
    fun `a suppressed MAC is excluded from both sides of the merge`() {
        val out = merge(mapOf("aa" to dev("aa", name = "Gone")), bonded = setOf("aa"), suppressed = setOf("aa"))
        assertTrue(out.isEmpty())
    }

    @Test
    fun `ordering is preferred then most-recently-connected then first-seen`() {
        val known =
            mapOf(
                "aa" to dev("aa", first = 1, last = 100),
                "bb" to dev("bb", first = 2, last = 300),
                "cc" to dev("cc", first = 3, last = null),
            )
        // No preference: recency wins, never-connected last.
        assertEquals(listOf("bb", "aa", "cc"), merge(known, setOf("aa", "bb", "cc")).map { it.btMac })
        // Explicit selection beats recency, and is sticky.
        assertEquals(
            listOf("cc", "bb", "aa"),
            merge(known, setOf("aa", "bb", "cc"), preferred = "cc").map { it.btMac },
        )
    }

    @Test
    fun `a preferred MAC is matched case-insensitively`() {
        val out = merge(mapOf("aa" to dev("aa"), "bb" to dev("bb", last = 9)), setOf("aa", "bb"), preferred = "AA")
        assertEquals("aa", out[0].btMac)
    }

    @Test
    fun `a device in both history and the bond list appears once`() {
        val out = merge(mapOf("aa" to dev("aa", name = "One")), setOf("aa", "AA:"))
        assertEquals(1, out.count { it.btMac == "aa" })
    }
}
