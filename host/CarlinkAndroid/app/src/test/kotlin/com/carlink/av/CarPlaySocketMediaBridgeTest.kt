package com.carlink.av

import com.carlink.logging.ProbeLog
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlaySocketMediaBridgeTest {
    @Test
    fun `receiver streams are adapted to existing consumer formats`() {
        val received = CountDownLatch(4)
        val video = arrayOfNulls<ByteArray>(1)
        val media = arrayOfNulls<ByteArray>(1)
        val voice = arrayOfNulls<ByteArray>(1)
        val meta = arrayOfNulls<ByteArray>(1)
        var mediaRate = 0
        var mediaChannels = 0
        var voiceRate = 0
        var voiceChannels = 0
        var voiceType = 0

        val reservations = List(4) { ServerSocket(0) }
        val ports = CarPlaySocketMediaBridge.Ports(
            reservations[0].localPort,
            reservations[1].localPort,
            reservations[2].localPort,
            reservations[3].localPort,
        )
        reservations.forEach { it.close() }
        val bridge = CarPlaySocketMediaBridge(
            log = ProbeLog.silent(),
            output = object : CarPlaySocketMediaBridge.Output {
                override fun video(payload: ByteArray): Boolean {
                    video[0] = payload
                    received.countDown()
                    return true
                }

                override fun media(payload: ByteArray, sampleRate: Int, channels: Int): Boolean {
                    media[0] = payload
                    mediaRate = sampleRate
                    mediaChannels = channels
                    received.countDown()
                    return true
                }

                override fun voice(payload: ByteArray, sampleRate: Int, channels: Int, audioType: Int): Boolean {
                    voice[0] = payload
                    voiceRate = sampleRate
                    voiceChannels = channels
                    voiceType = audioType
                    received.countDown()
                    return true
                }

                override fun metadata(marker: Int, payload: ByteArray) {
                    assertEquals(2, marker)
                    meta[0] = payload
                    received.countDown()
                }
            },
            ports = ports,
        )

        try {
            assertTrue(bridge.start())
            writeTo(bridge.boundPort(ports.video), framed(byteArrayOf(0, 0, 0, 1, 0x65, 0x11)))
            writeTo(bridge.boundPort(ports.media), adtsFrame(byteArrayOf(1, 2, 3)))
            writeTo(bridge.boundPort(ports.voice), voiceFrame(16_000, 1, 2, byteArrayOf(4, 5)))
            writeTo(bridge.boundPort(ports.metadata), metadataFrame(2, byteArrayOf(6, 7)))

            assertTrue("timed out waiting for adapted streams", received.await(3, TimeUnit.SECONDS))
            assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x65, 0x11), video[0])
            assertArrayEquals(byteArrayOf(1, 2, 3), media[0])
            assertEquals(48_000, mediaRate)
            assertEquals(2, mediaChannels)
            assertArrayEquals(byteArrayOf(4, 5), voice[0])
            assertEquals(16_000, voiceRate)
            assertEquals(1, voiceChannels)
            assertEquals(2, voiceType)
            assertArrayEquals(byteArrayOf(6, 7), meta[0])
        } finally {
            bridge.stop()
        }
    }

    private fun writeTo(port: Int, bytes: ByteArray) {
        Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
            socket.getOutputStream().write(bytes)
            socket.shutdownOutput()
        }
    }

    private fun framed(payload: ByteArray): ByteArray = ByteBuffer.allocate(4 + payload.size)
        .order(ByteOrder.BIG_ENDIAN)
        .putInt(payload.size)
        .put(payload)
        .array()

    private fun adtsFrame(au: ByteArray): ByteArray {
        val len = 7 + au.size
        return byteArrayOf(
            0xFF.toByte(), 0xF1.toByte(), 0x4C, 0x80.toByte(),
            (len ushr 3).toByte(), (((len and 7) shl 5) or 0x1F).toByte(), 0xFC.toByte(),
        ) + au
    }

    private fun voiceFrame(rate: Int, channels: Int, audioType: Int, au: ByteArray): ByteArray =
        ByteBuffer.allocate(11 + au.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(rate)
            .putShort(channels.toShort())
            .put(audioType.toByte())
            .putInt(au.size)
            .put(au)
            .array()

    private fun metadataFrame(marker: Int, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(9 + payload.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put("META".toByteArray(Charsets.US_ASCII))
            .putInt(1 + payload.size)
            .put(marker.toByte())
            .put(payload)
            .array()
}
