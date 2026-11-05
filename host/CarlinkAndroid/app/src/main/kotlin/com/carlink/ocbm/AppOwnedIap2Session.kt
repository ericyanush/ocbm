package com.carlink.ocbm

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import zeno.gmccpa.pair.NativeCore
import zeno.gmccpa.CarPlayRx

/** One phone's app-owned Bluetooth control link. The receiver owns the Wi-Fi media sockets. */
internal class AppOwnedIap2Session(
    private val context: Context,
    private val phoneAddress: String,
    private val signer: SoftwareMfiSigner,
    private val receiver: CarPlayRx,
    private val endpoint: JSONObject,
    private val log: (String) -> Unit,
    private val onStartSessionSent: () -> Unit,
) : Closeable {
    private val bluetooth = AppBluetoothRfcomm(context)
    private val pending = AtomicReference<AppBluetoothRfcomm.Link?>()
    private val active = AtomicReference<AppBluetoothRfcomm.AuthenticatedLink?>()
    @Volatile private var closed = false
    @Volatile var startSessionSent = false
        private set

    fun run() {
        val localMac = bluetooth.localAddress()
        check(localMac != "02:00:00:00:00:00") { "Android hides its Bluetooth address; cannot identify accessory" }
        log("local Bluetooth identity=$localMac; phone selected")
        val connection = bluetooth.connectAuthenticated(
            phoneAddress, signer,
            onPhase = { log("iAP2 auth phase=$it") },
            onDiagnostic = log,
            onLinkOpened = { link ->
                pending.set(link)
                if (closed) link.close()
            },
        )
        active.set(connection)
        pending.set(null)
        if (closed) { active.getAndSet(null)?.close(); return }
        try {
            val session = connection.session
            val socket = connection.socket
            var identified = false
            var wirelessAvailable = false
            val identification = NativeCore.wirelessIdentifyBody("CarLink", localMac)
            receiver.setActiveBluetoothPeer(phoneAddress)
            fun send(id: Int, body: ByteArray) {
                if (closed) throw IOException("app Bluetooth session closed")
                socket.output.write(session.message(id, body))
                socket.output.flush()
                log("TX 0x${id.toString(16)} body=${body.size}B")
            }
            fun processMessages() {
                for (message in session.drainMessages()) {
                    log("RX 0x${message.id.toString(16)} body=${message.body.size}B")
                    when (message.id) {
                        0x1D00 -> send(0x1D01, identification)
                        0x1D02 -> { identified = true; log("iAP2 identification accepted") }
                        0x1D03 -> throw IOException("iAP2 identification rejected")
                        0x5702 -> send(0x5703, AppOwnedIap2Bodies.wifiConfiguration(endpoint))
                        0x4300 -> {
                            wirelessAvailable = AppOwnedIap2Bodies.wirelessAvailable(message.body)
                            log("CarPlayAvailability wireless=$wirelessAvailable")
                        }
                    }
                    if (identified && wirelessAvailable && !startSessionSent) {
                        // Authorize this exact Bluetooth peer on this bridge before asking iOS to dial back.
                        receiver.authorizeStartSession(phoneAddress, endpoint.getString("interface"))
                        send(0x4301, AppOwnedIap2Bodies.startSession(endpoint))
                        startSessionSent = true
                        onStartSessionSent()
                        log("StartSession sent; waiting for phone control connection")
                    }
                }
            }
            // Authentication may have received 0x1D00 in the same read as 0xAA05.
            processMessages()
            val buffer = ByteArray(4096)
            while (!closed) {
                val count = socket.input.read(buffer)
                if (count < 0) throw IOException("iAP2 phone RFCOMM link closed")
                for (reply in session.receive(buffer.copyOf(count))) socket.output.write(reply)
                socket.output.flush()
                processMessages()
            }
        } finally {
            active.getAndSet(null)?.close()
            // An iPhone may close RFCOMM after 0x4301 but before it dials the Wi-Fi listener.
            // Let the manager retain this authorization through the receiver's 40-second deadline.
            if (!startSessionSent) {
                receiver.revokeStartSession()
                receiver.setHandoffTarget(null, false)
            }
        }
    }

    override fun close() {
        closed = true
        pending.getAndSet(null)?.close()
        active.getAndSet(null)?.close()
    }
}

/** The same 0x5703 and 0x4301 TLV fields used by the adapter's proven builders. */
internal object AppOwnedIap2Bodies {
    private fun tlv(id: Int, bytes: ByteArray): ByteArray {
        val size = bytes.size + 4
        require(size <= 65535)
        return byteArrayOf((size shr 8).toByte(), size.toByte(), (id shr 8).toByte(), id.toByte()) + bytes
    }
    private fun string(id: Int, value: String): ByteArray {
        require('\u0000' !in value) { "Interior NUL in iAP2 string parameter $id" }
        return tlv(id, value.toByteArray(Charsets.UTF_8) + 0.toByte())
    }
    private fun byte(id: Int, value: Int): ByteArray {
        require(value in 0..255) { "Invalid iAP2 byte parameter $id" }
        return tlv(id, byteArrayOf(value.toByte()))
    }
    private fun sequence(vararg values: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        values.forEach(::write)
    }.toByteArray()

    fun wifiConfiguration(endpoint: JSONObject): ByteArray = sequence(
        string(1, endpoint.getString("ssid")),
        string(2, endpoint.getString("passphrase")),
        // 0x5703 security enum: 2 means WPA2/WPA3 Personal; distinct from 0x4301's enum.
        byte(3, 2),
        byte(4, endpoint.getInt("channel")),
    )

    fun startSession(endpoint: JSONObject): ByteArray {
        val addresses = endpoint.getJSONArray("addresses")
        require(addresses.length() in 1..16)
        val wireless = sequence(
            string(0, endpoint.getString("ssid")), string(1, endpoint.getString("passphrase")),
            byte(2, endpoint.getInt("channel")),
            *Array(addresses.length()) { string(3, addresses.getString(it).substringBefore('%')) },
            byte(4, endpoint.getInt("security")),
        )
        val port = endpoint.getInt("port")
        require(port in 1..65535)
        return sequence(
            tlv(1, wireless),
            tlv(2, byteArrayOf(0, 0, (port shr 8).toByte(), port.toByte())),
            string(3, endpoint.getString("deviceId")),
            string(4, endpoint.getString("publicKey")),
            string(5, endpoint.getString("sourceVersion")),
        )
    }

    fun wirelessAvailable(body: ByteArray): Boolean {
        fun walk(bytes: ByteArray, block: (Int, ByteArray) -> Unit) {
            var offset = 0
            while (offset < bytes.size) {
                require(offset + 4 <= bytes.size) { "Truncated iAP2 TLV" }
                val size = ((bytes[offset].toInt() and 255) shl 8) or (bytes[offset + 1].toInt() and 255)
                val id = ((bytes[offset + 2].toInt() and 255) shl 8) or (bytes[offset + 3].toInt() and 255)
                require(size >= 4 && offset + size <= bytes.size) { "Invalid iAP2 TLV length" }
                block(id, bytes.copyOfRange(offset + 4, offset + size))
                offset += size
            }
        }
        var available: Boolean? = null
        walk(body) { id, group ->
            if (id == 1) walk(group) { sub, value ->
                if (sub == 0) {
                    require(available == null && value.size == 1 && value[0].toInt() in 0..1)
                    available = value[0].toInt() == 1
                }
            }
        }
        return available == true
    }
}
