package zeno.gmccpa

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import java.net.NetworkInterface
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * Low-volume network evidence for the wireless reconnect path.
 *
 * Records the default Android network and every interface visible to this app, including interfaces
 * that are down (which can be important during Wi-Fi handoff). It deliberately omits SSIDs, MAC
 * addresses, DNS contents, and hotspot credentials. The process owns this monitor,
 * not an Activity, because MainActivity can be destroyed while a CarPlay session is still active.
 */
object NetworkDiagnostics {
    private val log = ProbeLog.sub("net")
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "net-diagnostics").apply { isDaemon = true }
    }
    @Volatile private var appContext: Context? = null
    @Volatile private var callbackRegistered = false
    private var callback: ConnectivityManager.NetworkCallback? = null

    @Synchronized
    fun start(context: Context) {
        if (callbackRegistered) return
        val app = context.applicationContext
        appContext = app
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null) {
            log.w("ConnectivityManager unavailable; interface snapshots remain available")
            callbackRegistered = true
            snapshot("monitor start")
            return
        }
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                log.i("default network AVAILABLE handle=${network.networkHandle}")
                snapshot("default network available")
            }

            override fun onLost(network: Network) {
                log.w("default network LOST handle=${network.networkHandle}")
                snapshot("default network lost")
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                log.i("default network capabilities changed handle=${network.networkHandle}: ${capabilitySummary(caps)}")
                snapshot("network capabilities changed")
            }

            override fun onLinkPropertiesChanged(network: Network, props: LinkProperties) {
                log.i("default network link properties changed handle=${network.networkHandle}: ${linkSummary(props)}")
                snapshot("link properties changed")
            }

            override fun onUnavailable() {
                log.w("default network UNAVAILABLE")
                snapshot("default network unavailable")
            }
        }
        callback = cb
        try {
            cm.registerDefaultNetworkCallback(cb)
            callbackRegistered = true
            log.i("default-network monitor registered")
        } catch (t: Throwable) {
            callback = null
            callbackRegistered = true // do not repeatedly register on Activity recreation
            log.w("default-network monitor registration failed: ${t.javaClass.simpleName}: ${t.message}")
        }
        snapshot("monitor start")
    }

    /** Capture now-ish on a dedicated thread so interface enumeration never blocks USB/audio paths. */
    fun snapshot(reason: String) {
        val app = appContext ?: return
        try {
            executor.execute { capture(app, reason) }
        } catch (_: RejectedExecutionException) {
            // Process teardown; the diagnostic is best-effort.
        }
    }

    /** A small delayed pair makes radio-recovery transitions visible without adding sleeps to them. */
    fun snapshotAfter(reason: String, delayMs: Long) {
        val app = appContext ?: return
        try {
            executor.schedule({ capture(app, "$reason +${delayMs}ms") }, delayMs, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            // Process teardown; the diagnostic is best-effort.
        }
    }

    private fun capture(context: Context, reason: String) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val active = runCatching { cm?.activeNetwork }.getOrNull()
        val caps = runCatching { if (cm != null && active != null) cm.getNetworkCapabilities(active) else null }.getOrNull()
        val props = runCatching { if (cm != null && active != null) cm.getLinkProperties(active) else null }.getOrNull()
        val interfaces = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .sortedBy { it.name }
                .joinToString(";") { nif ->
                    val state = runCatching {
                        "up=${nif.isUp},loopback=${nif.isLoopback},pointToPoint=${nif.isPointToPoint},mtu=${nif.mtu}"
                    }.getOrDefault("state=unavailable")
                    val addresses = runCatching {
                        nif.interfaceAddresses.joinToString(",") { item ->
                            "${item.address.hostAddress?.substringBefore('%')}/${item.networkPrefixLength}"
                        }
                    }.getOrDefault("unavailable")
                    "${nif.name}[$state,addr=[$addresses]]"
                }
        }.getOrDefault("unavailable")
        log.i("snapshot reason=$reason active=${active?.networkHandle ?: "none"} " +
            "caps=${caps?.let(::capabilitySummary) ?: "none"} " +
            "link=${props?.let(::linkSummary) ?: "none"} interfaces={$interfaces}")
    }

    private fun capabilitySummary(caps: NetworkCapabilities): String {
        val transports = buildList {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("wifi")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("cellular")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ethernet")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add("bluetooth")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("vpn")
        }.ifEmpty { listOf("other") }.joinToString("+")
        return "$transports internet=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)}" +
            ",validated=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}" +
            ",notMetered=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)}" +
            ",captive=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)}"
    }

    private fun linkSummary(props: LinkProperties): String {
        val addresses = props.linkAddresses.joinToString(",") { it.toString().substringBefore('%') }
        val routes = props.routes.joinToString(",") { route ->
            "${route.destination} via ${route.gateway?.hostAddress?.substringBefore('%') ?: "-"}"
        }
        return "iface=${props.interfaceName ?: "-"} addr=[$addresses] routes=[$routes]"
    }
}
