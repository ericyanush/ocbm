package com.carlink.ui.components

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceControl
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.os.Build
import com.carlink.logging.logInfo
import com.carlink.logging.logWarn

/**
 * SurfaceView for H.264 decoding via HWC overlay (lower latency than TextureView).
 * Forwards Surface lifecycle and touch events to a [Callback].
 *
 * Threading: SurfaceHolder.Callback is delivered on the main thread (Android contract).
 * The [callback] property is a plain mutable var with no @Volatile/synchronization;
 * reads from other threads may see stale values, and reassigning mid-lifecycle can
 * produce orphaned create-without-destroy (or destroy-without-create) pairs for
 * the replaced consumer. Assign [callback] from the main thread only, ideally before
 * the surface is attached to the window.
 */
class VideoSurfaceView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : SurfaceView(context, attrs, defStyleAttr),
        SurfaceHolder.Callback {
        /**
         * Consumer contract for the surface lifecycle and touch delegation.
         *
         * Lifecycle ordering (see [surfaceChanged] for the deferred-fire contract):
         *   onSurfaceCreated (deferred until first surfaceChanged with real dimensions)
         *   -> onSurfaceChanged* (zero or more subsequent size/format changes)
         *   -> onSurfaceDestroyed.
         *
         * Idempotency: [onSurfaceDestroyed] may be invoked more than once per logical
         * surface because VideoSurface.kt's DisposableEffect.onDispose also calls it
         * on Compose teardown. Consumers MUST treat destroy as idempotent.
         *
         * Edge case: if [surfaceChanged] never arrives after [surfaceCreated]
         * (e.g. zero-size window), [onSurfaceCreated] is never fired and the
         * consumer never receives the Surface. Callers that need a guaranteed
         * creation signal must handle this out-of-band.
         */
        interface Callback {
            /** Fired once the surface exists AND has non-zero dimensions. */
            fun onSurfaceCreated(
                surface: Surface,
                width: Int,
                height: Int,
            )

            /** Fired on subsequent size/format/HDR changes after the initial create. */
            fun onSurfaceChanged(
                width: Int,
                height: Int,
            )

            /** Fired on surface teardown. May be called more than once; must be idempotent. */
            fun onSurfaceDestroyed()

            /** Touch delegation target; returns true if the event was consumed. */
            fun onTouchEvent(event: MotionEvent): Boolean
        }

        /**
         * Consumer callback. Main-thread only; no volatile/synchronization.
         * See class KDoc for threading and reassignment hazards.
         */
        var callback: Callback? = null

        private var viewAreaIndex = 0
        private var panelWidth = 2400
        private var panelHeight = 960
        /** A Compose update can arrive before Android has created the SurfaceControl. */
        private var geometryPending = false

        /** Crop/position the HWC layer in the panel's coordinates, without scaling the video. */
        fun setCarPlayViewArea(index: Int, panelWidth: Int, panelHeight: Int) {
            if (index !in 0..1) {
                logWarn("[VIDEO_SURFACE_VIEW] Ignoring invalid view-area index=$index", tag = "UI")
                return
            }
            if (panelWidth <= 0 || panelHeight <= 0) {
                logWarn("[VIDEO_SURFACE_VIEW] Ignoring invalid panel size=${panelWidth}x$panelHeight", tag = "UI")
                return
            }
            if (Build.VERSION.SDK_INT < 31) {
                logWarn("[VIDEO_SURFACE_VIEW] View-area crop requires API 31+ (device=${Build.VERSION.SDK_INT})", tag = "UI")
                return
            }
            val panelSizeChanged = this.panelWidth != panelWidth || this.panelHeight != panelHeight
            this.panelWidth = panelWidth
            this.panelHeight = panelHeight
            // The normal full-panel layout is already the SurfaceView default. Avoid a SurfaceFlinger
            // geometry transaction during app startup; only touch the layer after an actual resize
            // request (or when returning from compact mode).
            if (index == 0 && viewAreaIndex == 0 && !panelSizeChanged) return
            viewAreaIndex = index
            logInfo(
                "[VIDEO_SURFACE_VIEW] Applying view area index=$index panel=${panelWidth}x$panelHeight " +
                    "rect=${viewArea(index)} " +
                    "view=${width}x$height holderFrame=${holder.surfaceFrame}",
                tag = "UI",
            )
            geometryPending = true
            applyViewAreaGeometry()
        }

        private fun applyViewAreaGeometry() {
            if (Build.VERSION.SDK_INT < 31) return
            geometryPending = true
            val control = surfaceControl ?: return
            if (!control.isValid) return
            val area = viewArea(viewAreaIndex)
            runCatching {
                SurfaceControl.Transaction().use { transaction ->
                    transaction.setGeometry(control, area, area, Surface.ROTATION_0)
                    transaction.apply()
                }
                geometryPending = false
                logInfo("[VIDEO_SURFACE_VIEW] SurfaceControl geometry applied: source=$area destination=$area", tag = "UI")
            }.onFailure {
                geometryPending = true
                logWarn("[VIDEO_SURFACE_VIEW] view-area geometry failed: ${it.message}", tag = "UI")
            }
        }

        private fun viewArea(index: Int): Rect =
            if (index == 0) {
                Rect(0, 0, panelWidth, panelHeight)
            } else {
                // This compact area is the measured GM app viewport on the target truck. Keep it
                // bounded on a panel variant with smaller dimensions rather than issuing an invalid
                // source crop; the normal target remains exactly (188,118)-(1604,960).
                Rect(
                    188.coerceAtMost(panelWidth),
                    118.coerceAtMost(panelHeight),
                    1604.coerceAtMost(panelWidth),
                    960.coerceAtMost(panelHeight),
                )
            }

        /**
         * One-shot latch — true after [surfaceCreated] fires, cleared on the first
         * [surfaceChanged] (which is when [Callback.onSurfaceCreated] is delivered).
         * NOTE: the name is misleading — this is NOT a "surface is currently alive"
         * flag. Once cleared, the surface is still valid until [surfaceDestroyed];
         * the flag only tracks whether the initial onSurfaceCreated callback is
         * still pending delivery. The public getter is effectively vestigial
         * (no in-tree reader references it; VideoSurface.kt never reads it).
         * Left public to avoid changing the external API surface.
         */
        var isSurfaceCreated: Boolean = false
            private set

        init {
            holder.addCallback(this)
            isFocusable = true
            isFocusableInTouchMode = true
            logInfo("[VIDEO_SURFACE_VIEW] Initialized", tag = "UI")
        }

        // Touch delegation: returns exactly what the callback returns (including false),
        // which skips super.onTouchEvent / click handling. performClick() is intentionally
        // NOT wired — this view is a video surface, not a clickable control, so accessibility
        // click semantics don't apply. @Suppress silences the lint warning that would
        // otherwise demand performClick() wiring.
        @Suppress("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean = callback?.onTouchEvent(event) ?: super.onTouchEvent(event)

        /**
         * Stage 1 of the deferred-fire state machine: mark the initial-create callback
         * as pending. We intentionally do NOT invoke [Callback.onSurfaceCreated] here
         * because width/height are not yet known — they arrive with [surfaceChanged].
         */
        override fun surfaceCreated(holder: SurfaceHolder) {
            isSurfaceCreated = true
            logInfo("[VIDEO_SURFACE_VIEW] Surface created", tag = "UI")
            if (geometryPending) post { applyViewAreaGeometry() }
        }

        /**
         * Stage 2 of the deferred-fire state machine:
         *   - First call after [surfaceCreated]: clears the latch and delivers
         *     [Callback.onSurfaceCreated] with real dimensions.
         *   - Subsequent calls: delivers [Callback.onSurfaceChanged] for
         *     rotation/resize/HDR/format changes.
         *
         * This non-obvious reordering is the entire reason [isSurfaceCreated] exists:
         * it guarantees the consumer's first callback carries valid width/height.
         */
        override fun surfaceChanged(
            holder: SurfaceHolder,
            format: Int,
            width: Int,
            height: Int,
        ) {
            // Known log-spam hazard: fires on every rotation/resize/HDR/format change.
            // Acceptable at INFO for debugging surface sizing; switch to logDebugOnly if noisy.
            logInfo("[VIDEO_SURFACE_VIEW] Surface: ${width}x$height", tag = "UI")
            if (geometryPending || viewAreaIndex != 0) applyViewAreaGeometry()

            if (isSurfaceCreated) {
                isSurfaceCreated = false
                callback?.onSurfaceCreated(holder.surface, width, height)
            } else {
                callback?.onSurfaceChanged(width, height)
            }
        }

        /**
         * Stage 3: teardown. Clears the latch (in case destroy arrives before the
         * first surfaceChanged had a chance to fire) and notifies the consumer.
         * Note: VideoSurface.kt also fires onSurfaceDestroyed from its
         * DisposableEffect.onDispose, so consumers may see this twice — see [Callback].
         */
        override fun surfaceDestroyed(holder: SurfaceHolder) {
            logWarn("[VIDEO_SURFACE_VIEW] Surface destroyed", tag = "UI")
            isSurfaceCreated = false
            if (viewAreaIndex != 0) geometryPending = true
            callback?.onSurfaceDestroyed()
        }
    }
