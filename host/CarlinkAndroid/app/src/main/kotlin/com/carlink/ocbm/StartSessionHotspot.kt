package com.carlink.ocbm

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

internal class HotspotNotReady(message: String) : IllegalStateException(message)

internal data class HotspotInterface(val name: String, val up: Boolean, val addresses: List<InetAddress>) {
    val usableAddresses: List<String> get() = addresses.filter {
        !it.isAnyLocalAddress && !it.isLoopbackAddress && !it.isMulticastAddress &&
            (it is Inet4Address || it is Inet6Address && it.isLinkLocalAddress)
    }.mapNotNull { it.hostAddress?.substringBefore('%') }.distinct().sorted()
}

internal object StartSessionHotspot {
    fun snapshot(): List<HotspotInterface> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().map {
            HotspotInterface(it.name, it.isUp, it.inetAddresses.toList())
        }
    } catch (e: java.net.SocketException) {
        throw HotspotNotReady("Hotspot interface enumeration temporarily unavailable: ${e.message}")
    }

    fun select(interfaces: List<HotspotInterface>): HotspotInterface {
        val bridges = interfaces.filter { it.name.startsWith("br") }
        val preferred = bridges.firstOrNull { it.name == "br0" }
        val ready = bridges.filter { it.up && it.usableAddresses.isNotEmpty() }
        // If the known bridge is present but down, wait for it. Do not switch to another network.
        val selected = if (preferred != null) preferred.takeIf { it in ready } else ready.singleOrNull()
        return selected ?: throw HotspotNotReady("Hotspot bridge not ready or ambiguous; interfaces=" +
            interfaces.joinToString(";") { "${it.name}[up=${it.up},addresses=${it.usableAddresses}]" })
    }
}

/** Bridge/credential readiness can be transient. The owner supplies a timeout and cancellation. */
internal suspend fun <T> awaitHotspotReady(
    isCurrent: () -> Boolean,
    read: () -> T,
    waiting: (String) -> Unit,
    pause: suspend () -> Unit = { delay(500) },
    recovered: (T) -> Unit = {},
): T {
    var lastEvidence: String? = null
    while (true) {
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) throw CancellationException("StartSession owner changed while waiting for hotspot")
        try {
            val result = read()
            currentCoroutineContext().ensureActive()
            if (!isCurrent()) throw CancellationException("StartSession owner changed while reading hotspot")
            if (lastEvidence != null) recovered(result)
            return result
        } catch (e: HotspotNotReady) {
            if (lastEvidence != e.message) {
                lastEvidence = e.message
                waiting(e.message.orEmpty())
            }
        }
        pause()
    }
}
