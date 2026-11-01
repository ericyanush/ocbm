package com.carlink.ocbm

import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import zeno.gmccpa.pair.SocketTunnelRelay

/**
 * AAOS side of the OCBM socket broker. It maps adapter-accepted TCP/UDP sockets to receiver sockets
 * bound on loopback. Receiver code remains the protocol endpoint; this class only copies bytes.
 * Registering an adapter listener is synchronous and must be done off the UI/USB-read thread.
 */
class OcbmSocketTunnel(
    private val send: (ByteArray) -> Boolean,
    private val apAddress: InetAddress,
    private val apPrefixBits: Int = 24,
    private val controlPort: Int? = null,
    private val log: (String) -> Unit = {},
) : Closeable, SocketTunnelRelay {
    private val alive = AtomicBoolean(false)
    private val epoch = java.security.SecureRandom().nextInt().let { if (it == 0) 1 else it }
    private val nextId = AtomicInteger(1)
    private val incoming = ArrayBlockingQueue<ByteArray>(512)
    private val registrations = ConcurrentHashMap<Int, Registration>()
    private val streams = ConcurrentHashMap<Int, TunnelStream>()
    private val udpPeers = ConcurrentHashMap<String, UdpPeer>()
    private val controlProxyPeers = ConcurrentHashMap<Int, InetSocketAddress>()
    private val results = ConcurrentHashMap<Int, CompletableFuture<BindResult>>()
    @Volatile private var mdnsHandler: ((ByteArray) -> Unit)? = null
    private val workers: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "carlink-socket-tunnel").apply { isDaemon = true }
    }

    data class BindResult(val port: Int)
    private data class Registration(val protocol: Byte, val localPort: Int)
    private data class TunnelStream(
        val socket: Socket,
        val incoming: ByteBoundedQueue,
        val credit: AtomicInteger,
        val receivedPackets: AtomicLong = AtomicLong(),
        val receivedBytes: AtomicLong = AtomicLong(),
        val forwardedPackets: AtomicLong = AtomicLong(),
        val forwardedBytes: AtomicLong = AtomicLong(),
        val peerEof: AtomicBoolean = AtomicBoolean(false),
    )
    private data class UdpPeer(val peer: InetSocketAddress, val proxy: DatagramSocket)

    /**
     * TCP credits are byte based, so bound the pending receive queue by bytes too. A packet-count
     * bound can reject valid traffic when TCP supplies many small chunks despite unused byte credit.
     */
    private class ByteBoundedQueue(private val capacityBytes: Int) {
        data class Snapshot(val bytes: Int, val packets: Int, val peakBytes: Int, val peakPackets: Int)

        private val lock = ReentrantLock()
        private val notEmpty = lock.newCondition()
        private val packets = ArrayDeque<ByteArray>()
        private var queuedBytes = 0
        private var peakBytes = 0
        private var peakPackets = 0

        fun offer(data: ByteArray): Boolean {
            lock.lock()
            try {
                if (data.size > capacityBytes - queuedBytes) return false
                packets.addLast(data)
                queuedBytes += data.size
                peakBytes = maxOf(peakBytes, queuedBytes)
                peakPackets = maxOf(peakPackets, packets.size)
                notEmpty.signal()
                return true
            } finally {
                lock.unlock()
            }
        }

        @Throws(InterruptedException::class)
        fun poll(timeoutMs: Long): ByteArray? {
            var nanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            lock.lockInterruptibly()
            try {
                while (packets.isEmpty() && nanos > 0) nanos = notEmpty.awaitNanos(nanos)
                val data = packets.pollFirst() ?: return null
                queuedBytes -= data.size
                return data
            } finally {
                lock.unlock()
            }
        }

        fun isEmpty(): Boolean {
            lock.lock()
            try { return packets.isEmpty() } finally { lock.unlock() }
        }

        fun snapshot(): Snapshot {
            lock.lock()
            try { return Snapshot(queuedBytes, packets.size, peakBytes, peakPackets) } finally { lock.unlock() }
        }
    }

    fun start(client: OcbmClient) {
        check(alive.compareAndSet(false, true)) { "socket tunnel already started" }
        client.onSocketTunnelFrame = ::offerFrame
        val start = header(Ocbm.TUNNEL_START, 0)
        check(send(start)) { "could not start OCBM socket tunnel" }
        workers.execute(::dispatchLoop)
        log("socket tunnel started epoch=${epoch.toUInt().toString(16)}")
    }

    /** Register the adapter's phone-facing TCP port for a receiver TCP listener on loopback. */
    fun listenTcp(apAddress: InetAddress, prefixBits: Int, localPort: Int, timeoutMs: Long = 5000): Int =
        register(Ocbm.TUNNEL_LISTEN_TCP, apAddress, prefixBits, localPort, timeoutMs)

    /** Register the adapter's phone-facing UDP port for a receiver UDP socket on loopback. */
    fun bindUdp(apAddress: InetAddress, prefixBits: Int, localPort: Int, timeoutMs: Long = 5000): Int =
        register(Ocbm.TUNNEL_BIND_UDP, apAddress, prefixBits, localPort, timeoutMs)

    override fun registerTcp(localPort: Int): Boolean = runCatching {
        listenTcp(apAddress, apPrefixBits, localPort)
    }.onFailure { log("TCP port registration failed port=$localPort: ${it.message}") }.isSuccess

    override fun registerUdp(localPort: Int): Boolean = runCatching {
        bindUdp(apAddress, apPrefixBits, localPort)
    }.onFailure { log("UDP port registration failed port=$localPort: ${it.message}") }.isSuccess

    override fun unregisterTcp(localPort: Int) = unregister(Ocbm.TUNNEL_LISTEN_TCP, localPort)
    override fun unregisterUdp(localPort: Int) = unregister(Ocbm.TUNNEL_BIND_UDP, localPort)

    override fun phoneAddressForProxySourcePort(sourcePort: Int): String? =
        controlProxyPeers[sourcePort]?.let { "${it.address.hostAddress}:${it.port}" }

    override fun startMdns(apAddress: String, onPacket: (ByteArray) -> Unit): Boolean {
        val address = runCatching { InetAddress.getByName(apAddress) }.getOrNull() ?: return false
        if (address !is java.net.Inet4Address) return false
        mdnsHandler = onPacket
        val future = CompletableFuture<BindResult>()
        results[0] = future
        val body = byteArrayOf(4) + address.address
        val sent = send(header(Ocbm.TUNNEL_MDNS_START, 0) + body)
        if (!sent) { results.remove(0); mdnsHandler = null; return false }
        return try {
            future.get(5, TimeUnit.SECONDS).port == 5353
        } catch (e: Exception) {
            mdnsHandler = null
            log("adapter AP mDNS start failed: ${e.message}")
            false
        } finally { results.remove(0) }
    }

    override fun sendMdns(packet: ByteArray): Boolean =
        packet.size in 12..9000 && send(header(Ocbm.TUNNEL_MDNS_SEND, 0) + packet)

    override fun sendUdp(localPort: Int, peerAddress: String, peerPort: Int, payload: ByteArray): Boolean {
        val registration = registrations.entries.firstOrNull {
            it.value.protocol == Ocbm.TUNNEL_BIND_UDP && it.value.localPort == localPort
        } ?: return false
        if (payload.size > UDP_MAX || peerPort !in 1..65535) return false
        val peer = runCatching { InetSocketAddress(InetAddress.getByName(peerAddress), peerPort) }.getOrNull() ?: return false
        return send(udpSend(registration.key, peer, payload, payload.size))
    }

    private fun unregister(protocol: Byte, localPort: Int) {
        val id = registrations.entries.firstOrNull { it.value.protocol == protocol && it.value.localPort == localPort }?.key
            ?: return
        registrations.remove(id)
        send(closeFrame(id))
        udpPeers.filterKeys { it.startsWith("$id/") }.keys.toList().forEach { key ->
            udpPeers.remove(key)?.proxy?.close()
        }
        log("unregistered ${if (protocol == Ocbm.TUNNEL_LISTEN_TCP) "TCP" else "UDP"} port=$localPort id=$id")
    }

    private fun register(op: Byte, address: InetAddress, prefix: Int, localPort: Int, timeoutMs: Long): Int {
        check(alive.get()) { "socket tunnel is not active" }
        require(localPort in 1..65535)
        require(address !is java.net.Inet6Address) { "dongle AP tunnel currently requires an IPv4 address" }
        require(prefix in 0..if (address.address.size == 4) 32 else 128)
        val id = nextId.getAndIncrement().also { require(it > 0) { "tunnel object ID space exhausted" } }
        val registration = Registration(op, localPort)
        registrations[id] = registration
        val future = CompletableFuture<BindResult>()
        results[id] = future
        var payload = header(op, id)
        val addr = address.address
        payload += byteArrayOf(if (addr.size == 4) 4 else 6)
        payload += addr
        payload += prefix.toByte()
        payload += (localPort and 0xff).toByte()
        payload += ((localPort ushr 8) and 0xff).toByte()
        // For UDP this is unused; for TCP it is the adapter->host initial receive window.
        payload += littleU32(if (op == Ocbm.TUNNEL_LISTEN_TCP) INITIAL_WINDOW else 0)
        check(send(payload)) { "USB write failed registering tunneled socket $localPort" }
        try {
            val actual = future.get(timeoutMs, TimeUnit.MILLISECONDS).port
            check(actual == localPort) { "adapter port $actual differs from local receiver port $localPort" }
            log("registered ${if (op == Ocbm.TUNNEL_LISTEN_TCP) "TCP" else "UDP"} tunnel port=$actual id=$id")
            return actual
        } catch (e: Exception) {
            registrations.remove(id)
            throw e
        } finally {
            results.remove(id)
        }
    }

    /** OCBM read-thread callback: bounded enqueue only, never connect/write sockets here. */
    private fun offerFrame(payload: ByteArray) {
        if (!alive.get()) return
        if (!incoming.offer(payload.copyOf())) log("tunnel event queue full; incoming frame refused")
    }

    private fun dispatchLoop() {
        while (alive.get()) {
            val frame = try { incoming.poll(250, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { null } ?: continue
            if (frame.size < 9 || readU32(frame, 1) != epoch) continue
            val id = readU32(frame, 5)
            when (frame[0]) {
                Ocbm.TUNNEL_RESULT -> handleResult(id, frame)
                Ocbm.TUNNEL_ACCEPT -> handleAccept(id, frame)
                Ocbm.TUNNEL_TCP_DATA -> handleTcpData(id, frame)
                Ocbm.TUNNEL_TCP_CREDIT -> if (frame.size == 13) {
                    streams[id]?.credit?.updateAndGet { (it + readU32(frame, 9)).coerceAtMost(INITIAL_WINDOW) }
                }
                Ocbm.TUNNEL_TCP_EOF -> streams[id]?.let { stream ->
                    stream.peerEof.set(true)
                    if (stream.incoming.isEmpty()) stream.socket.shutdownOutputQuietly()
                }
                Ocbm.TUNNEL_UDP_DATAGRAM -> handleUdpDatagram(id, frame)
                Ocbm.TUNNEL_MDNS_PACKET -> {
                    val peer = decodePeerAddress(frame, 9) ?: continue
                    mdnsHandler?.invoke(frame.copyOfRange(peer.second, frame.size))
                }
                Ocbm.TUNNEL_CLOSE -> closeStream(id)
            }
        }
    }

    private fun handleResult(id: Int, frame: ByteArray) {
        if (frame.size < 12) return
        val status = frame[9].toInt() and 0xff
        val port = readU16(frame, 10)
        val future = results[id] ?: return
        if (status == 0) future.complete(BindResult(port))
        else future.completeExceptionally(IllegalStateException("adapter socket bind failed status=$status"))
    }

    private fun handleAccept(streamId: Int, frame: ByteArray) {
        // body: listener_id u32 LE, adapter receive credit u32 LE, peer address tuple
        if (frame.size < 17) return
        val listenerId = readU32(frame, 9)
        val initialCredit = readU32(frame, 13)
        val listener = registrations[listenerId] ?: return
        if (listener.protocol != Ocbm.TUNNEL_LISTEN_TCP) return
        val tunnelPeer = runCatching { decodePeerAddress(frame, 17)?.first }.getOrNull()
        var mappedSourcePort: Int? = null
        val socket = runCatching {
            Socket().apply {
                tcpNoDelay = true
                if (listener.localPort == controlPort) {
                    val peer = checkNotNull(tunnelPeer) { "accepted control stream has no peer address" }
                    // Publish the AP-side peer mapping before connect(): the local receiver can
                    // accept and validate the loopback socket as soon as the SYN completes.
                    bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
                    mappedSourcePort = localPort
                    controlProxyPeers[localPort] = peer
                }
                connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), listener.localPort), 1500)
            }
        }.getOrElse {
            mappedSourcePort?.let(controlProxyPeers::remove)
            log("local TCP connect failed port=${listener.localPort}: ${it.message}")
            send(closeFrame(streamId))
            return
        }
        val inbound = ByteBoundedQueue(INITIAL_WINDOW)
        val stream = TunnelStream(socket, inbound, AtomicInteger(initialCredit.coerceAtLeast(0)))
        streams[streamId] = stream
        // Start with the explicit receive window the adapter offered for this accepted stream.
        send(tcpCredit(streamId, INITIAL_WINDOW))
        workers.execute { pumpNetworkToUsb(streamId, stream) }
        workers.execute { pumpUsbToNetwork(streamId, socket, inbound) }
        log("accepted tunneled TCP peer=${decodePeer(frame, 17)} local=${listener.localPort} stream=$streamId")
    }

    private fun pumpNetworkToUsb(streamId: Int, stream: TunnelStream) {
        val socket = stream.socket
        val input = socket.getInputStream()
        val bytes = ByteArray(TCP_CHUNK)
        try {
            while (alive.get() && !socket.isClosed) {
                val credit = stream.credit.get()
                if (credit <= 0) { Thread.sleep(5); continue }
                val n = input.read(bytes, 0, minOf(bytes.size, credit))
                if (n < 0) {
                    send(header(Ocbm.TUNNEL_TCP_EOF, streamId))
                    // Half-close only. The adapter may still have response bytes to deliver.
                    return
                }
                if (!send(tcpData(streamId, bytes, n))) throw java.io.IOException("USB tunnel write failed")
                stream.credit.addAndGet(-n)
            }
        } catch (e: Exception) {
            if (alive.get()) log("TCP uplink closed stream=$streamId: ${e.message}")
            send(closeFrame(streamId))
            closeStream(streamId)
        }
    }

    private fun pumpUsbToNetwork(streamId: Int, socket: Socket, queue: ByteBoundedQueue) {
        try {
            val output = socket.getOutputStream()
            var lastStatsAt = System.nanoTime()
            var lastReceivedBytes = 0L
            var lastForwardedBytes = 0L
            var maxSocketWriteUs = 0L
            var maxCreditSendUs = 0L
            var socketWriteUs = 0L
            var creditSendUs = 0L
            while (alive.get() && !socket.isClosed) {
                val data = queue.poll(250)
                if (data != null) {
                    val writeStarted = System.nanoTime()
                    output.write(data)
                    output.flush()
                    val writeUs = (System.nanoTime() - writeStarted) / 1000
                    socketWriteUs += writeUs
                    maxSocketWriteUs = maxOf(maxSocketWriteUs, writeUs)
                    val stream = streams[streamId]
                    stream?.forwardedPackets?.incrementAndGet()
                    stream?.forwardedBytes?.addAndGet(data.size.toLong())
                    val creditStarted = System.nanoTime()
                    if (!send(tcpCredit(streamId, data.size))) {
                        throw java.io.IOException("USB tunnel credit write failed")
                    }
                    val creditUs = (System.nanoTime() - creditStarted) / 1000
                    creditSendUs += creditUs
                    maxCreditSendUs = maxOf(maxCreditSendUs, creditUs)
                    if (stream?.peerEof?.get() == true && queue.isEmpty()) {
                        socket.shutdownOutputQuietly()
                        return
                    }
                }

                val now = System.nanoTime()
                if (now - lastStatsAt >= STREAM_STATS_INTERVAL_NS) {
                    streams[streamId]?.let { stream ->
                        val snapshot = queue.snapshot()
                        val received = stream.receivedBytes.get()
                        val forwarded = stream.forwardedBytes.get()
                        val intervalSeconds = (now - lastStatsAt) / 1_000_000_000.0
                        val rxKbps = ((received - lastReceivedBytes) * 8 / intervalSeconds / 1000).toLong()
                        val txKbps = ((forwarded - lastForwardedBytes) * 8 / intervalSeconds / 1000).toLong()
                        log("TCP tunnel stats stream=$streamId local=${socket.localPort} " +
                            "rx=${stream.receivedPackets.get()}pk/${received}B(${rxKbps}Kbps) " +
                            "forwarded=${stream.forwardedPackets.get()}pk/${forwarded}B(${txKbps}Kbps) " +
                            "queue=${snapshot.bytes}B/${snapshot.packets}pk peak=${snapshot.peakBytes}B/${snapshot.peakPackets}pk " +
                            "socketWriteUs=$socketWriteUs maxSocketWriteUs=$maxSocketWriteUs " +
                            "creditSendUs=$creditSendUs maxCreditSendUs=$maxCreditSendUs")
                        lastReceivedBytes = received
                        lastForwardedBytes = forwarded
                    }
                    socketWriteUs = 0L
                    creditSendUs = 0L
                    maxSocketWriteUs = 0L
                    maxCreditSendUs = 0L
                    lastStatsAt = now
                }
            }
        } catch (e: Exception) {
            if (alive.get()) log("TCP downlink closed stream=$streamId: ${e.message}")
            send(closeFrame(streamId))
            closeStream(streamId)
        }
    }

    private fun handleTcpData(id: Int, frame: ByteArray) {
        if (frame.size <= 9) return
        val data = frame.copyOfRange(9, frame.size)
        val stream = streams[id] ?: return
        if (!stream.incoming.offer(data)) {
            val snapshot = stream.incoming.snapshot()
            log("TCP inbound queue byte limit exceeded stream=$id incoming=${data.size}B " +
                "queued=${snapshot.bytes}B/${snapshot.packets}pk capacity=${INITIAL_WINDOW}B; closing")
            send(closeFrame(id)); closeStream(id)
        } else {
            stream.receivedPackets.incrementAndGet()
            stream.receivedBytes.addAndGet(data.size.toLong())
        }
    }

    private fun handleUdpDatagram(id: Int, frame: ByteArray) {
        val registration = registrations[id] ?: return
        if (registration.protocol != Ocbm.TUNNEL_BIND_UDP) return
        val peer = decodePeerAddress(frame, 9) ?: return
        val bodyOffset = peer.second
        val key = "$id/${peer.first.address.hostAddress}:${peer.first.port}"
        if (!udpPeers.containsKey(key) && udpPeers.size >= MAX_UDP_PEERS) {
            log("UDP tunnel peer cap reached; dropping datagram registration=$id")
            return
        }
        val flow = udpPeers.computeIfAbsent(key) {
            val proxy = DatagramSocket()
            val p = UdpPeer(peer.first, proxy)
            workers.execute { pumpUdpResponse(id, p, registration.localPort) }
            p
        }
        if (frame.size - bodyOffset > UDP_MAX) return
        runCatching {
            val packet = DatagramPacket(frame, bodyOffset, frame.size - bodyOffset,
                IPV4_LOOPBACK, registration.localPort)
            flow.proxy.send(packet)
        }
    }

    private fun pumpUdpResponse(id: Int, flow: UdpPeer, localPort: Int) {
        val bytes = ByteArray(UDP_MAX)
        try {
            flow.proxy.soTimeout = 500
            while (alive.get() && !flow.proxy.isClosed) {
                val packet = DatagramPacket(bytes, bytes.size)
                try {
                    flow.proxy.receive(packet)
                    send(udpSend(id, flow.peer, bytes, packet.length))
                } catch (_: java.net.SocketTimeoutException) { }
            }
        } catch (e: Exception) { if (alive.get()) log("UDP flow closed local=$localPort: ${e.message}") }
        finally { flow.proxy.close() }
    }

    private fun closeStream(id: Int) {
        streams.remove(id)?.let { stream ->
            val queue = stream.incoming.snapshot()
            log("TCP tunnel closed stream=$id local=${stream.socket.localPort} " +
                "rx=${stream.receivedPackets.get()}pk/${stream.receivedBytes.get()}B " +
                "forwarded=${stream.forwardedPackets.get()}pk/${stream.forwardedBytes.get()}B " +
                "queue=${queue.bytes}B/${queue.packets}pk peak=${queue.peakBytes}B/${queue.peakPackets}pk")
            controlProxyPeers.remove(stream.socket.localPort)
            stream.socket.closeQuietly()
        }
    }

    override fun close() {
        if (!alive.compareAndSet(true, false)) return
        send(header(Ocbm.TUNNEL_STOP, 0))
        streams.keys.toList().forEach(::closeStream)
        udpPeers.values.forEach { it.proxy.close() }
        mdnsHandler = null
        udpPeers.clear(); registrations.clear(); controlProxyPeers.clear(); results.values.forEach { it.completeExceptionally(IllegalStateException("tunnel stopped")) }
        results.clear()
        workers.shutdownNow()
        log("socket tunnel stopped epoch=${epoch.toUInt().toString(16)}")
    }

    private fun header(op: Byte, id: Int): ByteArray = byteArrayOf(op) + littleU32(epoch) + littleU32(id)
    private fun closeFrame(id: Int): ByteArray = header(Ocbm.TUNNEL_CLOSE, id) + byteArrayOf(0)
    private fun tcpCredit(id: Int, amount: Int): ByteArray = header(Ocbm.TUNNEL_TCP_CREDIT, id) + littleU32(amount)
    private fun tcpData(id: Int, bytes: ByteArray, length: Int): ByteArray = header(Ocbm.TUNNEL_TCP_DATA, id) + bytes.copyOf(length)
    private fun udpSend(id: Int, peer: InetSocketAddress, bytes: ByteArray, length: Int): ByteArray {
        val address = peer.address.address
        return header(Ocbm.TUNNEL_UDP_SEND, id) + byteArrayOf(if (address.size == 4) 4 else 6) + address +
            byteArrayOf((peer.port ushr 8).toByte(), peer.port.toByte()) + bytes.copyOf(length)
    }

    private fun decodePeer(frame: ByteArray, offset: Int): String = decodePeerAddress(frame, offset)?.first?.toString() ?: "?"
    private fun decodePeerAddress(frame: ByteArray, offset: Int): Pair<InetSocketAddress, Int>? {
        if (frame.size <= offset) return null
        val family = frame[offset].toInt() and 0xff
        val len = when (family) { 4 -> 4; 6 -> 16; else -> return null }
        val portOffset = offset + 1 + len
        if (frame.size < portOffset + 2) return null
        val address = InetAddress.getByAddress(frame.copyOfRange(offset + 1, portOffset))
        val port = ((frame[portOffset].toInt() and 0xff) shl 8) or (frame[portOffset + 1].toInt() and 0xff)
        return InetSocketAddress(address, port) to (portOffset + 2)
    }

    private fun readU16(b: ByteArray, at: Int) = (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8)
    private fun readU32(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8) or
        ((b[at + 2].toInt() and 0xff) shl 16) or (b[at + 3].toInt() shl 24)
    private fun littleU32(n: Int) = byteArrayOf(n.toByte(), (n ushr 8).toByte(), (n ushr 16).toByte(), (n ushr 24).toByte())
    private fun Socket.shutdownOutputQuietly() { runCatching { shutdownOutput() } }
    private fun Socket.closeQuietly() { runCatching { close() } }

    companion object {
        private val IPV4_LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")
        private const val INITIAL_WINDOW = 256 * 1024
        private const val TCP_CHUNK = 16 * 1024
        private const val UDP_MAX = 60 * 1024
        private const val MAX_UDP_PEERS = 32
        private const val STREAM_STATS_INTERVAL_NS = 5_000_000_000L
    }
}
