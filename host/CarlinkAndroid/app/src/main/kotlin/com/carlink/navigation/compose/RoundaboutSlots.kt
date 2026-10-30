package com.carlink.navigation.compose

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Roundabout arms at the angles CarPlay draws them (owner, 10-28: the cluster/HUD icon must match
 * the CarPlay screen). At a roundabout the iPhone sends the map's raw arm angles (drive logs: 76°,
 * −76°, −67° for arms square to each other), where ordinary turns arrive already square. Apple's
 * MKJunction, and so CarPlay's guidance card, puts every arm in the nearest of eight slots 45° apart
 * (Carlink2 revisions [137]); drawn raw, a right exit sat 100° or more from the entry. The white exit
 * arrow and the grey arms both go through [snap].
 *
 * Angles are driver-relative degrees: 0 straight on, +90 right, −90 left, 180 back (the entry).
 */
internal object RoundaboutSlots {
    private const val SLOT_DEG = 45
    private const val ENTRY_DEG = 180
    private const val FULL_TURN = 360

    /** [AppleManeuverPaths]' arrows come every 10°; a diagonal slot sits halfway between two. */
    private const val TABLE_STEP_DEG = 10
    private const val HALF_STEP_DEG = 5.0

    /** Any key the table has: its arrows are the same at every arm count and exit. */
    private const val TABLE_ARMS = 4
    private const val TABLE_EXIT = 2

    private val NUMBER = Regex("-?\\d+\\.\\d+")

    /** [deg] in its slot, in (−180, 180]. */
    fun snap(deg: Int): Int {
        val slot = Math.floorMod((deg.toDouble() / SLOT_DEG).roundToInt() * SLOT_DEG, FULL_TURN)
        return if (slot > ENTRY_DEG) slot - FULL_TURN else slot
    }

    /** The other arms to draw grey, each slot once, leaving out the entry and the exit (the white arrow is those). */
    fun arms(
        entryAngles: List<Int>,
        exitSlot: Int,
    ): List<Int> = entryAngles.map(::snap).distinct().filter { it != exitSlot && it != ENTRY_DEG }

    /**
     * The white exit arrow into [slot]: Apple's own path at 0, ±90 and 180, and at a diagonal the two
     * paths 5° either side blended halfway (same commands, so every point moves half the way).
     *
     * The table holds one arrow per angle whatever the arm count and exit number (all 36 steps
     * checked, 10-28), so it is read at one key: a 4th or later exit, which the table has no key
     * for, gets its arrow too instead of falling back to the generic static icon.
     */
    fun arrow(slot: Int): String? {
        if (slot % TABLE_STEP_DEG == 0) return tableArrow(slot.toDouble())
        val below = tableArrow(slot - HALF_STEP_DEG) ?: return null
        val above = tableArrow(slot + HALF_STEP_DEG) ?: return null
        return blend(below, above)
    }

    private fun tableArrow(deg: Double): String? = AppleManeuverPaths.roundaboutPaths(TABLE_ARMS, TABLE_EXIT, deg)?.arrow

    /** Two paths of one shape with every coordinate halfway between them; null when their commands differ. */
    internal fun blend(
        a: String,
        b: String,
    ): String? {
        if (a.replace(NUMBER, "") != b.replace(NUMBER, "")) return null
        val others = NUMBER.findAll(b).map { it.value.toDouble() }.iterator()
        return a.replace(NUMBER) { "%.4f".format(Locale.US, (it.value.toDouble() + others.next()) / 2) }
    }
}
