package com.carlink.av

/** A stale frame is not proof of a live session. Probe before covering a quiet/static screen. */
internal class ProjectionLiveness {
    data class Decision(val visible: Boolean, val requestKeyframe: Boolean = false, val needsRecovery: Boolean = false)

    private var owner: Pair<Long, Long>? = null
    private var probedAt: Long? = null
    private var framesAtProbe = 0L

    fun update(epoch: Long, generation: Long, frames: Long, sessionLive: Boolean,
               avIdleMs: Long?, now: Long): Decision {
        val nextOwner = epoch to generation
        if (owner != nextOwner) {
            owner = nextOwner
            probedAt = null
        }
        if (frames == 0L || !sessionLive) {
            probedAt = null
            return Decision(false)
        }
        // A quiet video stream is normal while music is playing. Require ALL A/V to be idle,
        // then ask the phone for a frame and give it time to respond. Never tear down here.
        if (avIdleMs == null || avIdleMs < 10_000L ||
            (probedAt != null && frames != framesAtProbe)) {
            probedAt = null
            return Decision(true)
        }
        val probe = probedAt
        if (probe == null) {
            probedAt = now
            framesAtProbe = frames
            return Decision(true, requestKeyframe = true)
        }
        // A call can legitimately leave the screen and A/V quiet while the control session is
        // alive. Keep the last frame and touch access, but expose recovery controls after a probe.
        return Decision(visible = true, needsRecovery = now - probe >= 5_000L)
    }
}
