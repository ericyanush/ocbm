package com.carlink.navigation

import android.os.Handler
import android.os.Looper
import com.carlink.logging.LogRedactor
import com.carlink.logging.Logger
import com.carlink.logging.logInfo
import com.carlink.logging.logNavi
import com.carlink.navigation.compose.ComposedIconStore
import org.json.JSONObject

/**
 * Carlink's route-guidance source: the OCBM box decodes the iPhone's iAP2 route guidance and
 * forwards it as JSON on `CH_METADATA` (`iap2-core/src/metadata.rs`):
 *
 * - `kind=routeGuidance` (0x5201, ~1/s): routeGuidanceState, distanceToManeuverM,
 *   distanceRemainingM, timeRemainingSec, destinationName, **currentManeuverIndex**, …
 * - `kind=maneuver` (0x5202, one per maneuver of the route, keyed by `index`): maneuverType,
 *   description, afterManeuverRoadName, distanceM, drivingSide, junctionType, **exitInfo**.
 *
 * This object turns those into the [NavigationState] Carlink2's cluster/HUD pipeline already
 * renders ([TripBuilder], [ManeuverMapper], the compose icon store), replacing Carlink2's
 * NaviJSON path (`NavigationStateManager.onNaviJson`), which read the stock box's pre-digested
 * fields. Maneuvers are held by index; the live one is whatever the last 0x5201 named.
 *
 * Junction spoke angles (0x5202 params 10/11) arrive as `junctionAngles`/`junctionExitAngle` from
 * a box built from the OCBM branch `route-guidance-exitinfo-angles`; without them the composer
 * falls back to the static glyph per maneuverType. Roundabout exit counts come from the type
 * (ManeuverMapper). `drivingSide` and
 * `junctionType` are Apple enums whose values are not yet confirmed against the Kit, so they are
 * carried on the maneuver record but not mapped onto [NavigationState.turnSide]/[NavigationState.junctionType]
 * until a capture pins them.
 *
 * Thread-safety: called from the OCBM read-side dispatch; a single lock serializes updates.
 */
object OcbmNavigationFeed {
    private val lock = Any()

    init {
        // Enriched junction geometry is the OCBM path's native cluster renderer, not an opt-in
        // debug feature. If a maneuver lacks geometry, ManeuverMapper keeps its static fallback.
        ComposedIconStore.setEnabled(true)
    }

    /**
     * End-of-guidance grace. The 2026-10-19 wired drive showed `routeGuidanceState=0` records
     * arriving every few seconds in the middle of a live route, each followed within a second by a
     * fresh full maneuver list. Clearing on every 0 made the cluster trip flap (clear, refocus,
     * recompose 24 maneuvers, repeat) and the HUD never settled. Now a 0 only arms a timer; the
     * trip is cleared if nothing positive follows within [END_GRACE_MS]. A real end still clears.
     */
    private const val END_GRACE_MS = 6_000L
    private val main = Handler(Looper.getMainLooper())
    private var endPending = false
    private var zeroRecords = 0L
    private val endRunnable =
        Runnable {
            synchronized(lock) {
                if (!endPending) return@Runnable
                endPending = false
                logInfo("[NAVI] OCBM route guidance ended — clearing (no update for ${END_GRACE_MS / 1000}s)", tag = Logger.Tags.NAVI)
                clearLocked()
            }
        }
    private val maneuvers = sortedMapOf<Int, Iap2ManeuverData>()
    private var currentIndex: Int? = null
    private var guidance = NavigationState()
    private var routeDirty = false

    // ---- Lane guidance (iAP2 0x5204 via the box's `kind=laneGuidance`) --------------------------

    /**
     * Lane blocks by LaneGuidanceIndex, each lane by its index within the block. The iPhone sends
     * every block of the route at once when the route starts or changes (the 2026-10-21 drive: 9 to
     * 14 at a time, then nothing until the next re-route), and says in 0x5201 which one to show.
     */
    private val laneBlocks = HashMap<Int, HashMap<Int, LaneRecord>>()

    /**
     * 0x5201 PresentLaneGuidance and LaneGuidanceCurrentIndex, forwarded by a box built from the OCBM
     * branch `lane-guidance-index`. Null until the box sends them; older boxes never do, and then
     * only a maneuver's own link (`linkedLaneGuidance`) can pick a block.
     */
    private var laneShowing: Boolean? = null
    private var laneCurrent: Int? = null

    /** Maneuver index -> the lane block it links to (0x5202 param 12, `linkedLaneGuidance`). */
    private val linkedLanes = HashMap<Int, Int>()

    /** The source of the last published lanes, so a change is logged once rather than per tick. */
    private var lastLaneSource = ""

    private var published = 0L

