package com.carlink.ocbm

import zeno.gmccpa.pair.MfiRelay

/** App-owned iAP2 authentication plane. All calls run on one RFCOMM worker. */
internal class AppIap2AuthSession(private val signer: MfiRelay) {
    enum class Phase { INITIAL, CERT_SENT, SIGN_SENT, AUTHENTICATED, FAILED }
    private val link = AppIap2Link()
    var phase = Phase.INITIAL
        private set

    fun begin(): ByteArray = link.begin()

    /** Returns link packets to write in order. A signer failure leaves the current phase intact. */
    fun receive(bytes: ByteArray): List<ByteArray> {
        val replies = mutableListOf<ByteArray>()
        for (packet in link.feed(bytes)) {
            if (packet.isSynAck) {
                replies += link.ack()
                continue
            }
            val id = packet.messageId ?: continue
            replies += link.ack()
            when (id) {
                0xAA00 -> if (phase == Phase.INITIAL) {
                    val certificate = signer.copyCertificate()
                    require(certificate.isNotEmpty() && certificate.size <= 16_384)
                    replies += link.message(0xAA01, group(certificate))
                    phase = Phase.CERT_SENT
                }
                0xAA02 -> if (phase == Phase.CERT_SENT) {
                    val digest = challenge(packet.body)
                    if (digest != null) {
                        val signature = signer.createSignature(digest)
                        val required = if (signer.protocolMajor() == 3) 64 else 128
                        require(signature.size == required) { "Invalid MFi signature length" }
                        replies += link.message(0xAA03, group(signature))
                        phase = Phase.SIGN_SENT
                    }
                }
                0xAA05 -> if (phase >= Phase.SIGN_SENT && phase != Phase.FAILED) phase = Phase.AUTHENTICATED
                0xAA04 -> phase = Phase.FAILED
            }
        }
        return replies
    }

    private fun challenge(body: ByteArray): ByteArray? {
        if (body.size < 4 || body[2] != 0.toByte() || body[3] != 0.toByte()) return null
        val length = ((body[0].toInt() and 255) shl 8) or (body[1].toInt() and 255)
        val expected = if (signer.protocolMajor() == 3) 32 else 20
        if (length != expected + 4 || body.size < length) return null
        return body.copyOfRange(4, length)
    }

    private fun group(value: ByteArray): ByteArray {
        val size = value.size + 4
        return byteArrayOf((size shr 8).toByte(), size.toByte(), 0, 0) + value
    }
}
