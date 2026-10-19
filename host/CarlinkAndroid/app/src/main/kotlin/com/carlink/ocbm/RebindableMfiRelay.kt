package com.carlink.ocbm

import java.util.concurrent.atomic.AtomicReference
import zeno.gmccpa.pair.MfiRelay

/**
 * Stable relay object for the native CarPlay receiver, whose lifetime can exceed a USB/OCBM
 * connection. Its delegate is replaced only after a new OCBM client has subscribed successfully.
 * Calls that race a replacement are rejected rather than returning a result from a retired link.
 */
internal class RebindableMfiRelay : MfiRelay {
    private data class Binding(
        val generation: Long,
        val delegate: MfiRelay,
    )

    private val binding = AtomicReference<Binding?>(null)

    fun bind(generation: Long, delegate: MfiRelay) {
        binding.set(Binding(generation, delegate))
    }

    fun clear(generation: Long) {
        while (true) {
            val current = binding.get() ?: return
            if (current.generation != generation) return
            if (binding.compareAndSet(current, null)) return
        }
    }

    fun clear() {
        binding.set(null)
    }

    override fun copyCertificate(): ByteArray =
        invoke("certificate") { it.copyCertificate() }

    override fun createSignature(digest: ByteArray): ByteArray =
        invoke("signature") { it.createSignature(digest) }

    override fun copyCertificateFast(): ByteArray =
        invoke("fast certificate") { it.copyCertificateFast() }

    override fun createSignatureFast(digest: ByteArray): ByteArray =
        invoke("fast signature") { it.createSignatureFast(digest) }

    private inline fun invoke(
        operation: String,
        call: (MfiRelay) -> ByteArray,
    ): ByteArray {
        val current = binding.get()
            ?: throw IllegalStateException("MFi adapter unavailable during $operation request")
        val result = call(current.delegate)
        if (binding.get() !== current) {
            throw IllegalStateException(
                "MFi adapter session changed during $operation request " +
                    "(generation=${current.generation})",
            )
        }
        return result
    }
}
