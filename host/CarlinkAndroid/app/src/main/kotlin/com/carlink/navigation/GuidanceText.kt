package com.carlink.navigation

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The words of the pane's Directions card, from the metres and seconds of the iPhone's route
 * guidance. [DistanceFormatter] does the same job for the cluster but speaks the Car App Library's
 * `Distance`; the pane draws its own text, the number large and the unit small, so this returns the
 * two parts. Same unit policy as the cluster: feet under about a thousand, then miles, in the four
 * imperial countries; metres under a kilometre, then kilometres, everywhere else. Short distances
 * are rounded to fifty, never below fifty; long ones lose the decimal from ten.
 */
object GuidanceText {
    /** A distance as the card prints it: the number and its unit. */
    data class Distance(
        val value: String,
        val unit: String,
    ) {
        override fun toString(): String = "$value $unit"
    }

    private const val METERS_PER_FOOT = 0.3048
    private const val METERS_PER_MILE = 1609.344
    private const val FEET_THRESHOLD_M = 305
    private const val KM_THRESHOLD_M = 1000
    private const val SHORT_STEP = 50
    private const val NO_DECIMAL_FROM = 10.0
    private const val SECONDS_PER_MINUTE = 60
    private const val MINUTES_PER_HOUR = 60
    private val IMPERIAL_COUNTRIES = setOf("US", "GB", "MM", "LR")

    fun imperial(locale: Locale): Boolean = locale.country in IMPERIAL_COUNTRIES

    fun distance(
        meters: Int,
        imperial: Boolean,
    ): Distance =
        if (imperial) {
            if (meters < FEET_THRESHOLD_M) Distance(short(meters / METERS_PER_FOOT), "ft") else Distance(long(meters / METERS_PER_MILE), "mi")
        } else {
            if (meters < KM_THRESHOLD_M) Distance(short(meters.toDouble()), "m") else Distance(long(meters / KM_THRESHOLD_M.toDouble()), "km")
        }

    /** Time to go: "23 min", "1 h 5 min", "< 1 min". */
    fun duration(seconds: Int): String {
        val minutes = (seconds.coerceAtLeast(0) + SECONDS_PER_MINUTE / 2) / SECONDS_PER_MINUTE
        return when {
            minutes < 1 -> "< 1 min"
            minutes < MINUTES_PER_HOUR -> "$minutes min"
            minutes % MINUTES_PER_HOUR == 0 -> "${minutes / MINUTES_PER_HOUR} h"
            else -> "${minutes / MINUTES_PER_HOUR} h ${minutes % MINUTES_PER_HOUR} min"
        }
    }

    /** The clock time [seconds] from [now], in the head unit's 12- or 24-hour style. */
    fun arrival(
        seconds: Int,
        now: LocalTime,
        twentyFourHour: Boolean,
    ): String = now.plusSeconds(seconds.coerceAtLeast(0).toLong()).format(if (twentyFourHour) CLOCK_24 else CLOCK_12)

    private fun short(units: Double): String = ((units / SHORT_STEP).roundToInt() * SHORT_STEP).coerceAtLeast(SHORT_STEP).toString()

    private fun long(units: Double): String = if (units >= NO_DECIMAL_FROM) units.roundToInt().toString() else String.format(Locale.ROOT, "%.1f", units)

    private val CLOCK_12: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm", Locale.ROOT)
    private val CLOCK_24: DateTimeFormatter = DateTimeFormatter.ofPattern("H:mm", Locale.ROOT)
}