    /** `kind=routeGuidance` — trip summary and the index of the maneuver being approached. */
    fun onRouteGuidance(obj: JSONObject) {
        synchronized(lock) {
            val active = obj.optInt("routeGuidanceState", -1)
            if (active == 2) {
                logInfo("[NAVI] destination reached — ending cluster trip", tag = Logger.Tags.NAVI)
                clearLocked()
                return
            }
            if (active == 0) {
                // Logged once per run of zeros, not per record: the drive showed them every ~4 s.
                if (!endPending) logInfo("[NAVI] OCBM route guidance state 0 — clearing in ${END_GRACE_MS / 1000}s unless guidance resumes", tag = Logger.Tags.NAVI)
                zeroRecords++
                endPending = true
                main.removeCallbacks(endRunnable)
                main.postDelayed(endRunnable, END_GRACE_MS)
                return
            }
            if (endPending) {
                logInfo("[NAVI] OCBM route guidance resumed (state=$active) after $zeroRecords zero record(s) — keeping the trip", tag = Logger.Tags.NAVI)
                endPending = false
                zeroRecords = 0
                main.removeCallbacks(endRunnable)
            }
            if (obj.has("currentManeuverIndex")) currentIndex = obj.optInt("currentManeuverIndex")
            if (obj.has("presentLaneGuidance")) laneShowing = obj.optInt("presentLaneGuidance") != 0
            if (obj.has("laneGuidanceCurrentIndex")) laneCurrent = obj.optInt("laneGuidanceCurrentIndex")
            guidance =
                guidance.copy(
                    status = if (active > 0) 1 else guidance.status,
                    remainDistance = obj.optIntOr("distanceToManeuverM", guidance.remainDistance),
                    distanceToDestination = obj.optIntOr("distanceRemainingM", guidance.distanceToDestination),
                    timeToDestination = obj.optIntOr("timeRemainingSec", guidance.timeToDestination),
                    destinationName = obj.optString("destinationName").takeIf { it.isNotEmpty() } ?: guidance.destinationName,
                )
            if (routeDirty) {
                ComposedIconStore.populateFromRoute(Iap2RouteData(maneuvers.values.toList()))
                routeDirty = false
            }
            publishLocked()
        }
    }

    /** `kind=maneuver` — one maneuver of the route; the box sends the whole list, one message each. */
    fun onManeuver(obj: JSONObject) {
        if (!obj.has("index")) return
        synchronized(lock) {
            val index = obj.optInt("index")
            val m =
                Iap2ManeuverData(
                    index = index,
                    cpManeuverType = obj.optInt("maneuverType", 0),
                    instructionText = obj.optString("description"),
                    postManeuverRoadName = obj.optString("afterManeuverRoadName"),
                    distanceMeters = obj.optInt("distanceM", 0),
                    distanceDisplayText = obj.optString("distanceText"),
                    distanceUnitFlag = obj.optInt("distanceUnits", 0),
                    flag8 = obj.optInt("drivingSide", 0),
                    flag9 = obj.optInt("junctionType", 0),
                    // Present once the box emits 0x5202 params 10/11 (OCBM branch
                    // route-guidance-exitinfo-angles); absent on older box builds, which leaves the
                    // composer on the static glyph per maneuver type.
                    entryAngles = obj.optJSONArray("junctionAngles")?.let { arr -> List(arr.length()) { arr.optInt(it) } } ?: emptyList(),
                    exitAngle = if (obj.has("junctionExitAngle")) obj.optInt("junctionExitAngle") else null,
                    exitInfo = obj.optString("exitInfo").takeIf { it.isNotEmpty() },
                )
            val changed = maneuvers[index] != m
            maneuvers[index] = m
            if (changed) routeDirty = true
            if (obj.has("linkedLaneGuidance")) linkedLanes[index] = obj.optInt("linkedLaneGuidance")
            if (m.exitInfo != null) {
                logInfo(
                    "[EXIT_INFO] maneuver idx=$index cpType=${m.cpManeuverType} exit='${m.exitInfo}' " +
                        "instruction='${LogRedactor.place(m.instructionText)}'",
                    tag = Logger.Tags.NAVI,
                )
            }
            // Republish only if this maneuver is the live one or its successor — otherwise wait for 0x5201.
            val cur = currentIndex
            val isLiveOrNext = cur != null && (index == cur || index == nextIndexAfter(cur))
            if (changed && isLiveOrNext) publishLocked()
        }
    }

    /**
     * `kind=laneGuidance` — one lane of one lane-guidance block (iAP2 0x5204). The box splits each
     * 0x5204 into one record per lane, lane 0 first, so lane 0 starts the block afresh: a block number
     * is reused by the next route, and its old lanes must not linger (the 2026-10-21 drive showed a
     * re-sent 3-lane block still carrying lanes 3 and 4 of the previous route). `laneCount`, from a
     * box that sends it, trims the block and holds the publish until the block is complete.
     */
    fun onLaneGuidance(obj: JSONObject) {
        if (!obj.has("index")) return
        synchronized(lock) {
            val block = obj.optInt("guidanceIndex", -1)
            val rec = laneRecord(obj)
            val count = if (obj.has("laneCount")) obj.optInt("laneCount") else null
            val lanes = laneBlocks.getOrPut(block) { HashMap() }
            if (rec.lane == 0) lanes.clear()
            if (count != null) lanes.keys.removeAll { it >= count }
            if (lanes[rec.lane] == rec) return
            lanes[rec.lane] = rec
            logInfo(
                "[LANES] rx block=$block lane=${rec.lane}${count?.let { "/$it" } ?: ""} status=${rec.status} angles=${rec.angles} " +
                    "highlight=${rec.highlight} cur=$currentIndex",
                tag = Logger.Tags.NAVI,
            )
            if (currentIndex != null && (count == null || lanes.size >= count)) publishLocked()
        }
    }

