package com.carlink.ui.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How far before a turn the alert starts (Settings › Cluster › Start alerting): one distance per
 * speed band, 0–35, 36–59, 60–69 and 70+ mph. Standard is the owner's (2026-10-23): 500, 1,000,
 * 1,500 and 2,600 ft. Earlier is twice that and Later 0.6 of it, so the three keep their order at
 * every speed. Until v29 each preset was a distance at 25 and at 60 mph with the warning time
 * interpolated between (Standard 0.25 mi and 1 mi).
 */
enum class AlertTiming(
    val code: String,
    override val label: String,
    /** Feet before the turn, per band of [BAND_TOPS_MPH]. */
    val feet: List<Int>,
) : Labelled {
    EARLIER("earlier", "Earlier", listOf(1000, 2000, 3000, 5200)),
    STANDARD("standard", "Standard", listOf(500, 1000, 1500, 2600)),
    LATER("later", "Later", listOf(300, 600, 900, 1560)),
    ;

    /** "Starts 500 ft before a turn up to 35 mph, 1,000 ft to 59, 1,500 ft to 69, 2,600 ft at 70+", for the Start alerting row. */
    val hint: String
        get() {
            val ft = feet.map { "%,d".format(it) }
            val tops = BAND_TOPS_MPH
            return "Starts ${ft[0]} ft before a turn up to ${tops[0]} mph, ${ft[1]} ft to ${tops[1]}, ${ft[2]} ft to ${tops[2]}, ${ft[3]} ft at ${tops[2] + 1}+"
        }

    /** Feet before the turn the alert starts at [mph]; a band runs to its top whole mph (35 is the first band). */
    fun feetAt(mph: Double): Int {
        val band = BAND_TOPS_MPH.indexOfFirst { mph < it + 0.5 }.let { if (it < 0) BAND_TOPS_MPH.size else it }
        return feet[band]
    }

    companion object {
        /** The top speed of each band but the last: 0–35, 36–59, 60–69, then 70 and up. */
        val BAND_TOPS_MPH = listOf(35, 59, 69)

        fun fromCode(code: String?): AlertTiming = entries.firstOrNull { it.code == code } ?: STANDARD
    }
}

/** One full flash: the normal frame, then the colored frame, half of [cycleMs] each. */
enum class FlashSpeed(
    val code: String,
    override val label: String,
    val cycleMs: Long,
) : Labelled {
    SLOW("slow", "Slow", 1500L),
    MEDIUM("medium", "Medium", 1000L),
    FAST("fast", "Fast", 500L),
    ;

    companion object {
        fun fromCode(code: String?): FlashSpeed = entries.firstOrNull { it.code == code } ?: MEDIUM
    }
}

/** The colored frame's inlay. */
enum class InlayColor(
    val code: String,
    override val label: String,
    val argb: Int,
) : Labelled {
    RED("red", "Red", 0xFFFF3B30.toInt()),
    AMBER("amber", "Amber", 0xFFFF9F0A.toInt()),
    GREEN("green", "Green", 0xFF34C759.toInt()),
    CYAN("cyan", "Cyan", 0xFF32D2F5.toInt()),
    MAGENTA("magenta", "Magenta", 0xFFFF2D92.toInt()),
    ;

    companion object {
        fun fromCode(code: String?): InlayColor = entries.firstOrNull { it.code == code } ?: RED
    }
}

/** Inlay keeps the icon's white outline; Outline adds a colored edge around it as well. */
enum class AlertStyle(
    val code: String,
    override val label: String,
) : Labelled {
    INLAY("inlay", "Inlay"),
    OUTLINE("outline", "Outline"),
    ;

    companion object {
        fun fromCode(code: String?): AlertStyle = entries.firstOrNull { it.code == code } ?: INLAY
    }
}

/** Settings › Cluster › Turn alert. Off by default. */
data class TurnAlertConfig(
    val enabled: Boolean = false,
    val timing: AlertTiming = AlertTiming.STANDARD,
    val speed: FlashSpeed = FlashSpeed.MEDIUM,
    val color: InlayColor = InlayColor.RED,
    val style: AlertStyle = AlertStyle.INLAY,
    val solidLast: Boolean = true,
)

/**
 * Durable [TurnAlertConfig]; read by the cluster session ([com.carlink.navigation.TurnAlert]). Kept
 * per driver ([DriverProfiles]): [reload] switches to the active driver's file.
 */
class TurnAlertStore private constructor(
    context: Context,
) {
    @Volatile private var prefs = open(context)
    private val state = MutableStateFlow(read(prefs))

    val flow: StateFlow<TurnAlertConfig> = state.asStateFlow()

    /** Reload shared settings. */
    fun reload(context: Context) {
        prefs = open(context)
        state.value = read(prefs)
    }

    val current: TurnAlertConfig get() = state.value

    fun setEnabled(on: Boolean) = update(current.copy(enabled = on))

    fun setTiming(timing: AlertTiming) = update(current.copy(timing = timing))

    fun setSpeed(speed: FlashSpeed) = update(current.copy(speed = speed))

    fun setColor(color: InlayColor) = update(current.copy(color = color))

    fun setStyle(style: AlertStyle) = update(current.copy(style = style))

    fun setSolidLast(on: Boolean) = update(current.copy(solidLast = on))

    private fun update(c: TurnAlertConfig) {
        prefs
            .edit()
            .putBoolean(KEY_ENABLED, c.enabled)
            .putString(KEY_TIMING, c.timing.code)
            .putString(KEY_SPEED, c.speed.code)
            .putString(KEY_COLOR, c.color.code)
            .putString(KEY_STYLE, c.style.code)
            .putBoolean(KEY_SOLID_LAST, c.solidLast)
            .apply()
        state.value = c
    }

    private fun open(context: Context): SharedPreferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun read(p: SharedPreferences): TurnAlertConfig =
        TurnAlertConfig(
            enabled = p.getBoolean(KEY_ENABLED, false),
            timing = AlertTiming.fromCode(p.getString(KEY_TIMING, null)),
            speed = FlashSpeed.fromCode(p.getString(KEY_SPEED, null)),
            color = InlayColor.fromCode(p.getString(KEY_COLOR, null)),
            style = AlertStyle.fromCode(p.getString(KEY_STYLE, null)),
            solidLast = p.getBoolean(KEY_SOLID_LAST, true),
        )

    companion object {
        // PERSISTENCE CONTRACT: renaming any of these orphans the stored choice. Profiles add
        // "@<mac>" to the file name ([DriverProfiles]).
        internal const val PREFS_NAME = "carlink_turn_alert"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_TIMING = "timing"
        private const val KEY_SPEED = "speed"
        private const val KEY_COLOR = "color"
        private const val KEY_STYLE = "style"
        private const val KEY_SOLID_LAST = "solid_last"

        @Volatile private var instance: TurnAlertStore? = null

        fun get(context: Context): TurnAlertStore =
            instance ?: synchronized(this) {
                instance ?: TurnAlertStore(context).also { instance = it }
            }

    }
}
