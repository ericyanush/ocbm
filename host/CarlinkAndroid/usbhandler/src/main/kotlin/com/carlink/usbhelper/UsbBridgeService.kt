package com.carlink.usbhelper

import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import com.carlink.usbbridge.IUsbBridge
import com.carlink.usbbridge.IUsbBridgeCallback
import com.carlink.usbbridge.UsbBridgeConstants
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns the raw OCBM USB pipe under the fixed-handler UID. The app process receives bytes over AIDL;
 * it never calls UsbManager.openDevice and never requests a USB permission dialog.
 */
class UsbBridgeService : Service() {
    private val usb by lazy { getSystemService(Context.USB_SERVICE) as UsbManager }
    private val callbacks = ConcurrentHashMap.newKeySet<IUsbBridgeCallback>()
    private val running = AtomicBoolean(false)
    private val generation = AtomicLong(0)
    private val nextTransferId = AtomicLong(1)
    private val pendingWrites = ConcurrentHashMap<Long, ChunkAccumulator>()
    private val writeLock = Any()
    private var connection: UsbDeviceConnection? = null
    private var input: UsbEndpoint? = null
    private var output: UsbEndpoint? = null
    private var claimedInterface: UsbInterface? = null
    private var reader: Thread? = null

    // USB reading and app-side protocol processing are isolated. The bounded, ordered queue prevents
    // a slow Binder client from stalling bulk IN indefinitely or accumulating unbounded frame data.
    private val delivery = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(128),
    )

    private val binder = object : IUsbBridge.Stub() {
        override fun listAdapters(): Array<String> = usb.deviceList.values
            .filter(::isAdapter)
            .map { it.deviceName }
            .toTypedArray()

        override fun hasPermission(deviceName: String): Boolean =
            usb.deviceList[deviceName]?.let(usb::hasPermission) == true

        override fun open(deviceName: String): Boolean = openDevice(deviceName)

        override fun startReading() {
            startReader()
        }

        override fun close() {
            closeDevice("closed by app")
        }

        override fun registerCallback(callback: IUsbBridgeCallback) {
            callbacks += callback
            emitState(UsbBridgeConstants.STATE_DISCONNECTED, "USB helper ready")
        }

        override fun unregisterCallback(callback: IUsbBridgeCallback) {
            callbacks -= callback
        }

        override fun sendChunk(
            transferId: Long,
            chunkIndex: Int,
            chunkCount: Int,
            totalLength: Int,
            data: ByteArray,
        ): Boolean {
            if (chunkCount !in 1..MAX_CHUNKS || chunkIndex !in 0 until chunkCount ||
                totalLength !in 0..UsbBridgeConstants.MAX_MESSAGE_SIZE || data.size > UsbBridgeConstants.CHUNK_SIZE
            ) return false

            val accumulator = pendingWrites.getOrPut(transferId) { ChunkAccumulator(chunkCount, totalLength) }
            if (!accumulator.add(chunkIndex, data)) return false
            if (!accumulator.complete()) return true
            pendingWrites.remove(transferId)
            val bytes = accumulator.join() ?: return false
            val conn = connection ?: return false
            val endpoint = output ?: return false
            return synchronized(writeLock) {
                if (!running.get()) return@synchronized false
                var offset = 0
                while (offset < bytes.size) {
                    val count = minOf(WRITE_CHUNK, bytes.size - offset)
                    val chunk = bytes.copyOfRange(offset, offset + count)
                    val written = try {
                        conn.bulkTransfer(endpoint, chunk, count, WRITE_TIMEOUT_MS)
                    } catch (t: Throwable) {
                        Log.e(TAG, "bulk OUT threw", t)
                        -1
                    }
                    if (written <= 0) {
                        Log.e(TAG, "bulk OUT failed offset=$offset result=$written")
                        return@synchronized false
                    }
                    offset += written
                }
                true
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onUnbind(intent: Intent?): Boolean {
        closeDevice("last client unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        closeDevice("service destroyed")
        delivery.shutdownNow()
        super.onDestroy()
    }

    @Synchronized
    private fun openDevice(deviceName: String): Boolean {
        if (running.get()) return connection != null && usb.deviceList.containsKey(deviceName)
        val device = usb.deviceList[deviceName]
        if (device == null || !isAdapter(device)) {
            emitState(UsbBridgeConstants.STATE_ERROR, "OCBM adapter not found: $deviceName; devices=${describeDevices()}")
            return false
        }
        if (!usb.hasPermission(device)) {
            emitState(UsbBridgeConstants.STATE_ERROR, "silent USB grant unavailable for ${device.deviceName}")
            return false
        }
        val endpoints = findBulkEndpoints(device)
        if (endpoints == null) {
            emitState(UsbBridgeConstants.STATE_ERROR, "no bulk IN/OUT interface on ${device.deviceName}")
            return false
        }
        val conn = usb.openDevice(device)
        if (conn == null) {
            emitState(UsbBridgeConstants.STATE_ERROR, "helper openDevice failed despite permission")
            return false
        }
        if (!conn.claimInterface(endpoints.first, true)) {
            conn.close()
            emitState(UsbBridgeConstants.STATE_ERROR, "helper could not claim USB interface ${endpoints.first.id}")
            return false
        }
        connection = conn
        claimedInterface = endpoints.first
        input = endpoints.second
        output = endpoints.third
        generation.incrementAndGet()
        emitState(UsbBridgeConstants.STATE_CONNECTED, "${device.vendorId.toString(16)}:${device.productId.toString(16)} ${device.deviceName}")
        return true
    }

    @Synchronized
    private fun startReader() {
        if (!running.compareAndSet(false, true)) return
        val conn = connection ?: run {
            running.set(false)
            emitState(UsbBridgeConstants.STATE_ERROR, "startReading called before open")
            return
        }
        val endpoint = input ?: run {
            running.set(false)
            emitState(UsbBridgeConstants.STATE_ERROR, "bulk IN endpoint unavailable")
            return
        }
        val token = generation.get()
        reader = Thread({ readLoop(conn, endpoint, token) }, "OCBM-USB-Helper").apply {
            isDaemon = true
            start()
        }
    }

    private fun readLoop(conn: UsbDeviceConnection, endpoint: UsbEndpoint, token: Long) {
        val buffer = ByteArray(READ_BUFFER_SIZE)
        var rapidFailures = 0
        var reason = "USB read loop ended"
        try {
            while (running.get() && token == generation.get()) {
                val started = System.nanoTime()
                val count = try {
                    conn.bulkTransfer(endpoint, buffer, buffer.size, READ_TIMEOUT_MS)
                } catch (t: Throwable) {
                    Log.w(TAG, "bulk IN threw", t)
                    -1
                }
                if (!running.get() || token != generation.get()) break
                if (count > 0) {
                    rapidFailures = 0
                    val data = buffer.copyOf(count)
                    try {
                        delivery.execute {
                            if (running.get() && token == generation.get()) {
                                callbacks.forEach { callback ->
                                    try {
                                        callback.onInboundData(data)
                                    } catch (_: RemoteException) {
                                        callbacks -= callback
                                    }
                                }
                            }
                        }
                    } catch (_: RejectedExecutionException) {
                        reason = "USB-to-app delivery queue full"
                        break
                    }
                } else {
                    val elapsedMs = (System.nanoTime() - started) / 1_000_000
                    if (elapsedMs < RAPID_FAILURE_MS) {
                        rapidFailures++
                        if (rapidFailures >= RAPID_FAILURE_LIMIT) {
                            reason = "rapid USB bulk IN failures (device detached or endpoint stalled)"
                            break
                        }
                        Thread.sleep(RAPID_FAILURE_BACKOFF_MS)
                    } else {
                        // A normal USB read timeout means no bytes arrived yet, not that the link died.
                        rapidFailures = 0
                    }
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (t: Throwable) {
            reason = "USB read error: ${t.message ?: t.javaClass.simpleName}"
            Log.e(TAG, reason, t)
        } finally {
            if (token == generation.get() && running.get()) {
                emitState(UsbBridgeConstants.STATE_ERROR, reason)
                closeDevice(reason, token)
            }
        }
    }

    @Synchronized
    private fun closeDevice(reason: String, expectedGeneration: Long? = null) {
        if (expectedGeneration != null && expectedGeneration != generation.get()) return
        generation.incrementAndGet()
        val wasOpen = running.getAndSet(false) || connection != null
        reader?.interrupt()
        reader = null
        synchronized(writeLock) {
            val conn = connection
            val iface = claimedInterface
            if (conn != null && iface != null) runCatching { conn.releaseInterface(iface) }
            runCatching { conn?.close() }
            connection = null
            claimedInterface = null
            input = null
            output = null
        }
        pendingWrites.clear()
        delivery.queue.clear()
        if (wasOpen) emitState(UsbBridgeConstants.STATE_DISCONNECTED, reason)
    }

    private fun emitState(state: Int, detail: String) {
        callbacks.forEach { callback ->
            try {
                callback.onState(state, detail)
            } catch (_: RemoteException) {
                callbacks -= callback
            }
        }
    }

    private fun isAdapter(device: UsbDevice): Boolean =
        device.vendorId == VID_CARLINKIT && device.productId == PID_OCBM

    private fun describeDevices(): String = usb.deviceList.values.joinToString { device ->
        "${device.deviceName}:${device.vendorId.toString(16)}:${device.productId.toString(16)}"
    }.ifEmpty { "none" }

    private fun findBulkEndpoints(device: UsbDevice): Triple<UsbInterface, UsbEndpoint, UsbEndpoint>? {
        for (index in 0 until device.interfaceCount) {
            val iface = device.getInterface(index)
            var inEndpoint: UsbEndpoint? = null
            var outEndpoint: UsbEndpoint? = null
            for (endpointIndex in 0 until iface.endpointCount) {
                val endpoint = iface.getEndpoint(endpointIndex)
                if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (endpoint.direction == UsbConstants.USB_DIR_IN) inEndpoint = endpoint
                if (endpoint.direction == UsbConstants.USB_DIR_OUT) outEndpoint = endpoint
            }
            if (inEndpoint != null && outEndpoint != null) return Triple(iface, inEndpoint, outEndpoint)
        }
        return null
    }

    private class ChunkAccumulator(private val count: Int, private val totalLength: Int) {
        private val chunks = arrayOfNulls<ByteArray>(count)
        fun add(index: Int, data: ByteArray): Boolean {
            if (index !in chunks.indices || chunks[index] != null) return false
            chunks[index] = data.copyOf()
            return true
        }
        fun complete(): Boolean = chunks.all { it != null }
        fun join(): ByteArray? {
            if (!complete()) return null
            val result = ByteArray(totalLength)
            var offset = 0
            for (chunk in chunks) {
                val value = chunk ?: return null
                if (offset + value.size > result.size) return null
                value.copyInto(result, offset)
                offset += value.size
            }
            return result.takeIf { offset == totalLength }
        }
    }

    companion object {
        private const val TAG = "UsbBridgeService"
        private const val VID_CARLINKIT = 0x1314
        private const val PID_OCBM = 0x2d00
        private const val READ_BUFFER_SIZE = 16 * 1024
        private const val READ_TIMEOUT_MS = 250
        private const val WRITE_TIMEOUT_MS = 2_000
        private const val WRITE_CHUNK = 16 * 1024
        private const val RAPID_FAILURE_MS = 10
        private const val RAPID_FAILURE_LIMIT = 50
        private const val RAPID_FAILURE_BACKOFF_MS = 20L
        private const val MAX_CHUNKS = UsbBridgeConstants.MAX_MESSAGE_SIZE / UsbBridgeConstants.CHUNK_SIZE
    }
}
