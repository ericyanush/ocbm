package com.carlink.av

/** Bounded diagnostics; timestamps are local clockNs(), never phone timestamps. */
internal class VideoTiming(private val clockNs: () -> Long = System::nanoTime) {
    private data class Frame(val arrivalNs: Long, val queuedNs: Long, var releasedNs: Long = 0)
    private val frames = LinkedHashMap<Long, Frame>()
    private var since = clockNs()
    private var lastArrival = 0L
    private var lastRendered = 0L
    private var messages = 0L
    private var bytes = 0L
    private var maxMessageBytes = 0
    private var presentations = 0L
    private var evicted = 0L
    private var unmatched = 0L
    private var maxArrivalGapUs = 0L
    private var maxRenderedGapUs = 0L
    private var maxDecodeUs = 0L
    private var maxPresentationUs = 0L
    private var maxArrivalToRenderUs = 0L
    private var maxCallbackLagUs = 0L

    @Synchronized fun reset() {
        frames.clear()
        lastArrival = 0; lastRendered = 0
        resetInterval(clockNs())
    }

    @Synchronized fun arrived(now: Long, size: Int) {
        if (lastArrival != 0L) maxArrivalGapUs = maxOf(maxArrivalGapUs, (now-lastArrival).coerceAtLeast(0)/1000)
        lastArrival = now; messages++; bytes += size; maxMessageBytes = maxOf(maxMessageBytes, size)
    }

    @Synchronized fun queued(ptsUs: Long, arrivalNs: Long) {
        if (frames.size >= 256) { frames.remove(frames.keys.first()); evicted++ }
        frames[ptsUs] = Frame(arrivalNs, clockNs())
    }

    @Synchronized fun released(ptsUs: Long) {
        val frame = frames[ptsUs] ?: return
        frame.releasedNs = clockNs()
        maxDecodeUs = maxOf(maxDecodeUs, (frame.releasedNs-frame.queuedNs).coerceAtLeast(0)/1000)
    }

    @Synchronized fun rendered(ptsUs: Long, renderedNs: Long) {
        val now = clockNs()
        presentations++
        if (lastRendered != 0L) maxRenderedGapUs = maxOf(maxRenderedGapUs, (renderedNs-lastRendered).coerceAtLeast(0)/1000)
        lastRendered = maxOf(lastRendered, renderedNs)
        maxCallbackLagUs = maxOf(maxCallbackLagUs, (now-renderedNs).coerceAtLeast(0)/1000)
        val frame = frames.remove(ptsUs)
        if (frame == null) { unmatched++; return }
        if (frame.releasedNs != 0L) maxPresentationUs = maxOf(maxPresentationUs, (renderedNs-frame.releasedNs).coerceAtLeast(0)/1000)
        maxArrivalToRenderUs = maxOf(maxArrivalToRenderUs, (renderedNs-frame.arrivalNs).coerceAtLeast(0)/1000)
    }

    @Synchronized fun report(): String? {
        val now = clockNs()
        if (now-since < 5_000_000_000L) return null
        val oldestAgeUs = frames.values.minOfOrNull { it.queuedNs }?.let { (now-it)/1000 } ?: 0
        val line = "video-timing intervalMs=${(now-since)/1_000_000} messages=$messages bytes=$bytes " +
            "maxMessageBytes=$maxMessageBytes maxArrivalGapUs=$maxArrivalGapUs presentations=$presentations " +
            "maxRenderedGapUs=$maxRenderedGapUs maxDecodeUs=$maxDecodeUs maxPresentationUs=$maxPresentationUs " +
            "maxArrivalToRenderUs=$maxArrivalToRenderUs maxCallbackLagUs=$maxCallbackLagUs " +
            "inflight=${frames.size} oldestInflightUs=$oldestAgeUs evicted=$evicted unmatched=$unmatched"
        resetInterval(now)
        return line
    }

    private fun resetInterval(now: Long) {
        since=now; messages=0; bytes=0; maxMessageBytes=0; presentations=0; evicted=0; unmatched=0
        maxArrivalGapUs=0; maxRenderedGapUs=0; maxDecodeUs=0; maxPresentationUs=0; maxArrivalToRenderUs=0; maxCallbackLagUs=0
    }
}
