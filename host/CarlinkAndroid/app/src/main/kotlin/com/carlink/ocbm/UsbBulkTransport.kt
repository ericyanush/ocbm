package com.carlink.ocbm

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import com.carlink.logging.ProbeLog
import com.carlink.usbbridge.IUsbBridge
import com.carlink.usbbridge.IUsbBridgeCallback
import com.carlink.usbbridge.UsbBridgeConstants
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Raw OCBM byte transport backed by the separate `android.car.usb.handler` UID.
 *
 * GM grants USB access to that fixed-handler package, not to this app's UID. All UsbManager calls,
 * interface claims and endpoint I/O therefore live in [UsbBridgeService]; this class only moves
 * bounded byte chunks over the private, signature-protected AIDL contract.
 */
class UsbBulkTransport(
    context: Context,
    private val log: ProbeLog.Logger,
) : RawBulkTransport {
    companion object {
        const val VID_CARLINKIT = 0x1314
        const val PID_OCBM = 0x2d00
    }

    private val appContext = context.applicationContext
    private val connected = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private val transferIds = AtomicLong(1)
    private val writeLock = Any()

    @Volatile private var bridge: IUsbBridge? = null

    @Volatile private var serviceConnection: ServiceConnection? = null

    @Volatile private var readHandler: ((ByteArray, Int) -> Unit)? = null

    @Volatile private var deadHandler: (() -> Unit)? = null

    @Volatile var lastError: String? = null
        private set

    override fun setReadHandler(handler: (ByteArray, Int) -> Unit) {
        readHandler = handler
    }

    override fun setDeadHandler(handler: () -> Unit) {
        deadHandler = handler
    }

    private val callback =
        object : IUsbBridgeCallback.Stub() {
            override fun onState(
                state: Int,
                detail: String?,
            ) {
                val message = detail ?: "USB helper state=$state"
                log.i("[USB_HELPER] state=$state detail=$message")
                if (state == UsbBridgeConstants.STATE_ERROR) {
                    lastError = message
                    signalDead()
                } else if (state == UsbBridgeConstants.STATE_DISCONNECTED && running.get()) {
                    lastError = message
                    signalDead()
                }
            }

            override fun onInboundData(data: ByteArray) {
                if (!running.get()) return
                readHandler?.invoke(data, data.size)
            }
        }

    /** Bind on a worker thread; binding callbacks are delivered on the main looper. */
    fun connect(timeoutMs: Long = 5_000): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper()) { "USB helper bind must not block the main thread" }
        if (bridge != null) return true
        lastError = null
        val ready = CountDownLatch(1)
        val intent =
            Intent().setComponent(
                ComponentName(UsbBridgeConstants.BRIDGE_PACKAGE, UsbBridgeConstants.BRIDGE_SERVICE_CLASS),
            )
        if (appContext.packageManager.resolveService(intent, 0) == null) {
            lastError = "USB helper service is not installed for this AAOS user"
            log.e("[USB_HELPER] $lastError (${intent.component})")
            return false
        }
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(
                    name: ComponentName,
                    service: IBinder,
                ) {
                    val remote = IUsbBridge.Stub.asInterface(service)
                    bridge = remote
                    try {
                        remote.registerCallback(callback)
                    } catch (e: RemoteException) {
                        lastError = "USB helper callback registration failed: ${e.message}"
                        log.e("[USB_HELPER] $lastError")
                    } finally {
                        ready.countDown()
                    }
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    bridge = null
                    lastError = "USB helper service disconnected"
                    signalDead()
                    ready.countDown()
                }

                override fun onBindingDied(name: ComponentName) {
                    bridge = null
                    lastError = "USB helper service binding died"
                    signalDead()
                    ready.countDown()
                }

                override fun onNullBinding(name: ComponentName) {
                    lastError = "USB helper returned a null service binding"
                    ready.countDown()
                }
            }
        serviceConnection = connection
        return bindAndAwait(intent, connection, ready, timeoutMs)
    }

    private fun bindAndAwait(
        intent: Intent,
        connection: ServiceConnection,
        ready: CountDownLatch,
        timeoutMs: Long,
    ): Boolean =
        try {
            val bound = appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            if (!bound) {
                lastError = "bindService returned false; helper is unavailable to this AAOS user"
                log.e("[USB_HELPER] $lastError")
                serviceConnection = null
                false
            } else if (!ready.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                lastError = "timed out binding USB helper service"
                log.e("[USB_HELPER] $lastError")
                false
            } else if (bridge == null) {
                if (lastError.isNullOrBlank()) lastError = "USB helper binder unavailable"
                false
            } else {
                log.i("[USB_HELPER] bound to ${intent.component}")
                true
            }
        } catch (t: InterruptedException) {
            Thread.currentThread().interrupt()
            lastError = "interrupted while binding USB helper"
            log.e("[USB_HELPER] $lastError")
            false
        } catch (t: SecurityException) {
            lastError = "USB helper binding denied: ${t.message ?: t.javaClass.simpleName}"
            log.e("[USB_HELPER] $lastError")
            false
        } catch (t: IllegalArgumentException) {
            lastError = "USB helper bind failed: ${t.message ?: t.javaClass.simpleName}"
            log.e("[USB_HELPER] $lastError")
            false
        }

    /** Adapter names and permission status are queried by the helper UID. */
    fun findQuiet(): String? =
        try {
            bridge?.listAdapters()?.firstOrNull()
        } catch (t: Throwable) {
            lastError = "USB helper device scan failed: ${t.message ?: t.javaClass.simpleName}"
            log.e("[USB_HELPER] $lastError")
            null
        }

    fun hasPermission(deviceName: String): Boolean =
        try {
            bridge?.hasPermission(deviceName) == true
        } catch (t: Throwable) {
            lastError = "USB helper permission check failed: ${t.message ?: t.javaClass.simpleName}"
            log.e("[USB_HELPER] $lastError")
            false
        }

    fun open(deviceName: String): Boolean {
        val remote =
            bridge ?: run {
                lastError = "USB helper is not bound"
                return false
            }
        return try {
            connected.set(remote.open(deviceName))
            if (!connected.get() && lastError.isNullOrBlank()) {
                lastError = "USB helper could not claim the adapter; check its USB grant and interface"
            }
            connected.get()
        } catch (t: Throwable) {
            lastError = "USB helper open failed: ${t.message ?: t.javaClass.simpleName}"
            log.e("[USB_HELPER] $lastError")
            false
        }
    }

    override fun start() {
        if (!connected.get() || !running.compareAndSet(false, true)) return
        try {
            val remote = bridge ?: error("helper binder disconnected")
            remote.startReading()
            log.i("[USB_HELPER] raw OCBM read stream started")
        } catch (t: Throwable) {
            running.set(false)
            lastError = "USB helper could not start reads: ${t.message ?: t.javaClass.simpleName}"
            signalDead()
        }
    }

    override fun writeBulk(data: ByteArray): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            log.e("[USB_MAIN_GUARD] rejected synchronous helper write on main thread")
            return false
        }
        val remote = bridge ?: return false
        if (!connected.get() || data.size > UsbBridgeConstants.MAX_MESSAGE_SIZE) return false
        synchronized(writeLock) {
            val id = transferIds.getAndIncrement()
            val chunks = maxOf(1, (data.size + UsbBridgeConstants.CHUNK_SIZE - 1) / UsbBridgeConstants.CHUNK_SIZE)
            for (index in 0 until chunks) {
                val from = index * UsbBridgeConstants.CHUNK_SIZE
                val to = minOf(data.size, from + UsbBridgeConstants.CHUNK_SIZE)
                val bytes = data.copyOfRange(from, to)
                val accepted =
                    try {
                        remote.sendChunk(id, index, chunks, data.size, bytes)
                    } catch (t: Throwable) {
                        lastError = "USB helper write failed: ${t.message ?: t.javaClass.simpleName}"
                        log.e("[USB_HELPER] $lastError")
                        false
                    }
                if (!accepted) {
                    lastError = lastError ?: "USB helper rejected OUT transfer $id chunk $index/$chunks"
                    return false
                }
            }
        }
        return true
    }

    override fun stop() {
        running.set(false)
        connected.set(false)
        val remote = bridge
        bridge = null
        if (remote != null) {
            runCatching { remote.unregisterCallback(callback) }
            runCatching { remote.close() }
        }
        serviceConnection?.let { runCatching { appContext.unbindService(it) } }
        serviceConnection = null
        log.i("[USB_HELPER] transport stopped and helper unbound")
    }

    private fun signalDead() {
        if (!running.compareAndSet(true, false)) return
        Thread({ runCatching { deadHandler?.invoke() } }, "ocbm-helper-dead").apply {
            isDaemon = true
            start()
        }
    }
}
