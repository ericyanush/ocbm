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
import android.os.SystemClock
import android.util.Log
import com.carlink.usbbridge.IUsbBridge
import com.carlink.usbbridge.IUsbBridgeCallback
import com.carlink.usbbridge.UsbBridgeConstants
import com.carlink.usbbridge.UsbBulkWriteLoop
import com.carlink.usbbridge.UsbWriteAssembler
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns the raw OCBM USB pipe under the fixed-handler UID. The app process receives bytes over AIDL;
 * it never calls UsbManager.openDevice and never requests a USB permission dialog. The service is
 * normally bound by the main app for a transport session; discovery/open is initiated through the
 * expanded AIDL interface. Like the original helper, this also supports a sticky started-service
 * path for system starts, while retaining the OCBM byte-stream transport and diagnostics.
 */
class UsbBridgeService : Service() {
    private val usb by lazy { getSystemService(Context.USB_SERVICE) as UsbManager }
    private val callbacks = ConcurrentHashMap.newKeySet<IUsbBridgeCallback>()
    private val running = AtomicBoolean(false)
    private val generation = AtomicLong(0)
    private val nextTransferId = AtomicLong(1)
    private val pendingWrites = HashMap<Long, UsbWriteAssembler>()
    private val pendingWritesLock = Any()
    private val writeLock = Any()
    private var connection: UsbDeviceConnection? = null
    private var input: UsbEndpoint? = null
    private var output: UsbEndpoint? = null
    private var claimedInterface: UsbInterface? = null
    private var openedDeviceName: String? = null
    private var reader: Thread? = null
    private val deliveryHighWaterLogged = AtomicBoolean(false)

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
        override fun listAdapters(): Array<String> {
            val devices = usb.deviceList.values.toList()
            val adapters = devices.filter(::isAdapter)
            val detail = "USB scan: ${describeDevicesWithPermissions(devices)}; OCBM matches=${adapters.joinToString { it.deviceName }.ifEmpty { "none" }}"
            Log.i(TAG, detail)
            emitState(UsbBridgeConstants.STATE_DIAGNOSTIC, detail)
            return adapters.map { it.deviceName }.toTypedArray()
        }

        override fun hasPermission(deviceName: String): Boolean {
            val device = usb.deviceList[deviceName]
            val granted = device?.let(usb::hasPermission) == true
            val detail = "USB permission check: path=$deviceName present=${device != null} grant=$granted${device?.let { " descriptor=${describeDevice(it)}" } ?: ""}"
            Log.i(TAG, detail)
            emitState(UsbBridgeConstants.STATE_DIAGNOSTIC, detail)
            return granted
        }

