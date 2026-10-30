package com.carlink.navigation

import android.content.Context
import android.util.Log
import androidx.car.app.model.CarIcon
import androidx.car.app.navigation.model.Destination
import androidx.car.app.model.Distance
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.car.app.navigation.model.Trip
import androidx.core.graphics.drawable.IconCompat
import com.carlink.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** Accumulated iAP2 route summary and indexed 0x5202 maneuver records from the OCBM metadata seam. */
data class CarPlayNavigationSnapshot(
    val active: Boolean = false,
    val routeState: Int = 0,
    val currentIndex: Int? = null,
    val currentRoad: String? = null,
    val destination: String? = null,
    val etaEpochSeconds: Long? = null,
    val remainingSeconds: Long? = null,
    val remainingMeters: Int? = null,
    val remainingText: String? = null,
    val remainingUnits: Int? = null,
    val maneuverDistanceMeters: Int? = null,
    val maneuverDistanceText: String? = null,
    val maneuverDistanceUnits: Int? = null,
    val maneuvers: Map<Int, CarPlayManeuver> = emptyMap(),
) {
    fun currentManeuver(): CarPlayManeuver? = currentIndex?.let(maneuvers::get)

    fun nextManeuver(): CarPlayManeuver? {
        val index = currentIndex ?: return null
        return maneuvers[index + 1]
    }
}

data class CarPlayManeuver(
    val index: Int,
    val description: String? = null,
    val type: Int? = null,
    val roadAfter: String? = null,
    val distanceMeters: Int? = null,
    val distanceText: String? = null,
    val distanceUnits: Int? = null,
    val drivingSide: Int? = null,
    val junctionType: Int? = null,
    val exitInfo: String? = null,
)

/**
 * Reduces the receiver's iAP2 0x5201/0x5202 JSON deltas. The Rust receiver already parses these
 * messages and forwards `routeGuidance` and `maneuver` objects over CH_METADATA; this reducer joins
 * them by the protocol maneuver index and selects the index named by 0x5201.
 */
object CarPlayNavigationState {
    private val mutable = MutableStateFlow(CarPlayNavigationSnapshot())
    val state = mutable.asStateFlow()

    @Synchronized
    fun accept(json: JSONObject) {
        when (json.optString("kind")) {
            "routeGuidance" -> acceptRoute(json)
            "maneuver" -> acceptManeuver(json)
        }
    }

    @Synchronized
    fun clear(reason: String) {
        if (mutable.value != CarPlayNavigationSnapshot()) {
            Log.i(TAG, "clearing route state: $reason")
            mutable.value = CarPlayNavigationSnapshot()
        }
    }

    private fun acceptRoute(json: JSONObject) {
        val old = mutable.value
        val routeState = json.optIntOrNull("routeGuidanceState") ?: old.routeState
        // Apple route state 0 means no route; 2 means arrived. Retire the old maneuver index map
        // so an immediate next trip cannot accidentally reuse the previous route's records.
        if (routeState == 0 || routeState == 2) {
            mutable.value = CarPlayNavigationSnapshot(routeState = routeState)
            Log.i(TAG, "route ended state=$routeState")
            return
        }

        val newDestination = json.optStringOrNull("destinationName")
        // A fresh active route following an inactive state starts with no retained maneuvers.
        val routeChanged = !old.active || (newDestination != null && newDestination != old.destination)
        val base = if (routeChanged) old.copy(maneuvers = emptyMap(), currentIndex = null) else old
        val next = base.copy(
            active = routeState != 0,
            routeState = routeState,
            currentIndex = json.optIntOrNull("currentManeuverIndex") ?: base.currentIndex,
            currentRoad = json.optStringOrNull("currentRoadName") ?: base.currentRoad,
            destination = json.optStringOrNull("destinationName") ?: base.destination,
            etaEpochSeconds = json.optLongOrNull("etaEpoch") ?: base.etaEpochSeconds,
            remainingSeconds = json.optLongOrNull("timeRemainingSec") ?: base.remainingSeconds,
            remainingMeters = json.optIntOrNull("distanceRemainingM") ?: base.remainingMeters,
            remainingText = json.optStringOrNull("distanceRemainingText") ?: base.remainingText,
            remainingUnits = json.optIntOrNull("distanceRemainingUnits") ?: base.remainingUnits,
            maneuverDistanceMeters = json.optIntOrNull("distanceToManeuverM") ?: base.maneuverDistanceMeters,
            maneuverDistanceText = json.optStringOrNull("distanceToManeuverText") ?: base.maneuverDistanceText,
            maneuverDistanceUnits = json.optIntOrNull("distanceToManeuverUnits") ?: base.maneuverDistanceUnits,
        )
        mutable.value = next
        Log.d(TAG, "route update state=$routeState index=${next.currentIndex} maneuvers=${next.maneuvers.size}")
    }

