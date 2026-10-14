package com.carlink.av

import com.carlink.logging.ProbeLog
import com.carlink.ocbm.seam.MetadataSeam
import com.carlink.ocbm.seam.VoiceTag
import java.io.EOFException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Adapts gm_ccpa's native receiver localhost sockets to Carlink's existing renderer/audio inputs.
 *
 * These listeners are loopback-only. The receiver itself owns the vehicle-Wi-Fi network socket and
 * CarPlay protocol; this class only translates its already-decoded local media formats into the
 * framed messages consumed by VideoRenderer, MediaAudioPlayer, VoiceRouter and CarlinkManager.
 * The legacy OCBM USB lanes remain available and own their existing pipes as before.
 */
internal class CarPlaySocketMediaBridge(
    private val log: ProbeLog.Logger,
    private val output: Output,
    private val address: InetAddress = InetAddress.getByName("127.0.0.1"),
    private val ports: Ports = Ports(),
) {
    internal interface Output {
        /** Annex-B video AU/config; caller wraps it for VideoRenderer's length-framed pipe. */
        fun video(payload: ByteArray): Boolean

        /** ADTS AAC-LC frame with the ADTS header removed. */
        fun media(payload: ByteArray, sampleRate: Int, channels: Int): Boolean

        /** Wireless CarPlay voice is AAC-ELD; gm_ccpa's socket header omits the codec byte. */
        fun voice(payload: ByteArray, sampleRate: Int, channels: Int, audioType: Int): Boolean

        fun metadata(marker: Int, payload: ByteArray)
    }

    internal data class Ports(
        val video: Int = 9001,
        val media: Int = 9002,
        val voice: Int = 9003,
        val metadata: Int = 9004,
    )

    private val running = AtomicBoolean(false)
    private val servers = mutableMapOf<Int, ServerSocket>()
    private val clients = java.util.concurrent.CopyOnWriteArrayList<Socket>()
    private val threads = mutableListOf<Thread>()

    @Synchronized
    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true
        val requested = listOf(ports.video, ports.media, ports.voice, ports.metadata)
        try {
            requested.forEach { port ->
                servers[port] = ServerSocket(port, 2, address)
            }
        } catch (t: Throwable) {
            log.e("CarPlay localhost media listener bind failed: ${t.message}")
            closeResources()
            running.set(false)
            return false
        }

        val handlers = listOf<(InputStream) -> Unit>(
            ::readVideo,
            ::readMedia,
            ::readVoice,
            ::readMetadata,
        )
        requested.zip(handlers).forEach { (port, handler) ->
            Thread({ acceptLoop(port, handler) }, "cp-socket-$port").apply {
                isDaemon = true
                threads += this
                start()
            }
        }
        log.i("CarPlay receiver adapter listening on loopback ${requested.joinToString()}")
        return true
    }

    @Synchronized
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        closeResources()
        // Closing the listener and accepted sockets unblocks their read/accept calls. Do not join
        // here: OcbmClient invokes lane retirement on its ordered USB read thread.
        threads.clear()
        log.i("CarPlay receiver adapter stopped")
    }

    internal fun boundPort(requestedPort: Int): Int =
        checkNotNull(servers[requestedPort]) { "listener for requested port $requestedPort is not running" }.localPort

    private fun acceptLoop(port: Int, handler: (InputStream) -> Unit) {
        val server = servers[port] ?: return
        while (running.get()) {
            val socket = try {
                server.accept().apply {
                    tcpNoDelay = true
                }
            } catch (t: Throwable) {
                if (running.get()) log.w("localhost :$port accept failed: ${t.message}")
                break
            }
            clients += socket
            log.i("CarPlay receiver connected to localhost :$port")
            try {
                socket.use { handler(it.getInputStream()) }
            } catch (t: Throwable) {
                if (running.get()) log.w("localhost :$port stream ended: ${t.message}")
            } finally {
                clients -= socket
            }
        }
    }

    private fun readVideo(input: InputStream) {
        val header = ByteArray(4)
        while (running.get()) {
            if (!readFullyOrEof(input, header)) return
            val len = be32(header)
            requireLength(len, MAX_VIDEO_AU, "video")
            val payload = ByteArray(len)
            readFully(input, payload)
            output.video(payload)
        }
    }

    /** Receiver :9002 is a concatenation of ADTS frames, not length-prefixed socket writes. */
    private fun readMedia(input: InputStream) {
        while (running.get()) {
            val first = input.read()
            if (first < 0) return
            if (first != 0xFF) throw IllegalArgumentException("ADTS sync lost (0x${first.toString(16)})")
            val hdr = ByteArray(7)
            hdr[0] = first.toByte()
            readFully(input, hdr, 1, 6)
            if ((hdr[1].toInt() and 0xF6) != 0xF0) throw IllegalArgumentException("invalid ADTS sync/layer")
            val headerLen = if ((hdr[1].toInt() and 1) == 1) 7 else 9
            val profile = (hdr[2].toInt() ushr 6) and 0x03
            val rateIndex = (hdr[2].toInt() ushr 2) and 0x0F
            val channelCount = ((hdr[2].toInt() and 1) shl 2) or ((hdr[3].toInt() ushr 6) and 0x03)
            val frameLen = ((hdr[3].toInt() and 0x03) shl 11) or
                ((hdr[4].toInt() and 0xFF) shl 3) or ((hdr[5].toInt() ushr 5) and 0x07)
            require(frameLen in headerLen..MAX_MEDIA_FRAME) { "invalid ADTS frame length $frameLen" }
            if (headerLen == 9) readFully(input, ByteArray(2))
            val au = ByteArray(frameLen - headerLen)
            readFully(input, au)
            val rate = ADTS_SAMPLE_RATES.getOrNull(rateIndex)
                ?: throw IllegalArgumentException("unsupported ADTS sampling index $rateIndex")
            if (profile != 1) throw IllegalArgumentException("receiver media is not AAC-LC (profile=$profile)")
            require(channelCount in 1..2) { "unsupported ADTS channel configuration $channelCount" }
            output.media(au, rate, channelCount)
        }
    }

    /** gm_ccpa's receiver uses the protocol's 11-byte voice header; Carlink adds the codec byte. */
    private fun readVoice(input: InputStream) {
        val header = ByteArray(11)
        while (running.get()) {
            if (!readFullyOrEof(input, header)) return
            val rate = be32(header, 0)
            val channels = ((header[4].toInt() and 0xFF) shl 8) or (header[5].toInt() and 0xFF)
            val audioType = header[6].toInt() and 0xFF
            val len = be32(header, 7)
            require(rate in 8000..48000) { "invalid voice rate $rate" }
            require(channels in 1..2) { "invalid voice channel count $channels" }
            requireLength(len, MAX_VOICE_AU, "voice")
            val payload = ByteArray(len)
            readFully(input, payload)
            output.voice(payload, rate, channels, audioType)
        }
    }

    /** The receiver emits the same META-framed records as the existing OCBM MetadataSeam. */
    private fun readMetadata(input: InputStream) {
        val seam = MetadataSeam(log, output::metadata)
        val buf = ByteArray(16 * 1024)
        while (running.get()) {
            val n = input.read(buf)
            if (n < 0) return
            if (n > 0) seam.feed(buf.copyOf(n))
        }
    }

    @Synchronized
    private fun closeResources() {
        servers.values.forEach { runCatching { it.close() } }
        servers.clear()
        clients.forEach { runCatching { it.close() } }
        clients.clear()
    }

    private fun readFully(input: InputStream, out: ByteArray, offset: Int = 0, length: Int = out.size - offset) {
        var read = 0
        while (read < length) {
            val n = input.read(out, offset + read, length - read)
            if (n < 0) throw EOFException("short localhost media frame")
            if (n == 0) continue
            read += n
        }
    }

    private fun readFullyOrEof(input: InputStream, out: ByteArray): Boolean {
        val first = input.read()
        if (first < 0) return false
        out[0] = first.toByte()
        readFully(input, out, 1, out.size - 1)
        return true
    }

    private fun requireLength(length: Int, max: Int, label: String) {
        require(length in 1..max) { "invalid $label frame length $length" }
    }

    private fun be32(bytes: ByteArray, offset: Int = 0): Int = ByteBuffer.wrap(bytes, offset, 4)
        .order(ByteOrder.BIG_ENDIAN).int

    companion object {
        private const val MAX_VIDEO_AU = 16 * 1024 * 1024
        private const val MAX_MEDIA_FRAME = 1 shl 20
        private const val MAX_VOICE_AU = 1 shl 20
        private val ADTS_SAMPLE_RATES = intArrayOf(
            96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050,
            16000, 12000, 11025, 8000, 7350,
        )
    }
}