    private fun laneRecord(obj: JSONObject): LaneRecord {
        val angle = obj.optInt("laneAngle", 0)
        val angles = obj.optJSONArray("laneAngles")?.let { arr -> List(arr.length()) { arr.optInt(it) } }?.takeIf { it.isNotEmpty() } ?: listOf(angle)
        return LaneRecord(
            lane = obj.optInt("index"),
            status = obj.optInt("laneStatus", 0),
            angle = angles.first(),
            angles = angles,
            highlight = if (obj.has("laneAngleHighlight")) obj.optInt("laneAngleHighlight") else null,
        )
    }

    fun clear() {
        synchronized(lock) { clearLocked() }
    }

    /** Snapshot for diagnostics: maneuvers held and the live index. */
    fun describe(): String = synchronized(lock) { "maneuvers=${maneuvers.size} current=$currentIndex published=$published" }

    private fun clearLocked() {
        endPending = false
        zeroRecords = 0
        main.removeCallbacks(endRunnable)
        maneuvers.clear()
        laneBlocks.clear()
        laneShowing = null
        laneCurrent = null
        linkedLanes.clear()
        lastLaneSource = ""
        currentIndex = null
        guidance = NavigationState()
        routeDirty = false
        ComposedIconStore.clear()
        NavigationStateManager.clear()
    }

    private fun nextIndexAfter(index: Int): Int? = maneuvers.keys.firstOrNull { it > index }

    private fun publishLocked() {
        val cur = currentIndex
        val step = cur?.let { maneuvers[it] }
        val next = cur?.let { nextIndexAfter(it) }?.let { maneuvers[it] }
        val state =
            if (step == null) {
                guidance
            } else {
                guidance.copy(
                    maneuverType = step.cpManeuverType,
                    roadName = step.instructionText.takeIf { it.isNotEmpty() } ?: step.postManeuverRoadName.takeIf { it.isNotEmpty() } ?: guidance.roadName,
                    exitAngle = step.exitAngle,
                    exitInfo = step.exitInfo,
                    nextManeuverType = next?.cpManeuverType,
                    nextRoadName = next?.instructionText?.takeIf { it.isNotEmpty() } ?: next?.postManeuverRoadName,
                    nextExitAngle = next?.exitAngle,
                    nextExitInfo = next?.exitInfo,
                    lanes = lanesForLocked(cur),
                )
            }
        published++
        logNavi {
            "[NAVI] OCBM publish #$published cur=$cur type=${state.maneuverType} road=${state.roadName} " +
                "exit=${state.exitInfo} remain=${state.remainDistance}m next=${state.nextManeuverType}@${state.nextRoadName}"
        }
        NavigationStateManager.publish(state)
    }

    /**
     * Lanes to show now. When the box forwards 0x5201's PresentLaneGuidance, the iPhone decides:
     * the block it names (LaneGuidanceCurrentIndex) while it is presenting lanes, nothing while it is
     * not. Older boxes leave only the approaching maneuver's own link. There is deliberately no
     * "newest block" guess: the iPhone sends the whole route's blocks at once, so the newest is the
     * route's LAST junction (v14 showed that, and only for 15 s after each route start). The source
     * is logged on change; the test pattern (Settings › Cluster) is applied later, by [ClusterStep].
     */
    private fun lanesForLocked(cur: Int?): List<LaneHint> {
        val current = laneCurrent
        val (source, block) =
            when {
                laneShowing == false -> "none (the iPhone is not showing lanes)" to null
                laneShowing == true && current != null -> "current block $current" to current
                else -> cur?.let { linkedLanes[it] }?.let { "linked block $it" to it } ?: ("none" to null)
            }
        val lanes = block?.let { laneBlocks[it] }?.let { LaneGuidance.fromRecords(it.values) } ?: emptyList()
        val tag = "$source $lanes"
        if (tag != lastLaneSource) {
            lastLaneSource = tag
            val missing = if (block != null && lanes.isEmpty()) " (block not received)" else ""
            logInfo("[LANES] cur=$cur lanes from $source$missing: ${LaneIconComposer.describe(lanes)}", tag = Logger.Tags.NAVI)
        }
        LaneActivity.noteLanes(lanes)
        return lanes
    }

    private fun JSONObject.optIntOr(
        key: String,
        fallback: Int,
    ): Int = if (has(key)) optInt(key, fallback) else fallback
}
