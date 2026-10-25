package com.carlink.ocbm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import zeno.gmccpa.CarPlayRx

/** Per-connection selection and request ownership. Never falls back to an HTTP nudge. */
class NativeStartSession(
    private val scope: CoroutineScope,
    private val receiver: () -> CarPlayRx?,
    private val send: (JSONObject) -> Boolean,
    private val hotspot: () -> VehicleHotspotCredentials?,
    private val status: (String) -> Unit,
    private val log: (String) -> Unit,
) {
    var enabled = false
    @Volatile var progressText: String? = null
        private set
    private fun report(text: String) {
        progressText = text
        log("progress: $text")
        status(text)
    }
    private val slots = java.util.concurrent.ConcurrentHashMap<String, JSONObject>()
    private val blocked = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val retiredSlots = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val generation = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile private var request: JSONObject? = null
    private var pendingPeer: String? = null
    private var pendingSlotId: String? = null
    private var timer: Job? = null
    private var pending: Job? = null
    private var networkWatch: Job? = null

    private fun networkKey(e: JSONObject): String = listOf("interface", "addresses", "port", "deviceId")
        .joinToString("|") { e.opt(it)?.toString().orEmpty() }

    fun refreshNetwork() {
        val current = request ?: return
        if (!enabled || receiver()?.sessionLive == true) return
        scope.launch {
            val fresh = withContext(Dispatchers.IO) { runCatching { endpoint() }.getOrNull() }
            if (request !== current || receiver()?.sessionLive == true) return@launch
            if (fresh == null || networkKey(fresh) != networkKey(current.getJSONObject("endpoint"))) {
                val peer = current.getString("peerMac")
                log("hotspot interface/address changed; invalidating request=${current.getString("requestId")}")
                cancel(clearSlots = false)
                select(peer) // A missing bridge waits for recovery without blocking this slot.
            }
        }
    }

    fun endpoint(): JSONObject {
        val rx = checkNotNull(receiver()) { "Native receiver is unavailable" }
        val credentials = hotspot() ?: throw HotspotNotReady("Hotspot credentials unavailable; waiting for saved settings")
        val iface = StartSessionHotspot.select(StartSessionHotspot.snapshot())
        val addresses = iface.usableAddresses
        check(addresses.isNotEmpty() && addresses.size <= 16) { "Hotspot addresses unavailable" }
        return rx.startSessionIdentity().put("interface", iface.name).put("addresses", JSONArray(addresses))
            .put("ssid", credentials.ssid).put("passphrase", credentials.password)
            .put("channel", credentials.channel).put("security", credentials.security)
    }

    suspend fun awaitEndpoint(isCurrent: () -> Boolean): JSONObject = awaitHotspotReady(
        isCurrent = isCurrent,
        read = { endpoint() },
        waiting = { evidence ->
            report("StartSession waiting for vehicle hotspot...")
            log("waiting for hotspot: $evidence")
        },
        recovered = { endpoint ->
            log("hotspot readiness recovered: interface=${endpoint.optString("interface")} addresses=${endpoint.optJSONArray("addresses")} port=${endpoint.optInt("port")}")
            report("Vehicle hotspot ready — preparing CarPlay...")
        },
    )

    /** Hotspot readiness is a preparation milestone, not the ongoing phone-wait state. */
    fun waitingForPhone() {
        progressText = null
    }

    private fun reportWaitingUnlessFailed() {
        val current = progressText.orEmpty()
        if (current.startsWith("StartSession failed") ||
            current.startsWith("StartSession unavailable") ||
            current.startsWith("StartSession ended") ||
            current.startsWith("Adapter did not process")
        ) return
        waitingForPhone()
        status("Waiting for phone...")
    }

    /** Slot ordering is only meaningful within one OCBM adapter session. */
    fun adapterSessionChanged() {
        generation.incrementAndGet()
        timer?.cancel(); timer = null
        pending?.cancel(); pending = null
        networkWatch?.cancel(); networkWatch = null
        pendingPeer = null
        pendingSlotId = null
        request = null
        val oldSlotCount = slots.size
        slots.clear()
        blocked.clear()
        retiredSlots.clear()
        progressText = null
        log("new OCBM adapter session; discarded $oldSlotCount cached iAP2 slot(s) and StartSession state")
    }

    fun event(e: JSONObject): Pair<String, Boolean>? {
        if (!enabled || e.optInt("version") != 1) return null
        val peer = e.optString("peerMac").uppercase()
        if (!peer.matches(Regex("[0-9A-F]{2}(:[0-9A-F]{2}){5}"))) return null
        val stage = e.optString("stage")
        if (stage == "queued") { log("request=${e.optString("requestId")} queued; Bluetooth write still pending"); return null }
        val slotId = e.optString("slotId")
        if (slotId.isEmpty()) return null
        val activeRequest = request
        if (stage in setOf("sent", "failed", "unsupported", "cancelled") && e.optString("requestId").isNotEmpty() &&
            (activeRequest == null || e.optString("requestId") != activeRequest.optString("requestId") ||
                e.optString("receiverEpoch") != activeRequest.optString("receiverEpoch") ||
                slotId != activeRequest.optString("slotId"))
        ) {
            log("ignoring response to obsolete StartSession request=${e.optString("requestId")} slot=$slotId stage=$stage")
            return null
        }
        val known = slots[peer]
        val establishment = stage in setOf("connected", "identified", "available", "unavailable")
        val newer = if (known != null && known.optLong("slotStartedAt") > 0 && e.optLong("slotStartedAt") > 0) {
            e.optLong("slotStartedAt") > known.optLong("slotStartedAt")
        } else stage == "connected" // Older firmware has no monotonic slot ordering.
        if (slotId in retiredSlots ||
            (known != null && known.optString("slotId") != slotId && !newer) ||
            (known == null && !establishment && e.optLong("slotStartedAt") == 0L && request?.optString("slotId") != slotId)
        ) {
            log("ignoring stale event peer=$peer slot=$slotId stage=$stage; current=${known?.optString("slotId")}")
            return null
        }
        if (known != null && known.optString("slotId") != slotId) {
            val oldSlot = known.optString("slotId")
            retiredSlots.add(oldSlot)
            blocked.remove(oldSlot)
            if (request?.optString("slotId") == oldSlot || pendingSlotId == oldSlot) cancel(clearSlots = false)
        }
        val current = request
        if (stage == "ended") {
            slots.remove(peer)
            retiredSlots.add(slotId)
            blocked.remove(slotId)
            if (current?.optString("slotId") == slotId || pendingSlotId == slotId) cancel(clearSlots = false)
        } else {
            slots[peer] = e
        }
        log("peer=$peer slot=$slotId request=${e.optString("requestId")} stage=$stage reason=${e.optString("reason")} failureReason=${e.optString("failureReason")}")
        if (current != null && e.optString("requestId") == current.optString("requestId") &&
            e.optString("receiverEpoch") == current.optString("receiverEpoch") && e.optString("slotId") == current.optString("slotId")) {
            if (stage == "sent") {
                timer?.cancel()
                if (receiver()?.sessionLive == true) return peer to true
                report("StartSession sent — waiting for phone...")
                timer = scope.launch {
                    delay(30_000)
                    if (request === current && receiver()?.sessionLive != true) {
                        report("StartSession timed out — retry or disable the experiment")
                        blocked.add(current.optString("slotId"))
                        cancel(clearSlots = false)
                    }
                }
            } else if (stage in setOf("failed", "unsupported", "ended")) {
                timer?.cancel()
                if (stage != "ended") blocked.add(slotId)
                report("StartSession $stage: ${e.optString("reason")}")
            }
        } else if (stage in setOf("failed", "unsupported") && e.optString("requestId").isEmpty()) {
            blocked.add(e.optString("slotId")); report("StartSession $stage: ${e.optString("reason")}")
        }
        if (stage == "cancelled") return null
        return peer to (e.optBoolean("available") && stage !in setOf("failed", "unsupported", "ended", "unavailable"))
    }

    fun select(peer: String?) {
        if (!enabled || receiver()?.sessionLive == true) return
        if (peer == null) {
            cancel(clearSlots = false)
            reportWaitingUnlessFailed()
            return
        }
        if (request?.optString("peerMac")?.equals(peer, true) == false || pendingPeer?.equals(peer, true) == false) cancel(clearSlots = false)
        val slot = slots[peer.uppercase()]
        if (slot == null || !slot.optBoolean("available") || slot.optString("slotId") in blocked) {
            cancel(clearSlots = false)
            reportWaitingUnlessFailed()
            return
        }
        val previous = request
        if (previous?.optString("peerMac") == peer.uppercase() && previous.optString("slotId") == slot.optString("slotId")) return
        if (pendingPeer.equals(peer, true) && pendingSlotId == slot.optString("slotId") && pending?.isActive == true) return
        cancel(clearSlots = false)
        val epoch = generation.get()
        pendingPeer = peer
        pendingSlotId = slot.optString("slotId")
        pending = scope.launch {
            val rx = receiver() ?: return@launch
            if (rx.sessionLive) return@launch
            val result = runCatching {
                val ep = withContext(Dispatchers.IO) {
                    awaitEndpoint { generation.get() == epoch && receiver() === rx && !rx.sessionLive }
                    check(rx.awaitAdvertRegistered(5000)) { "Receiver advertisement unavailable" }
                    awaitEndpoint { generation.get() == epoch && receiver() === rx && !rx.sessionLive }
                }
                if (generation.get() != epoch || receiver() !== rx || rx.sessionLive) return@launch
                val r = JSONObject().put("version", 1).put("action", "start")
                    .put("requestId", java.util.UUID.randomUUID().toString()).put("peerMac", peer.uppercase())
                    .put("slotId", slot.getString("slotId")).put("receiverEpoch", rx.receiverEpoch).put("endpoint", ep)
                rx.authorizeStartSession(peer, ep.getString("interface"))
                request = r
                report("Requesting CarPlay StartSession...")
                timer = scope.launch {
                    delay(10_000)
                    if (request === r && receiver()?.sessionLive != true && slots[peer.uppercase()]?.optString("requestId") != r.optString("requestId")) {
                        report("Adapter did not process StartSession request")
                        blocked.add(slot.optString("slotId"))
                        cancel(clearSlots = false)
                    }
                }
                check(withContext(Dispatchers.IO) { send(r) }) { "StartSession request write failed" }
                if (request === r && !rx.sessionLive) networkWatch = scope.launch {
                    while (request === r && !rx.sessionLive) { delay(2000); refreshNetwork() }
                }
            }
            val failure = result.exceptionOrNull()
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            if (generation.get() != epoch) return@launch
            pendingPeer = null
            pendingSlotId = null
            result.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                blocked.add(slot.optString("slotId")); cancel(clearSlots = false)
                report("StartSession unavailable: ${it.message}"); log("preparation failed: ${it.message}")
            }
        }
    }

    fun connected() { progressText = null; timer?.cancel(); pending?.cancel(); networkWatch?.cancel() }
    fun connectionEnded() { progressText = null; cancel(clearSlots = false) }
    fun cancel(clearSlots: Boolean = true) {
        generation.incrementAndGet()
        timer?.cancel(); timer = null; pending?.cancel(); pending = null
        networkWatch?.cancel(); networkWatch = null
        pendingPeer = null
        pendingSlotId = null
        request?.let { r ->
            val cancellation = JSONObject(r.toString()).put("action", "cancel").put("endpoint", JSONObject.NULL)
            // USB writes may block. Adapter request IDs make a delayed cancellation harmless.
            scope.launch(Dispatchers.IO) { send(cancellation) }
        }
        request = null
        receiver()?.revokeStartSession()
        if (clearSlots) { slots.clear(); blocked.clear(); retiredSlots.clear(); progressText = null }
    }
}
