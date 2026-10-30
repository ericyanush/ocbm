package com.carlink.navigation

import android.content.Context
import androidx.car.app.model.CarIcon
import androidx.car.app.navigation.model.Maneuver
import com.carlink.logging.Logger
import com.carlink.logging.logWarn
import com.carlink.ui.settings.AlertTiming
import com.carlink.ui.settings.TurnAlertConfig
import com.carlink.ui.settings.TurnAlertStore
import kotlin.math.abs

/**
 * Turn alert (Settings › Cluster › Turn alert, v17): as the car nears the next turn, the cluster
 * and HUD turn icon flashes between its normal frame and a colored one, then holds the colored
 * frame for the last [SOLID_SECONDS] if the driver chose that.
 *
 * WHEN. Start alerting sets a distance before the turn for each speed band ([AlertTiming.feetAt];
 * Standard: 500 ft to 35 mph, 1,000 ft to 59, 1,500 ft to 69, 2,600 ft from 70).
 * The speed is the truck's own ([onCarSpeed], from [com.carlink.car.VehicleData]; owner, 10-26: use
 * these speed values for the flashing turn icon) while a reading is under [CAR_SPEED_FRESH_MS] old.
 * Without one (speed permission refused, another car) it is the rate the iPhone's distance-to-turn
 * counts down over the last few updates. Below [MOVING_MPS] (stopped, or the speed is not known yet
 * after a new step) nothing flashes.
 *
 * HOW. [observe] takes each new navigation state; the cluster session's ticker asks [phase] and
 * flips [coloredBeat] at the flash rate, and [decorate] picks the frame for the trip. While the
 * icon is flashing the ticker is the only thing that sends trips, so the cluster sees one change
 * per half cycle and no repeats of the frame already on screen. Both frames come from
 * [TurnAlertFrames]: drawn here, same size and position, reused for the whole step.
 */
object TurnAlert {
    enum class Phase { NORMAL, FLASH, SOLID }

    /** The final approach held on the colored frame when Solid for the last 3 seconds is on. */
    const val SOLID_SECONDS = 3.0

    const val MPS_PER_MPH = 0.44704
    const val METERS_PER_FOOT = 0.3048

    /** About 4.5 mph: slower than this counts as stopped. */
    private const val MOVING_MPS = 2.0

    /** Speed is the countdown over this window... */
    private const val WINDOW_MS = 5_000L

    /** ...once it spans at least this long. */
    private const val MIN_SPAN_MS = 1_500L

    /** The distance growing by more than this means a new step or a re-route: start over. */
    private const val JUMP_M = 30

    /** The truck's speed counts while its last reading is this recent; after that the countdown's. */
    private const val CAR_SPEED_FRESH_MS = 3_000L

    /**
     * CPManeuverType values that never flash: the straight arrows, noTurn (0), straightAhead (3) and
     * followRoad (5), which ask nothing of the driver; and endOfNavigation (10), proceedToRoute (11),
     * arrived (12), arrivedLeft (24), arrivedRight (25), endOfDirections (27).
     */
    private val QUIET_TYPES = setOf(0, 3, 5, 10, 11, 12, 24, 25, 27)

    private class Sample(
        val atMs: Long,
        val meters: Int,
    )

    private val samples = ArrayDeque<Sample>()
    private var stepKey: Pair<Int, String?>? = null

    @Volatile private var active = false

    @Volatile private var maneuverType = 0

    @Volatile private var speedMps = 0.0

    @Volatile private var lastMeters = 0

    @Volatile private var lastAtMs = 0L

    /** The truck's own speed ([onCarSpeed]) and when it came; [carAtMs] below 0 is none yet. */
    @Volatile private var carMps = 0.0

    @Volatile private var carAtMs = -1L

    /** The phase the cluster session last acted on. */
    @Volatile var phaseNow = Phase.NORMAL

    /** Which frame a flashing icon shows next. Flipped by the cluster session's ticker. */
    @Volatile var coloredBeat = false

    /** Distance before the turn where the alert starts, at [mps]: the speed band's distance. */
    fun startMeters(
        mps: Double,
        timing: AlertTiming,
    ): Double = timing.feetAt(mps / MPS_PER_MPH) * METERS_PER_FOOT

