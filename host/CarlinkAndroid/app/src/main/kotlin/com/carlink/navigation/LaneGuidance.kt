package com.carlink.navigation

import androidx.car.app.navigation.model.LaneDirection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/** One lane as the cluster gets it: the direction it leads and whether to be in it. */
data class LaneHint(
    /**
     * Degrees, Apple's convention: 0 straight, +90 right, -90 left, ±180 back. For a lane to be
     * in, the direction the route takes from it.
     */
    val angle: Int,
    val recommended: Boolean,
    /** The lane's other directions (a straight-or-right lane taken right has others = [0]). */
    val others: List<Int> = emptyList(),
)

/** One lane record from the box's `kind=laneGuidance` JSON (iAP2 0x5204 LaneInformation). */
data class LaneRecord(
    val lane: Int,
    /** 0 do not take, 1 usable, 2 preferred. */
    val status: Int,
    /** The lane's first direction (`laneAngle`). */
    val angle: Int,
    /** Every direction the lane leads (`laneAngles`, from a box that sends them); else just [angle]. */
    val angles: List<Int> = listOf(angle),
    /** The direction the route takes from this lane (`laneAngleHighlight`), when sent. */
    val highlight: Int? = null,
)

/**
 * Lane guidance for the cluster/HUD. GM's cluster and HUD run GM's own code. Carlink can draw lanes
 * into the turn icon GM already shows ([LaneIconComposer]), and offer Step lanes plus a lane image
 * for GM to place itself. With Android Auto phones GM shows lanes where the turn icon sits, which
 * is what the turn-icon layouts aim for.
 */
object LaneGuidance {
    private const val STRAIGHT_MAX = 10
    private const val SLIGHT_MAX = 45
    private const val NORMAL_MAX = 135

    /**
     * Records of one guidance block, in lane order, to hints. "Preferred" lanes win; else "usable".
     * A lane to be in points the way the route goes from it (its highlighted direction), with its
     * other directions kept to draw beside it.
     *
     * The highlight is the way to go, always. A lone 0 beside a turn is a placeholder, not a
     * straight-ahead direction: preferred lanes almost always arrive as `laneAngles=[0]` with the real
     * direction only in `laneAngleHighlight` (153 of the preferred records in the 2026-10-21/22 drives,
     * e.g. `[0]` + 90), and CarPlay draws such a lane as the turn alone. So a lone 0 with a turn
     * highlight leaves just the turn. A lane that lists 0 among other directions really does go straight
     * as well, and keeps it.
     *
     * The reverse, a lone turn with a 0 highlight, is straight ahead: the turn is drawn beside it, not
     * highlighted. The 2026-10-29 drive had it twice, and both times the iPhone meant straight on and the
     * cluster, taking the turn instead, showed the opposite of CarPlay: approaching I-575 exit 7 (block
     * `[0]`+0, `[45]`+0, `[45]`: stay in the left two lanes) and the intersection before the right turn
     * onto Jiles Rd (`[0]`, `[90]`+0: keep right, straight on), whose turn came as the next block.
     */
    fun fromRecords(records: Collection<LaneRecord>): List<LaneHint> {
        val ordered = records.sortedBy { it.lane }
        val anyPreferred = ordered.any { it.status == 2 }
        return ordered.map { r ->
            val recommended = if (anyPreferred) r.status == 2 else r.status == 1
            val angles = r.angles.distinct()
            val lone = angles.singleOrNull()
            val highlight = r.highlight
            val main = if (recommended) highlight ?: r.angle else r.angle
            val placeholderZero = recommended && lone == 0 && main != 0
            val others = if (placeholderZero) emptyList() else angles.filter { it != main }
            LaneHint(main, recommended, others)
        }
    }

    /**
     * The androidx [LaneDirection] shape for an Apple lane angle, as sent to GM in Step lanes.
     *
     * Never a U-turn shape: a back-the-way-you-came lane is sent as a sharp turn. Both lane sets
     * with a U-turn direction (±180) on 2026-10-22 were followed 1.2–1.4 s later by GM's templates
     * host dropping the cluster session (its RendererService disconnecting), and on the first the
     * host crashed again 1.3 s after it rebound and never came back, so the cluster stayed blank;
     * no other lane set did that. GM draws the lane picture ([LaneIconComposer.lanesImage]), which
     * still shows the U-turn; these shapes only have to be safe for it.
     */
    fun shapeFor(angle: Int): Int {
        val a = abs(angle)
        val right = angle > 0
        return when {
            a <= STRAIGHT_MAX -> LaneDirection.SHAPE_STRAIGHT
            a <= SLIGHT_MAX -> if (right) LaneDirection.SHAPE_SLIGHT_RIGHT else LaneDirection.SHAPE_SLIGHT_LEFT
            a <= NORMAL_MAX -> if (right) LaneDirection.SHAPE_NORMAL_RIGHT else LaneDirection.SHAPE_NORMAL_LEFT
            else -> if (right) LaneDirection.SHAPE_SHARP_RIGHT else LaneDirection.SHAPE_SHARP_LEFT
        }
    }
}

/** What lane guidance has seen, for the status line under Settings › Cluster. */
object LaneActivity {
    /** The last lanes the iPhone sent: how many, how many to be in, and when (wall clock). */
    data class Seen(
        val lanes: Int,
        val recommended: Int,
        val atMs: Long,
    )

    private val seenState = MutableStateFlow<Seen?>(null)
    val seen: StateFlow<Seen?> = seenState.asStateFlow()

    private val askedState = MutableStateFlow(false)

    /** Whether the config pushed for the live adapter connection asked the iPhone for lanes. */
    val askedThisConnection: StateFlow<Boolean> = askedState.asStateFlow()

    fun noteLanes(lanes: List<LaneHint>) {
        if (lanes.isEmpty()) return
        seenState.value = Seen(lanes.size, lanes.count { it.recommended }, System.currentTimeMillis())
    }

    fun noteAsked(asked: Boolean) {
        askedState.value = asked
    }
}
