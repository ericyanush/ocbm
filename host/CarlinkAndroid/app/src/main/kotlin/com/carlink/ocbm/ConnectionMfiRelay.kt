package com.carlink.ocbm

import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import zeno.gmccpa.pair.MfiRelay

/** Public certificates are stable for one adapter binding; signatures are challenge-specific. */
internal class ConnectionMfiRelay(
    private val delegate: MfiRelay,
    private val ensureCurrent: () -> Unit,
    private val log: (String) -> Unit = {},
) : MfiRelay {
    private val certificateLock = Any()
    private var certificate: ByteArray? = null

    override fun copyCertificate(): ByteArray = readCertificate { delegate.copyCertificate() }
    override fun copyCertificateFast(): ByteArray = readCertificate { delegate.copyCertificateFast() }

    private fun readCertificate(read: () -> ByteArray): ByteArray = synchronized(certificateLock) {
        ensureCurrent()
        val cached = certificate
        if (cached != null) {
            log("certificate cache hit (${cached.size}B)")
            return@synchronized cached.copyOf()
        }
        val bytes = read().copyOf()
        ensureCurrent()
        check(bytes.size in 1..2048) { "Invalid MFi certificate size" }
        val input = ByteArrayInputStream(bytes)
        val parsed = CertificateFactory.getInstance("X.509").generateCertificates(input)
        check(parsed.isNotEmpty() && input.available() == 0) { "Invalid MFi certificate encoding" }
        ensureCurrent()
        certificate = bytes
        log("validated and cached adapter certificate (${bytes.size}B)")
        bytes.copyOf()
    }

    override fun createSignature(digest: ByteArray): ByteArray = sign { delegate.createSignature(digest) }
    override fun createSignatureFast(digest: ByteArray): ByteArray = sign { delegate.createSignatureFast(digest) }

    private fun sign(operation: () -> ByteArray): ByteArray {
        ensureCurrent()
        val result = operation()
        ensureCurrent()
        return result
    }
}
