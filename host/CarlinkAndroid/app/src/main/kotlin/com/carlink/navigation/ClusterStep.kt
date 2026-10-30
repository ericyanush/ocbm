package com.carlink.navigation

import android.content.Context
import androidx.car.app.navigation.model.Lane
import androidx.car.app.navigation.model.LaneDirection
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.Step
import com.carlink.logging.Logger
import com.carlink.logging.logWarn
import com.carlink.ui.settings.LaneGuidanceStore

/**
 * The step being approached, as both cluster paths send it: the Trip ([TripBuilder]) and the
 * cluster screen's NavigationTemplate ([com.carlink.cluster.CarlinkClusterSession]).
 *
 * Lane guidance (Settings › Cluster) acts here, all off by default: the iPhone's lanes drawn into
 * the turn icon ([LaneIconComposer]), and Step lanes plus a lane image for GM to place itself. With
 * it off this is exactly the step Carlink always sent.
 */
object ClusterStep {
    fun primary(
        state: NavigationState,
        context: Context,
    ): Step {
        // Turn alert (Settings › Cluster): the normal or colored frame while it is on, else unchanged.
        val base = TurnAlert.decorate(ManeuverMapper.buildManeuver(state, context), context)
        val cfg = LaneGuidanceStore.get(context).current
        val lanes = state.lanes
        val step =
            runCatching {
                val builder = Step.Builder().setManeuver(LaneIconComposer.maneuver(base, lanes, cfg, context))
                state.stepCue?.let { builder.setCue(it) }
                if (cfg.strip && lanes.isNotEmpty()) {
                    addLanes(builder, lanes)
                    builder.setLanesImage(LaneIconComposer.lanesImage(lanes, cfg.laneArgb))
                }
                builder.build()
            }
        step.exceptionOrNull()?.let { logWarn("[LANES] lane step refused (${it.message}); sending the plain step", tag = Logger.Tags.NAVI) }
        return step.getOrNull() ?: plain(base, state)
    }

    private fun addLanes(
        builder: Step.Builder,
        lanes: List<LaneHint>,
    ) {
        lanes.forEach { lane ->
            val directions = Lane.Builder().addDirection(LaneDirection.create(LaneGuidance.shapeFor(lane.angle), lane.recommended))
            lane.others.forEach { directions.addDirection(LaneDirection.create(LaneGuidance.shapeFor(it), false)) }
            builder.addLane(directions.build())
        }
    }

    private fun plain(
        base: Maneuver,
        state: NavigationState,
    ): Step {
        val builder = Step.Builder().setManeuver(base)
        state.stepCue?.let { builder.setCue(it) }
        return builder.build()
    }
}
