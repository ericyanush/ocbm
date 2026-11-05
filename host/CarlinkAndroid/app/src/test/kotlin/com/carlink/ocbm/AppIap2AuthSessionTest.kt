package com.carlink.ocbm

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import zeno.gmccpa.pair.MfiRelay

class AppIap2AuthSessionTest {
    @Test
    fun `software signer authenticates over fragmented RFCOMM frames`() {
        val signer = FakeSigner()
        val session = AppIap2AuthSession(signer)
        session.begin()
        val phone = AppIap2Link()
        phone.begin()

        val certRequest = phone.message(0xAA00, byteArrayOf())
        assertTrue(session.receive(certRequest.copyOfRange(0, 4)).isEmpty())
        val certReplies = session.receive(certRequest.copyOfRange(4, certRequest.size))
        assertEquals(2, certReplies.size) // ACK then 0xAA01
        assertEquals(AppIap2AuthSession.Phase.CERT_SENT, session.phase)
        val observer = AppIap2Link()
        assertEquals(0xAA01, observer.feed(certReplies[1]).single().messageId)

        val digest = ByteArray(32) { it.toByte() }
        val challenge = byteArrayOf(0, 36, 0, 0) + digest
        val signReplies = session.receive(phone.message(0xAA02, challenge))
        assertEquals(2, signReplies.size)
        assertEquals(0xAA03, observer.feed(signReplies[1]).single().messageId)
        assertArrayEquals(digest, signer.lastDigest)
        assertEquals(AppIap2AuthSession.Phase.SIGN_SENT, session.phase)

        session.receive(phone.message(0xAA05, byteArrayOf()))
        assertEquals(AppIap2AuthSession.Phase.AUTHENTICATED, session.phase)
    }

    private class FakeSigner : MfiRelay {
        var lastDigest = byteArrayOf()
        override fun protocolMajor() = 3
        override fun copyCertificate() = byteArrayOf(0x30, 0)
        override fun createSignature(digest: ByteArray): ByteArray {
            lastDigest = digest.copyOf()
            return ByteArray(64) { 7 }
        }
    }
}
