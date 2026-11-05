package com.carlink.ocbm

import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import zeno.gmccpa.pair.MfiRelay

/** Local P-256 signer. Callers must provision identity files in private app storage. */
internal class SoftwareMfiSigner private constructor(
    private val key: java.security.PrivateKey,
    private val certificate: ByteArray,
) : MfiRelay {
    override fun protocolMajor(): Int = 3
    override fun copyCertificate(): ByteArray = certificate.copyOf()
    override fun createSignature(digest: ByteArray): ByteArray {
        require(digest.size == 32) { "MFi protocol 3 requires a 32-byte digest; got ${digest.size}" }
        return raw(Signature.getInstance("NONEwithECDSA").apply { initSign(key); update(digest) }.sign())
    }
    companion object {
        private val order = BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16)
        private fun bounded(file: File): ByteArray = file.inputStream().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                require(output.size() + n <= 16384) { "Invalid identity size" }
                output.write(buffer, 0, n)
            }
            val bytes = output.toByteArray()
            require(bytes.size in 1..16384) { "Invalid identity size" }
            bytes
        }
        fun load(directory: File): SoftwareMfiSigner {
            val bytes = bounded(File(directory, "identity.pk8"))
            val key = try { KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(bytes)) } finally { bytes.fill(0) }
            val cert = bounded(File(directory, "certificate.p7b"))
            val certificates = CertificateFactory.getInstance("X.509").generateCertificates(cert.inputStream())
            require(certificates.size == 1)
            val publicKey = certificates.single().publicKey as? ECPublicKey ?: error("Expected EC certificate")
            require(publicKey.params.order == order) { "Expected P-256 certificate" }
            val challenge = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val signature = Signature.getInstance("NONEwithECDSA").apply { initSign(key); update(challenge) }.sign()
            require(Signature.getInstance("NONEwithECDSA").run { initVerify(publicKey); update(challenge); verify(signature) }) { "Identity mismatch" }
            return SoftwareMfiSigner(key, cert)
        }
        internal fun raw(der: ByteArray): ByteArray {
            var p = 0
            fun next(): Int { require(p < der.size); return der[p++].toInt() and 255 }
            require(next() == 0x30 && next() == der.size - 2)
            val result = ByteArray(64)
            repeat(2) { index ->
                require(next() == 2)
                val length = next(); require(length in 1..33 && p + length <= der.size)
                var start = p; var size = length
                require(der[start].toInt() and 0x80 == 0)
                if (size > 1 && der[start] == 0.toByte()) { require(der[start + 1].toInt() and 0x80 != 0); start++; size-- }
                require(size <= 32)
                der.copyInto(result, index * 32 + 32 - size, start, start + size)
                p += length
            }
            require(p == der.size)
            return result
        }
    }
}
