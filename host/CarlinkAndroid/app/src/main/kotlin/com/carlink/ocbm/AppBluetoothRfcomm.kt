package com.carlink.ocbm

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import java.io.Closeable
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** App-owned Bluetooth Classic transport. Protocol processing is deliberately separate. */
class AppBluetoothRfcomm(private val context: Context) {
    data class Readiness(val available: Boolean, val detail: String, val pairedPhones: List<Pair<String, String>>)

    private fun adapter(): BluetoothAdapter =
        context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw IOException("Android Bluetooth service unavailable")

    fun readiness(): Readiness {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED) return Readiness(false, "Bluetooth permission needed", emptyList())
        val radio = adapter()
        if (!radio.isEnabled) return Readiness(false, "Bluetooth radio disabled", emptyList())
        val paired = radio.bondedDevices.orEmpty().filter { it.bondState == BluetoothDevice.BOND_BONDED }
            .map { it.address to (it.name ?: it.address) }
        return Readiness(true, "Android Bluetooth ready; ${paired.size} paired devices", paired)
    }

    /** Call on a worker thread. Closing [Link] interrupts a blocked read on disconnect. */
    fun dial(address: String, timeoutMs: Long = 15_000): Link {
        require(timeoutMs in 1_000..60_000)
        check(readiness().available) { "Android Bluetooth unavailable" }
        val device = adapter().getRemoteDevice(address)
        check(device.bondState == BluetoothDevice.BOND_BONDED) { "Phone is not paired with Android Bluetooth" }
        val socket = device.createRfcommSocketToServiceRecord(IAP2_SERVICE)
        val timeout = Executors.newSingleThreadScheduledExecutor()
        val closeTask = timeout.schedule({ runCatching { socket.close() } }, timeoutMs, TimeUnit.MILLISECONDS)
        return try {
            socket.connect()
            Link(socket)
        } catch (error: Exception) {
            runCatching { socket.close() }
            throw IOException("RFCOMM connect failed", error)
        } finally {
            closeTask.cancel(false)
            timeout.shutdownNow()
        }
    }

    /** Minimal on-device proof that the iPhone accepts the imported signer over app-owned RFCOMM. */
    internal fun probeAuthentication(
        address: String,
        signer: SoftwareMfiSigner,
        onPhase: (AppIap2AuthSession.Phase) -> Unit = {},
    ): String {
        val session = AppIap2AuthSession(signer)
        dial(address).use { socket ->
            val deadline = Executors.newSingleThreadScheduledExecutor()
            val closeTask = deadline.schedule({ runCatching { socket.close() } }, 30, TimeUnit.SECONDS)
            try {
                socket.output.write(session.begin())
                socket.output.flush()
                val buffer = ByteArray(4096)
                var lastPhase = session.phase
                while (session.phase != AppIap2AuthSession.Phase.AUTHENTICATED &&
                    session.phase != AppIap2AuthSession.Phase.FAILED) {
                    val count = socket.input.read(buffer)
                    if (count < 0) throw IOException("RFCOMM closed at ${session.phase}")
                    for (packet in session.receive(buffer.copyOf(count))) socket.output.write(packet)
                    socket.output.flush()
                    if (session.phase != lastPhase) {
                        lastPhase = session.phase
                        onPhase(lastPhase)
                    }
                }
                return session.phase.name
            } finally {
                closeTask.cancel(false)
                deadline.shutdownNow()
            }
        }
    }

    class Link(private val socket: BluetoothSocket) : Closeable {
        val peerAddress: String get() = socket.remoteDevice.address
        val input get() = socket.inputStream
        val output get() = socket.outputStream
        override fun close() = socket.close()
    }

    companion object {
        private val IAP2_SERVICE = UUID.fromString("00000000-deca-fade-deca-deafdecacafe")
    }
}