    private fun acceptManeuver(json: JSONObject) {
        val index = json.optIntOrNull("index") ?: return
        val old = mutable.value
        val previous = old.maneuvers[index] ?: CarPlayManeuver(index)
        val updated = previous.copy(
            description = json.optStringOrNull("description") ?: previous.description,
            type = json.optIntOrNull("maneuverType") ?: previous.type,
            roadAfter = json.optStringOrNull("afterManeuverRoadName") ?: previous.roadAfter,
            distanceMeters = json.optIntOrNull("distanceM") ?: previous.distanceMeters,
            distanceText = json.optStringOrNull("distanceText") ?: previous.distanceText,
            distanceUnits = json.optIntOrNull("distanceUnits") ?: previous.distanceUnits,
            drivingSide = json.optIntOrNull("drivingSide") ?: previous.drivingSide,
            junctionType = json.optIntOrNull("junctionType") ?: previous.junctionType,
            exitInfo = json.optStringOrNull("exitInfo") ?: previous.exitInfo,
        )
        mutable.value = old.copy(maneuvers = old.maneuvers + (index to updated))
        Log.d(TAG, "maneuver index=$index type=${updated.type} road=${updated.roadAfter}")
    }

    private fun JSONObject.optIntOrNull(name: String): Int? = if (has(name) && !isNull(name)) optInt(name) else null
    private fun JSONObject.optLongOrNull(name: String): Long? = if (has(name) && !isNull(name)) optLong(name) else null
    private fun JSONObject.optStringOrNull(name: String): String? =
        if (has(name) && !isNull(name)) optString(name).takeIf(String::isNotBlank) else null

    private const val TAG = "CarlinkNavState"
}

/** Builds the Car App Library trip payload used by AAOS cluster/HUD navigation consumers. */
object CarPlayTripBuilder {
    fun build(snapshot: CarPlayNavigationSnapshot, context: Context): Trip {
        val now = ZonedDateTime.now()
        val destinationEta = snapshot.etaEpochSeconds?.let {
            ZonedDateTime.ofInstant(Instant.ofEpochSecond(it), ZoneId.systemDefault())
        } ?: now.plusSeconds(snapshot.remainingSeconds ?: 0L)
        val trip = Trip.Builder()
        snapshot.currentManeuver()?.let { current ->
            trip.addStep(buildStep(current, snapshot.currentRoad, context), estimate(snapshot.maneuverDistanceMeters, snapshot.maneuverDistanceText, snapshot.maneuverDistanceUnits, destinationEta))
            snapshot.nextManeuver()?.let { next ->
                trip.addStep(buildStep(next, null, context), estimate(next.distanceMeters, next.distanceText, next.distanceUnits, destinationEta))
            }
        }
        if (snapshot.destination != null || (snapshot.remainingMeters ?: 0) > 0) {
            val destinationBuilder = Destination.Builder()
            snapshot.destination?.let(destinationBuilder::setName)
            val destination = destinationBuilder.build()
            trip.addDestination(destination, estimate(snapshot.remainingMeters, snapshot.remainingText, snapshot.remainingUnits, destinationEta))
        }
        return trip.setLoading(false).build()
    }