    /**
     * The truck's speed, [nowMs] on the clock [observe] and [phase] use (elapsed realtime). Reversing
     * reads as moving: the car reports it negative.
     */
    fun onCarSpeed(
        mps: Double,
        nowMs: Long,
    ) {
        carMps = abs(mps)
        carAtMs = nowMs
    }

    private fun carFresh(nowMs: Long): Boolean = carAtMs >= 0 && nowMs - carAtMs <= CAR_SPEED_FRESH_MS

    /** The truck's speed while fresh, else the countdown's. */
    private fun speedAt(nowMs: Long): Double = if (carFresh(nowMs)) carMps else speedMps

    /** Feed each NEW navigation state, never a re-send of the same one. */
    @Synchronized
    fun observe(
        state: NavigationState,
        nowMs: Long,
    ) {
        active = state.isActive
        maneuverType = state.maneuverType
        val key = state.maneuverType to state.roadName
        val jumped = samples.lastOrNull()?.let { state.remainDistance > it.meters + JUMP_M } ?: false
        if (!state.isActive || key != stepKey || jumped) {
            samples.clear()
            stepKey = key
            speedMps = 0.0
        }
        samples.addLast(Sample(nowMs, state.remainDistance))
        while (samples.size > 1 && nowMs - samples.first().atMs > WINDOW_MS) samples.removeFirst()
        val first = samples.first()
        val span = nowMs - first.atMs
        if (span >= MIN_SPAN_MS) {
            speedMps = ((first.meters - state.remainDistance) * 1000.0 / span).coerceAtLeast(0.0)
        }
        lastMeters = state.remainDistance
        lastAtMs = nowMs
    }

    /** What the icon should be doing at [nowMs]. The distance is carried forward between updates. */
    fun phase(
        cfg: TurnAlertConfig,
        nowMs: Long,
    ): Phase {
        val v = speedAt(nowMs)
        return when {
            !cfg.enabled || !active || maneuverType in QUIET_TYPES || v < MOVING_MPS -> Phase.NORMAL
            else -> {
                val remain = (lastMeters - v * (nowMs - lastAtMs) / 1000.0).coerceAtLeast(0.0)
                when {
                    remain > startMeters(v, cfg.timing) -> Phase.NORMAL
                    cfg.solidLast && remain / v <= SOLID_SECONDS -> Phase.SOLID
                    else -> Phase.FLASH
                }
            }
        }
    }

    /** Speed and carried-forward distance, for the phase-change log line. */
    fun describe(nowMs: Long): String {
        val v = speedAt(nowMs)
        val remain = (lastMeters - v * (nowMs - lastAtMs) / 1000.0).coerceAtLeast(0.0)
        val from = if (carFresh(nowMs)) "truck" else "countdown"
        return "%.0f mph (%s), %.0f m to the turn".format(v / MPS_PER_MPH, from, remain)
    }

    /** [base] with the frame for [phaseNow]; [base] itself outside an alert. */
    fun decorate(
        base: Maneuver,
        context: Context,
    ): Maneuver {
        val p = phaseNow
        if (p == Phase.NORMAL) return base
        val cfg = TurnAlertStore.get(context).current
        val icon = base.icon ?: return base
        val frames = TurnAlertFrames.frames(icon, cfg.color.argb, cfg.style, context) ?: return base
        val pick: CarIcon = if (p == Phase.SOLID || coloredBeat) frames.colored else frames.normal
        return runCatching {
            val b = Maneuver.Builder(base.type)
            if (base.roundaboutExitNumber > 0) b.setRoundaboutExitNumber(base.roundaboutExitNumber)
            if (base.roundaboutExitAngle > 0) b.setRoundaboutExitAngle(base.roundaboutExitAngle)
            b.setIcon(pick).build()
        }.getOrElse {
            logWarn("[TURN_ALERT] could not rebuild the maneuver (${it.message}); sending the plain one", tag = Logger.Tags.NAVI)
            base
        }
    }

    /** Forget the step being tracked (tests). */
    @Synchronized
    internal fun reset() {
        samples.clear()
        stepKey = null
        active = false
        maneuverType = 0
        speedMps = 0.0
        lastMeters = 0
        lastAtMs = 0L
        carMps = 0.0
        carAtMs = -1L
        phaseNow = Phase.NORMAL
        coloredBeat = false
    }
}
