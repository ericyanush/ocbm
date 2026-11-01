package com.carlink.ocbm

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import com.carlink.logging.ProbeLog
import java.util.concurrent.atomic.AtomicBoolean

/** Raw OCBM byte pipe opened by the main app UID. Useful on head units that allow main-app grants. */
class DirectUsbBulkTransport(
    context: Context,
    private val log: ProbeLog.Logger,
    private val identity: UsbAdapterIdentity = UsbAdapterIdentity.STANDARD,
) : UsbAdapterTransport {
    companion object {
        private const val READ_BUFFER = 16 * 1024
        private const val READ_TIMEOUT_MS = 250
        private const val WRITE_TIMEOUT_MS = 2_000
        private const val WRITE_CHUNK = 16 * 1024
        private const val ACTION_PERMISSION = "com.carlink.ocbm.USB_PERMISSION"
    }

    private val appContext = context.applicationContext
    private val usb = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val running = AtomicBoolean(false)
    private val writeLock = Any()
    @Volatile private var connection: UsbDeviceConnection? = null
    @Volatile private var claimed: UsbInterface? = null
    @Volatile private var input: UsbEndpoint? = null
    @Volatile private var output: UsbEndpoint? = null
    @Volatile private var reader: Thread? = null
    @Volatile private var readHandler: ((ByteArray, Int) -> Unit)? = null
    @Volatile private var deadHandler: (() -> Unit)? = null
    @Volatile override var lastError: String? = null
        private set
    @Volatile override var activeDeviceName: String? = null
        private set
    private val permissionRequested = AtomicBoolean(false)

    override fun connect(): Boolean = true

    override fun findAndOpenFirstAdapter(): String? {
        val device = usb.deviceList.values.firstOrNull { identity.matches(it.vendorId, it.productId) }
        if (device == null) {
            lastError = "OCBM adapter ${identity.hexId} not enumerated in the main app UID"
            return null
        }
        if (!usb.hasPermission(device)) {
            val requested = requestPermission(device)
            lastError = if (requested || permissionRequested.get()) {
                "Waiting for USB permission for ${device.deviceName}; grant the main app access if prompted"
            } else {
                "USB permission request failed for ${device.deviceName}"
            }
            return null
        }
        return if (open(device)) device.deviceName else null
    }

    private fun requestPermission(device: UsbDevice): Boolean {
        if (!permissionRequested.compareAndSet(false, true)) return true
        try {
            val flags = (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0) or
                PendingIntent.FLAG_UPDATE_CURRENT
            val intent = Intent(ACTION_PERMISSION).setPackage(appContext.packageName)
            val pendingIntent = PendingIntent.getBroadcast(appContext, 0, intent, flags)
            usb.requestPermission(device, pendingIntent)
            log.i("[USB_DIRECT] requested permission for ${device.deviceName} vid=0x${device.vendorId.toString(16)} pid=0x${device.productId.toString(16)}")
            return true
        } catch (t: Throwable) {
            permissionRequested.set(false)
            lastError = "USB permission request threw ${t.javaClass.simpleName}: ${t.message}"
            log.e("[USB_DIRECT] $lastError")
            return false
        }
    }

    private fun open(device: UsbDevice): Boolean {
        val conn = usb.openDevice(device)
        if (conn == null) {
            lastError = "UsbManager.openDevice returned null (grant may be missing for main app UID)"
            log.e("[USB_DIRECT] $lastError")
            return false
        }
        for (index in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(index)
            // These identities are used for a vendor-specific OCBM pipe, including the test ID.
            if (usbInterface.interfaceClass != 0xff || usbInterface.interfaceSubclass != 0xff ||
                usbInterface.interfaceProtocol != 0) continue
            var inEndpoint: UsbEndpoint? = null
            var outEndpoint: UsbEndpoint? = null
            for (i in 0 until usbInterface.endpointCount) {
                val endpoint = usbInterface.getEndpoint(i)
                if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (endpoint.direction == UsbConstants.USB_DIR_IN && inEndpoint == null) inEndpoint = endpoint
                if (endpoint.direction == UsbConstants.USB_DIR_OUT && outEndpoint == null) outEndpoint = endpoint
            }
            if (inEndpoint != null && outEndpoint != null && conn.claimInterface(usbInterface, true)) {
                connection = conn
                claimed = usbInterface
                input = inEndpoint
                output = outEndpoint
                activeDeviceName = device.deviceName
                lastError = null
                log.i("[USB_DIRECT] opened ${device.deviceName} iface=${usbInterface.id} IN=0x${inEndpoint.address.toString(16)} OUT=0x${outEndpoint.address.toString(16)}")
                return true
            }
        }
        conn.close()
        lastError = "No bulk IN/OUT interface could be claimed by the main app UID"
        log.e("[USB_DIRECT] $lastError")
        return false
    }

    override fun setReadHandler(handler: (ByteArray, Int) -> Unit) { readHandler = handler }
    override fun setDeadHandler(handler: () -> Unit) { deadHandler = handler }

    override fun start() {
        if (!running.compareAndSet(false, true)) return
        reader = Thread(::readLoop, "ocbm-direct-usb-read").apply { isDaemon = true; start() }
    }

    private fun readLoop() {
        val buffer = ByteArray(READ_BUFFER)
        var rapidFailures = 0
        while (running.get()) {
            val conn = connection ?: break
            val endpoint = input ?: break
            val began = System.nanoTime()
            val count = runCatching { conn.bulkTransfer(endpoint, buffer, buffer.size, READ_TIMEOUT_MS) }.getOrElse {
                log.w("[USB_DIRECT] bulk IN threw ${it.javaClass.simpleName}: ${it.message}")
                -1
            }
            if (count > 0) {
                rapidFailures = 0
                readHandler?.invoke(buffer, count)
            } else if ((System.nanoTime() - began) / 1_000_000 < 10) {
                if (++rapidFailures >= 50) {
                    lastError = "bulk IN returned rapid -1 x$rapidFailures"
                    log.e("[USB_DIRECT] $lastError")
                    if (running.get()) deadHandler?.invoke()
                    break
                }
                Thread.sleep(20)
            }
        }
    }

    override fun writeBulk(data: ByteArray): Boolean = synchronized(writeLock) {
        val conn = connection ?: return false
        val endpoint = output ?: return false
        if (!running.get()) return false
        var offset = 0
        while (offset < data.size) {
            val count = minOf(WRITE_CHUNK, data.size - offset)
            val written = runCatching { conn.bulkTransfer(endpoint, data, offset, count, WRITE_TIMEOUT_MS) }.getOrElse {
                lastError = "bulk OUT threw ${it.javaClass.simpleName}: ${it.message}"
                log.e("[USB_DIRECT] $lastError")
                return false
            }
            if (written <= 0) {
                lastError = "bulk OUT failed at offset $offset/${data.size} result=$written"
                log.e("[USB_DIRECT] $lastError")
                return false
            }
            offset += written
        }
        true
    }

    override fun stop() {
        running.set(false)
        val readThread = reader
        runCatching { readThread?.join(1_500) }
        reader = null
        synchronized(writeLock) {
            runCatching { claimed?.let { connection?.releaseInterface(it) } }
            runCatching { connection?.close() }
            connection = null
            claimed = null
            input = null
            output = null
            activeDeviceName = null
        }
    }
}