    private fun buildStep(m: CarPlayManeuver, currentRoad: String?, context: Context): Step {
        val type = maneuverType(m.type ?: 0, m.drivingSide == 1)
        val builder = Maneuver.Builder(type)
        val cpType = m.type ?: 0
        if (cpType in 28..46) builder.setRoundaboutExitNumber(cpType - 27)
        builder.setIcon(CarIcon.Builder(IconCompat.createWithResource(context, maneuverDrawable(cpType))).build())
        val step = Step.Builder().setManeuver(builder.build())
        (m.description ?: currentRoad ?: m.roadAfter)?.let(step::setCue)
        m.roadAfter?.let(step::setRoad)
        return step.build()
    }

    private fun estimate(meters: Int?, display: String?, units: Int?, eta: ZonedDateTime): TravelEstimate {
        val rawMeters = meters?.toDouble()
        val displayValue = display?.toDoubleOrNull()
        val unit = when (units) {
            0 -> Distance.UNIT_KILOMETERS_P1
            1 -> Distance.UNIT_MILES_P1
            2 -> Distance.UNIT_METERS
            3 -> Distance.UNIT_YARDS
            4 -> Distance.UNIT_FEET
            else -> if ((rawMeters ?: 0.0) >= 1000) Distance.UNIT_KILOMETERS_P1 else Distance.UNIT_METERS
        }
        val shown = displayValue ?: when (units) {
            0 -> (rawMeters ?: 0.0) / 1000.0
            1 -> (rawMeters ?: 0.0) / 1609.344
            2 -> rawMeters ?: 0.0
            3 -> (rawMeters ?: 0.0) / 0.9144
            4 -> (rawMeters ?: 0.0) / 0.3048
            else -> if ((rawMeters ?: 0.0) >= 1000) (rawMeters ?: 0.0) / 1000.0 else rawMeters ?: 0.0
        }
        return TravelEstimate.Builder(Distance.create(shown, unit), eta).build()
    }

    private fun maneuverDrawable(cp: Int): Int = when (cp) {
        1 -> R.drawable.cp_maneuver_01_left_turn
        2 -> R.drawable.cp_maneuver_02_right_turn
        4 -> R.drawable.cp_maneuver_04_uturn
        18 -> R.drawable.cp_maneuver_18_start_route_with_uturn
        19 -> R.drawable.cp_maneuver_19_uturn_at_roundabout
        26 -> R.drawable.cp_maneuver_26_uturn_when_possible
        6 -> R.drawable.cp_maneuver_06_enter_roundabout
        7 -> R.drawable.cp_maneuver_07_exit_roundabout
        in 28..46 -> roundaboutDrawable(cp - 27)
        8 -> R.drawable.cp_maneuver_08_off_ramp
        22 -> R.drawable.cp_maneuver_22_highway_off_ramp_left
        9 -> R.drawable.cp_maneuver_09_on_ramp
        10 -> R.drawable.cp_maneuver_10_arrive_end_of_navigation
        12 -> R.drawable.cp_maneuver_12_arrive_at_destination
        13 -> R.drawable.cp_maneuver_13_keep_left
        14, 51 -> R.drawable.cp_maneuver_14_keep_right
        15 -> R.drawable.cp_maneuver_15_enter_ferry
        16 -> R.drawable.cp_maneuver_16_exit_ferry
        17 -> R.drawable.cp_maneuver_17_change_ferry
        11 -> R.drawable.cp_maneuver_11_start_route
        20 -> R.drawable.cp_maneuver_20_left_turn_at_end
        21 -> R.drawable.cp_maneuver_21_right_turn_at_end
        23 -> R.drawable.cp_maneuver_23_highway_off_ramp_right
        24 -> R.drawable.cp_maneuver_24_arrive_at_destination_left
        25 -> R.drawable.cp_maneuver_25_arrive_at_destination_right
        27 -> R.drawable.cp_maneuver_27_arrive_end_of_directions
        47 -> R.drawable.cp_maneuver_47_sharp_left_turn
        48 -> R.drawable.cp_maneuver_48_sharp_right_turn
        49 -> R.drawable.cp_maneuver_49_slight_left_turn
        50 -> R.drawable.cp_maneuver_50_slight_right_turn
        52 -> R.drawable.cp_maneuver_52_change_highway_left
        53 -> R.drawable.cp_maneuver_53_change_highway_right
        else -> R.drawable.cp_maneuver_03_straight_ahead
    }

