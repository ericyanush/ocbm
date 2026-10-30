package com.carlink.ui.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface Labelled { val label: String }

/** How the cluster turn icon shows lanes (Settings › Cluster › Lanes in the turn icon). */
enum class LaneIconMode(
    val code: String,
    override val label: String,
) : Labelled {
    OFF("off", "Off"),
    ARROW("arrow", "Arrow over lanes"),
    LANES("lanes", "Lanes only"),
    ;

    companion object {
        fun fromCode(code: String?): LaneIconMode = entries.firstOrNull { it.code == code } ?: OFF
    }
}

/**
 * Settings › Cluster › Lane guidance (design "Lane guidance tests", 2026-10-21; a normal setting
 * since 2026-10-22). Kept per driver ([DriverProfiles]); factory defaults ask the iPhone for lanes
 * and show the lane strip, white, with no lanes in the turn icon.
 *
 * [ask] is the only one that reaches the adapter: it widens the metadata Carlink pushes on the next
 * `CT_SUBSCRIBE` so the iPhone is asked for iAP2 lane guidance. The rest change only what Carlink
 * draws for GM's cluster and HUD. [laneColor] is the color of the lanes to be in, from the turn
 * alert's palette (null = white). [refusedAtMs] is when [ask] last turned itself off because the
 * iPhone rejected the identification that carried it (0 = never).
 */
data class LaneGuidanceConfig(
    val ask: Boolean = true,
    val icon: LaneIconMode = LaneIconMode.OFF,
    val strip: Boolean = true,
    val laneColor: InlayColor? = null,
    val refusedAtMs: Long = 0L,
) {
    /** True when anything is switched on, for the Cluster category's summary. */
    val anyOn: Boolean get() = ask || icon != LaneIconMode.OFF || strip

    /** ARGB the lanes to be in are drawn in. */
    val laneArgb: Int get() = laneColor?.argb ?: WHITE_ARGB

    private companion object {
        const val WHITE_ARGB = 0xFFFFFFFF.toInt()
    }
}

/** Durable [LaneGuidanceConfig]; read by the cluster step builder and the adapter config. */
class LaneGuidanceStore private constructor(
    context: Context,
) {
    @Volatile private var prefs = open(context)
    private val state = MutableStateFlow(read(prefs))

    val flow: StateFlow<LaneGuidanceConfig> = state.asStateFlow()

    /** Reload shared settings. */
    fun reload(context: Context) {
        prefs = open(context)
        state.value = read(prefs)
    }

    val current: LaneGuidanceConfig get() = state.value

    fun setAsk(on: Boolean) = update(current.copy(ask = on))

    fun setIcon(mode: LaneIconMode) = update(current.copy(icon = mode))

    fun setStrip(on: Boolean) = update(current.copy(strip = on))

    fun setLaneColor(color: InlayColor?) = update(current.copy(laneColor = color))

    /** The iPhone refused an identification that asked for lanes: stop asking. */
    fun markRefused(atMs: Long) = update(current.copy(ask = false, refusedAtMs = atMs))

    private fun update(c: LaneGuidanceConfig) {
        val edit =
            prefs
                .edit()
                .putBoolean(KEY_ASK, c.ask)
                .putString(KEY_ICON, c.icon.code)
                .putBoolean(KEY_STRIP, c.strip)
                .putString(KEY_LANE_COLOR, c.laneColor?.code ?: WHITE)
                .putLong(KEY_REFUSED_AT, c.refusedAtMs)
        RETIRED_KEYS.forEach { edit.remove(it) }
        edit.apply()
        state.value = c
    }

    private fun open(context: Context): SharedPreferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun read(p: SharedPreferences): LaneGuidanceConfig =
        LaneGuidanceConfig(
            ask = p.getBoolean(KEY_ASK, true),
            icon = LaneIconMode.fromCode(p.getString(KEY_ICON, null)),
            strip = p.getBoolean(KEY_STRIP, true),
            laneColor = p.getString(KEY_LANE_COLOR, null).let { code -> InlayColor.entries.firstOrNull { it.code == code } },
            refusedAtMs = p.getLong(KEY_REFUSED_AT, 0L),
        )

    companion object {
        // PERSISTENCE CONTRACT: renaming any of these orphans the stored choice. The file keeps its
        // name from when these were the lane guidance tests; profiles add "@<mac>" ([DriverProfiles]).
        internal const val PREFS_NAME = "carlink_lane_tests"
        private const val KEY_ASK = "ask"
        private const val KEY_ICON = "icon"
        private const val KEY_STRIP = "strip"
        private const val KEY_LANE_COLOR = "lane_color"
        private const val WHITE = "white"
        private const val KEY_REFUSED_AT = "refused_at"

        /** The lane source and the two calibration cards, removed 2026-10-22; cleared on the next save. */
        private val RETIRED_KEYS = listOf("source", "card", "slot_card")

        @Volatile private var instance: LaneGuidanceStore? = null

        fun get(context: Context): LaneGuidanceStore =
            instance ?: synchronized(this) {
                instance ?: LaneGuidanceStore(context).also { instance = it }
            }

    }
}
