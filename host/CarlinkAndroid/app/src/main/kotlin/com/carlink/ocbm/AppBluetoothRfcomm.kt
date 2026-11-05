package com.carlink.ocbm

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import java.io.Closeable
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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
    fun dial(address: String, service: UUID = DIPLAY_PHONE_SERVICE, timeoutMs: Long = 15_000): Link {
        require(timeoutMs in 1_000..60_000)
        check(readiness().available) { "Android Bluetooth unavailable" }
        val device = adapter().getRemoteDevice(address)
        check(device.bondState == BluetoothDevice.BOND_BONDED) { "Phone is not paired with Android Bluetooth" }
        val socket = device.createRfcommSocketToServiceRecord(service)
        val timeout = Executors.newSingleThreadScheduledExecutor()
        val closeTask = timeout.schedule({ runCatching { socket.close() } }, timeoutMs, TimeUnit.MILLISECONDS)
        return try {
            socket.connect()
            Link(socket)
        } catch (error: Exception) {
            runCatching { socket.close() }
            throw IOException(
                "RFCOMM connect to phone service $service failed: " +
                    "${error.javaClass.simpleName}: ${error.message ?: "no detail"}",
                error,
            )
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
        onDiagnostic: (String) -> Unit = {},
    ): String {
        val device = adapter().getRemoteDevice(address)
        check(device.bondState == BluetoothDevice.BOND_BONDED) { "Phone is not paired with Android Bluetooth" }
        val services = discoverServices(device, onDiagnostic)
        // DiPlay/carlink-fenyx use the iPhone's ...cafe record. An OCBM phone SDP capture also
        // found a "Wireless iAP v2" record at 02030302...; try it if the first dial fails.
        val candidates = listOf(DIPLAY_PHONE_SERVICE, IAP2_V2_PHONE_SERVICE).sortedBy {
            if (it in services) 0 else 1
        }
        var lastConnectError: IOException? = null
        for (service in candidates) {
            onDiagnostic("RFCOMM dialing service=$service advertised=${service in services}")
            val socket = try {
                dial(address, service)
            } catch (error: IOException) {
                lastConnectError = error
                onDiagnostic("RFCOMM dial failed service=$service cause=${error.message}")
                continue
            }
            onDiagnostic("RFCOMM connected service=$service; starting iAP2 authentication")
            return socket.use { link -> authenticate(link, signer, onPhase) }
        }
        throw IOException("RFCOMM could not connect using either phone service", lastConnectError)
    }

    private fun authenticate(
        socket: Link,
        signer: SoftwareMfiSigner,
        onPhase: (AppIap2AuthSession.Phase) -> Unit,
    ): String {
        val session = AppIap2AuthSession(signer)
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

    /** A bounded SDP query before the dial, so the query and RFCOMM connect cannot race. */
    private fun discoverServices(device: BluetoothDevice, report: (String) -> Unit): Set<UUID> {
        val cached = runCatching { device.uuids?.map { it.uuid }?.toSet().orEmpty() }.getOrDefault(emptySet())
        report("SDP cached count=${cached.size} diPlay=${DIPLAY_PHONE_SERVICE in cached} iap2v2=${IAP2_V2_PHONE_SERVICE in cached}")
        val latch = CountDownLatch(1)
        val discovered = AtomicReference<Set<UUID>?>(null)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val peer = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                if (peer?.address != device.address) return
                val values = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID, ParcelUuid::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID)
                }
                discovered.set(values?.filterIsInstance<ParcelUuid>()?.map { it.uuid }?.toSet())
                latch.countDown()
            }
        }
        val app = context.applicationContext
        ContextCompat.registerReceiver(
            app, receiver, IntentFilter(BluetoothDevice.ACTION_UUID), ContextCompat.RECEIVER_EXPORTED,
        )
        try {
            val started = runCatching { device.fetchUuidsWithSdp() }
            report("SDP requested=${started.getOrDefault(false)} error=${started.exceptionOrNull()?.message ?: "none"}")
            if (started.getOrDefault(false)) {
                val answered = latch.await(12, TimeUnit.SECONDS)
                report("SDP response=$answered")
            }
        } finally {
            app.unregisterReceiver(receiver)
        }
        val reported = discovered.get()
            ?: runCatching { device.uuids?.map { it.uuid }?.toSet().orEmpty() }.getOrDefault(cached)
        // Android may supply cached UUIDs after an SDP error, even when ACTION_UUID arrives.
        report("SDP result count=${reported.size} diPlay=${DIPLAY_PHONE_SERVICE in reported} iap2v2=${IAP2_V2_PHONE_SERVICE in reported}")
        return reported
    }

    class Link(private val socket: BluetoothSocket) : Closeable {
        val peerAddress: String get() = socket.remoteDevice.address
        val input get() = socket.inputStream
        val output get() = socket.outputStream
        override fun close() = socket.close()
    }

    companion object {
        private val DIPLAY_PHONE_SERVICE = UUID.fromString("00000000-deca-fade-deca-deafdecacafe")
        private val IAP2_V2_PHONE_SERVICE = UUID.fromString("02030302-1d19-415f-86f2-22a2106a0a77")
    }
}