    private fun roundaboutDrawable(exit: Int): Int = when (exit) {
        1 -> R.drawable.cp_maneuver_28_roundabout_exit_1
        2 -> R.drawable.cp_maneuver_29_roundabout_exit_2
        3 -> R.drawable.cp_maneuver_30_roundabout_exit_3
        4 -> R.drawable.cp_maneuver_31_roundabout_exit_4
        5 -> R.drawable.cp_maneuver_32_roundabout_exit_5
        6 -> R.drawable.cp_maneuver_33_roundabout_exit_6
        7 -> R.drawable.cp_maneuver_34_roundabout_exit_7
        8 -> R.drawable.cp_maneuver_35_roundabout_exit_8
        9 -> R.drawable.cp_maneuver_36_roundabout_exit_9
        10 -> R.drawable.cp_maneuver_37_roundabout_exit_10
        11 -> R.drawable.cp_maneuver_38_roundabout_exit_11
        12 -> R.drawable.cp_maneuver_39_roundabout_exit_12
        13 -> R.drawable.cp_maneuver_40_roundabout_exit_13
        14 -> R.drawable.cp_maneuver_41_roundabout_exit_14
        15 -> R.drawable.cp_maneuver_42_roundabout_exit_15
        16 -> R.drawable.cp_maneuver_43_roundabout_exit_16
        17 -> R.drawable.cp_maneuver_44_roundabout_exit_17
        18 -> R.drawable.cp_maneuver_45_roundabout_exit_18
        else -> R.drawable.cp_maneuver_46_roundabout_exit_19
    }

    private fun maneuverType(cp: Int, leftDrive: Boolean): Int = when (cp) {
        0, 3, 5 -> Maneuver.TYPE_STRAIGHT
        1, 20 -> Maneuver.TYPE_TURN_NORMAL_LEFT
        2, 21 -> Maneuver.TYPE_TURN_NORMAL_RIGHT
        4, 18, 26 -> if (leftDrive) Maneuver.TYPE_U_TURN_RIGHT else Maneuver.TYPE_U_TURN_LEFT
        6 -> if (leftDrive) Maneuver.TYPE_ROUNDABOUT_ENTER_CW else Maneuver.TYPE_ROUNDABOUT_ENTER_CCW
        7 -> if (leftDrive) Maneuver.TYPE_ROUNDABOUT_EXIT_CW else Maneuver.TYPE_ROUNDABOUT_EXIT_CCW
        8 -> if (leftDrive) Maneuver.TYPE_OFF_RAMP_NORMAL_LEFT else Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT
        22 -> Maneuver.TYPE_OFF_RAMP_NORMAL_LEFT
        9 -> if (leftDrive) Maneuver.TYPE_ON_RAMP_NORMAL_LEFT else Maneuver.TYPE_ON_RAMP_NORMAL_RIGHT
        10, 12, 27 -> Maneuver.TYPE_DESTINATION
        11 -> Maneuver.TYPE_DEPART
        13, 52 -> Maneuver.TYPE_KEEP_LEFT
        14, 51, 53 -> Maneuver.TYPE_KEEP_RIGHT
        15, 16, 17 -> Maneuver.TYPE_FERRY_BOAT
        19 -> if (leftDrive) Maneuver.TYPE_ROUNDABOUT_ENTER_CW else Maneuver.TYPE_ROUNDABOUT_ENTER_CCW
        in 28..46 -> if (leftDrive) Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW else Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW
        23 -> Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT
        24 -> Maneuver.TYPE_DESTINATION_LEFT
        25 -> Maneuver.TYPE_DESTINATION_RIGHT
        47 -> Maneuver.TYPE_TURN_SHARP_LEFT
        48 -> Maneuver.TYPE_TURN_SHARP_RIGHT
        49 -> Maneuver.TYPE_TURN_SLIGHT_LEFT
        50 -> Maneuver.TYPE_TURN_SLIGHT_RIGHT
        else -> Maneuver.TYPE_UNKNOWN
    }
}