        override fun openFirstAdapter(): String = findAndOpenAdapter()

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
            val detail = "USB helper callback registered; inventory=${describeDevicesWithPermissions()}"
            Log.i(TAG, detail)
            emitState(UsbBridgeConstants.STATE_DIAGNOSTIC, detail)
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
        ): String {
            val expectedChunks = maxOf(1, (totalLength + UsbBridgeConstants.CHUNK_SIZE - 1) / UsbBridgeConstants.CHUNK_SIZE)
            if (chunkCount !in 1..MAX_CHUNKS || chunkIndex !in 0 until chunkCount ||
                totalLength !in 0..UsbBridgeConstants.MAX_MESSAGE_SIZE || data.size > UsbBridgeConstants.CHUNK_SIZE ||
                chunkCount != expectedChunks
            ) return "invalid chunk geometry transfer=$transferId index=$chunkIndex count=$chunkCount total=$totalLength data=${data.size}"

            val bytes = synchronized(pendingWritesLock) {
                val now = SystemClock.elapsedRealtime()
                val expired = pendingWrites.filterValues { now - it.createdAtMs > UsbBridgeConstants.PENDING_WRITE_TTL_MS }.keys
                expired.forEach { pendingWrites.remove(it) }
                if (expired.isNotEmpty()) Log.w(TAG, "expired ${expired.size} incomplete OUT transfer(s)")

                var accumulator = pendingWrites[transferId]
                if (accumulator == null) {
                    if (pendingWrites.size >= UsbBridgeConstants.MAX_PENDING_WRITES) {
                        return "too many incomplete OUT transfers (${pendingWrites.size})"
                    }
                    accumulator = UsbWriteAssembler(chunkCount, totalLength, now)
                    pendingWrites[transferId] = accumulator
                } else if (accumulator.chunkCount != chunkCount || accumulator.totalLength != totalLength) {
                    pendingWrites.remove(transferId)
                    return "OUT transfer metadata changed for transfer=$transferId"
                }
                val addError = accumulator.add(chunkIndex, data)
                if (addError != null) {
                    pendingWrites.remove(transferId)
                    return "$addError (transfer=$transferId)"
                }
                if (!accumulator.complete()) return ""
                pendingWrites.remove(transferId)
                accumulator.join() ?: return "assembled OUT length mismatch transfer=$transferId"
            }

            val token = generation.get()
            return synchronized(writeLock) {
                if (!running.get() || token != generation.get()) {
                    return@synchronized "USB interface is not active (generation=$token current=${generation.get()} reading=${running.get()})"
                }
                val conn = connection ?: return@synchronized "USB connection missing (generation=$token)"
                val endpoint = output ?: return@synchronized "bulk OUT endpoint missing (generation=$token)"
                val deviceName = openedDeviceName ?: "unknown"
                val interfaceId = claimedInterface?.id ?: -1
                var exceptionDetail: String? = null
                var failureElapsedMs = 0L
                val failure = UsbBulkWriteLoop.writeAll(bytes.size, WRITE_CHUNK) { offset, count ->
                    val chunk = bytes.copyOfRange(offset, offset + count)
                    val startedNs = System.nanoTime()
                    val written = try {
                        conn.bulkTransfer(endpoint, chunk, count, WRITE_TIMEOUT_MS)
                    } catch (t: Throwable) {
                        failureElapsedMs = (System.nanoTime() - startedNs) / 1_000_000
                        val reason = "bulk OUT threw ${t.javaClass.simpleName}: ${t.message ?: "no message"}"
                        exceptionDetail = "$reason transfer=$transferId offset=$offset/${bytes.size} requested=$count generation=$token device=$deviceName interface=$interfaceId endpoint=0x${endpoint.address.toString(16)} elapsed=${failureElapsedMs}ms"
                        Log.e(TAG, exceptionDetail, t)
                        -1
                    }
                    if (exceptionDetail == null) failureElapsedMs = (System.nanoTime() - startedNs) / 1_000_000
                    if (exceptionDetail == null && written in 1 until count) {
                        Log.w(
                            TAG,
                            "bulk OUT partial progress transfer=$transferId offset=$offset/${bytes.size} requested=$count result=$written generation=$token device=$deviceName endpoint=0x${endpoint.address.toString(16)}",
                        )
                    }
                    written
                }
                if (failure != null) {
                    if (exceptionDetail != null) return@synchronized exceptionDetail
                    val reason = "bulk OUT ${failure.reason} transfer=$transferId offset=${failure.offset}/${bytes.size} requested=${failure.requested} result=${failure.result} generation=$token device=$deviceName interface=$interfaceId endpoint=0x${endpoint.address.toString(16)} elapsed=${failureElapsedMs}ms"
                    Log.e(TAG, reason)
                    return@synchronized reason
                }
                ""
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        emitState(UsbBridgeConstants.STATE_DISCONNECTED, "waiting for OCBM adapter")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Match the original helper: a start (including a system restart of a sticky service)
        // attempts USB open immediately. Normal app operation also binds and opens through AIDL.
        findAndOpenAdapter()
        return START_STICKY
    }

    override fun onDestroy() {
        closeDevice("service destroyed")
        delivery.shutdownNow()
        super.onDestroy()
    }

    /** Match the original helper: retry enumeration briefly, but check the USB grant once present. */
    private fun findAndOpenAdapter(): String {
        var devices = usb.deviceList.values.toList()
        var device = devices.firstOrNull(::isAdapter)
        for (attempt in 0 until ENUMERATION_RETRIES) {
            if (device != null) break
            Thread.sleep(ENUMERATION_RETRY_DELAY_MS)
            devices = usb.deviceList.values.toList()
            device = devices.firstOrNull(::isAdapter)
        }
        if (device == null) {
            val reason = "OCBM adapter not found after enumeration retries; USB devices=${describeDevices(devices)}"
            Log.w(TAG, reason)
            emitState(UsbBridgeConstants.STATE_ERROR, reason)
            return ""
        }
        if (!usb.hasPermission(device)) {
            val reason = "USB permission was not granted for ${describeDevice(device)}"
            Log.w(TAG, reason)
            emitState(UsbBridgeConstants.STATE_ERROR, reason)
            return ""
        }
        return if (openDevice(device.deviceName)) device.deviceName else ""
    }

    @Synchronized
    private fun openDevice(deviceName: String): Boolean {
        if (connection != null) return true
        val device = usb.deviceList[deviceName]
        if (device == null || !isAdapter(device)) {
            emitState(UsbBridgeConstants.STATE_ERROR, "OCBM adapter not found: $deviceName; devices=${describeDevices()}")
            return false
        }
        if (!usb.hasPermission(device)) {
            emitState(UsbBridgeConstants.STATE_ERROR, "USB permission was not granted")
            return false
        }
        emitState(UsbBridgeConstants.STATE_CONNECTING, device.deviceName)
        val conn = usb.openDevice(device)
        if (conn == null) {
            emitState(UsbBridgeConstants.STATE_ERROR, "UsbManager.openDevice failed")
            return false
        }
        val endpoints = findBulkEndpoints(device)
        if (endpoints == null) {
            conn.close()
            emitState(UsbBridgeConstants.STATE_ERROR, "No claimable bulk USB interface")
            return false
        }
        if (!conn.claimInterface(endpoints.first, true)) {
            conn.close()
            emitState(UsbBridgeConstants.STATE_ERROR, "No claimable bulk USB interface")
            return false
        }
        connection = conn
        claimedInterface = endpoints.first
        input = endpoints.second
        output = endpoints.third
        openedDeviceName = deviceName
        generation.incrementAndGet()
        emitState(UsbBridgeConstants.STATE_CONNECTED, "${device.vendorId}:${device.productId}")
        startReader()
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
                    // Do not interpret a USB read as an OCBM message boundary. The app-side
                    // Reassembler consumes this raw byte stream and handles split headers,
                    // split payloads, and multiple frames coalesced across reads.
                    try {
                        delivery.execute {
                            if (running.get() && token == generation.get()) {
                                val deliveredAt = System.nanoTime()
                                callbacks.forEach { callback ->
                                    try {
                                        callback.onInboundData(data)
                                    } catch (_: RemoteException) {
                                        callbacks -= callback
                                    }
                                }
                                val callbackMs = (System.nanoTime() - deliveredAt) / 1_000_000
                                if (callbackMs >= SLOW_DELIVERY_MS) {
                                    val detail = "slow Binder delivery ${callbackMs}ms bytes=$count generation=$token"
                                    Log.w(TAG, detail)
                                    emitState(UsbBridgeConstants.STATE_DIAGNOSTIC, detail)
                                }
                                if (delivery.queue.size < DELIVERY_HIGH_WATER) deliveryHighWaterLogged.set(false)
                            }
                        }
                    } catch (_: RejectedExecutionException) {
                        reason = "USB-to-app delivery queue full depth=${delivery.queue.size} capacity=${DELIVERY_QUEUE_CAPACITY} generation=$token"
                        Log.e(TAG, reason)
                        break
                    }
                    val depth = delivery.queue.size
                    if (depth >= DELIVERY_HIGH_WATER && deliveryHighWaterLogged.compareAndSet(false, true)) {
                        val detail = "USB-to-app delivery queue high-water depth=$depth capacity=$DELIVERY_QUEUE_CAPACITY generation=$token"
                        Log.w(TAG, detail)
                        emitState(UsbBridgeConstants.STATE_DIAGNOSTIC, detail)
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
            openedDeviceName = null
        }
        synchronized(pendingWritesLock) { pendingWrites.clear() }
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

    private fun describeDevices(devices: List<UsbDevice> = usb.deviceList.values.toList()): String =
        devices.joinToString(prefix = "[", postfix = "]") {
            "${it.deviceName}:0x${it.vendorId.toString(16)}:0x${it.productId.toString(16)}"
        }.ifEmpty { "none" }

    private fun describeDevicesWithPermissions(devices: List<UsbDevice> = usb.deviceList.values.toList()): String =
        devices.joinToString(prefix = "[", postfix = "]") { device ->
            "${describeDevice(device)} grant=${runCatching { usb.hasPermission(device) }.getOrDefault(false)}"
        }.ifEmpty { "none" }

    private fun describeDevice(device: UsbDevice): String =
        "path=${device.deviceName} vid=0x${device.vendorId.toString(16).padStart(4, '0')} pid=0x${device.productId.toString(16).padStart(4, '0')} interfaces=${device.interfaceCount}"

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

    companion object {
        private const val TAG = "UsbBridgeService"
        private const val VID_CARLINKIT = 0x1314
        private const val PID_OCBM = 0x2d00
        private const val READ_BUFFER_SIZE = 16 * 1024
        private const val ENUMERATION_RETRIES = 5
        private const val ENUMERATION_RETRY_DELAY_MS = 200L
        private const val READ_TIMEOUT_MS = 250
        private const val WRITE_TIMEOUT_MS = 2_000
        private const val WRITE_CHUNK = 16 * 1024
        private const val DELIVERY_QUEUE_CAPACITY = 128
        private const val DELIVERY_HIGH_WATER = 96
        private const val SLOW_DELIVERY_MS = 100L
        private const val RAPID_FAILURE_MS = 10
        private const val RAPID_FAILURE_LIMIT = 50
        private const val RAPID_FAILURE_BACKOFF_MS = 20L
        private const val MAX_CHUNKS = UsbBridgeConstants.MAX_MESSAGE_SIZE / UsbBridgeConstants.CHUNK_SIZE
    }
}
