package com.carlink.ui

import android.view.MotionEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.carlink.BuildConfig
import com.carlink.CarlinkManager
import com.carlink.R
import com.carlink.logging.logDebug
import com.carlink.logging.logInfo
import com.carlink.protocol.MessageSerializer
import com.carlink.protocol.MultiTouchAction
import com.carlink.protocol.PhoneType
import com.carlink.ui.adaptive.asWindowInsets
import com.carlink.ui.components.BoxStatusLine
import com.carlink.ui.components.LoadingSpinner
import com.carlink.ui.components.VideoSurface
import com.carlink.ui.components.VideoSurfaceState
import com.carlink.ui.components.rememberVideoSurfaceState
import com.carlink.ui.settings.ResetConnectionDialog
import com.carlink.ui.theme.AutomotiveDimens
import com.carlink.util.EdgeInsets
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Poll period for whether the current renderer epoch has actually produced a frame. */
private const val VIDEO_VISIBILITY_POLL_MS = 100L

/**
 * Session-scoped UI state, one instance per [CarlinkManager] (a Reset Device rebuild replaces the
 * manager and therefore this too, so stale callbacks / touch state from the old manager can never
 * leak into the new session).
 */
@Stable
private class SessionUiState {
    var connectionState by mutableStateOf(CarlinkManager.State.DISCONNECTED)
    var statusText by mutableStateOf("Connect Adapter")
    var viewAreaIndex by mutableStateOf(0)

    /** Container (content-area) dimensions, used for the adapter OPEN / declared-panel resolution. */
    var containerSize by mutableStateOf(IntSize.Zero)

    /** Set once initialize() has run with a real surface + size; gates the one-shot start effect. */
    var initializedForStart by mutableStateOf(false)

    /** Current epoch has a frame and native media has not failed its liveness probe. */
    var videoOnScreen by mutableStateOf(false)

    /** The wireless pairing code awaiting Pair / Cancel (CT_PAIRING_CODE), or null. */
    var pairingCode by mutableStateOf<String?>(null)

    /**
     * carlink_native hid its loading overlay (and forwarded touch) from the FIRST VIDEO FRAME, which
     * was its STREAMING edge. OCBM stamps STREAMING on the first media metadata instead, so the same
     * behavior must use the current renderer epoch for BOTH states. STREAMING is session history,
     * not proof that the current Surface has a frame: backgrounding retires the renderer while the
     * phone session (and STREAMING state) can remain alive. Requiring a current frame keeps the
     * loading/recovery controls available if the phone disconnects while this Activity is away.
     */
    val projecting: Boolean
        get() =
            videoOnScreen &&
                (connectionState == CarlinkManager.State.STREAMING ||
                    connectionState == CarlinkManager.State.DEVICE_CONNECTED)

    // Plain fields, not snapshot state: only the touch handler reads/mutates them on the UI thread.
    var lastTouchTime = 0L
    val activeTouches = mutableMapOf<Int, TouchPoint>()
}

/**
 * Main projection screen: the CarPlay video (SurfaceView / HWC overlay) with touch forwarding,
 * and — while no session is streaming — the loading overlay (logo, spinner, status line) with the
 * Settings / Reset Device buttons, exactly the carlink_native main page. During a live session the
 * OEM "Exit" icon in CarPlay (`requestUI`) opens Settings via [onNavigateToSettings].
 *
 * The video surface stays full-panel in edge-to-edge coordinates so `requestViewArea` can crop the
 * hardware-composer layer into the exact panel-coordinate rectangle advertised in `/info`. Touch is
 * normalized against that same full-panel basis, including while the compact crop is active.
 */
@Composable
fun MainScreen(
    carlinkManager: CarlinkManager,
    onNavigateToSettings: () -> Unit,
    onStateChanged: (CarlinkManager.State) -> Unit = {},
    onViewAreaChanged: (Int) -> Unit = {},
) {
    val ui = remember(carlinkManager) { SessionUiState() }
    val surfaceState = rememberVideoSurfaceState()
    val scope = rememberCoroutineScope()
    var surfaceRecreateToken by remember(carlinkManager) { mutableIntStateOf(0) }
    var showResetConfirm by remember(carlinkManager) { mutableStateOf(false) }
    SessionEffects(carlinkManager, ui, surfaceState, onNavigateToSettings, onStateChanged, onViewAreaChanged)

    // AAOS can destroy a SurfaceView while the Activity is stopped and occasionally fail to
    // deliver a replacement Surface on resume. The manager cannot restart its decoder without a
    // valid Surface, so remount the view on foreground return when the old Surface is gone.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, carlinkManager) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START && surfaceState.surface?.isValid != true) {
                logInfo("[UI_SURFACE] No valid Surface on foreground return — recreating SurfaceView", tag = "UI")
                surfaceRecreateToken++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // The surface rect comes from the SAME profile the config was built from — the bars the
    // session's display mode keeps visible plus the parity pixel — not from the live Compose
    // insets, which flip with transient bar reveals and would move the surface out from under
    // the pushed geometry. The safe area (cutout / waterfall / corners) only pads the buttons.
    val profile = carlinkManager.displayProfile
    val safeAreaInsets = profile?.safeAreaInsets ?: EdgeInsets.NONE

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        ProjectionSurface(
            carlinkManager,
            ui,
            surfaceState,
            surfaceRecreateToken,
                Modifier.fillMaxSize(),
        )
        if (!ui.projecting) {
            // STREAMING is retained across a brief Surface/decoder loss. Audio and the phone's
            // CarPlay control session may still be live, so the generic adapter status (often
            // left at "Waiting for phone...") is misleading while video is being rebuilt.
            val overlayStatus =
                if (ui.connectionState == CarlinkManager.State.STREAMING && !ui.videoOnScreen) {
                    "CarPlay video unavailable — waiting for phone..."
                } else {
                    ui.statusText
                }
            LoadingOverlay(carlinkManager, overlayStatus)
            TopActions(
                onNavigateToSettings = onNavigateToSettings,
                onResetConnection = { showResetConfirm = true },
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .windowInsetsPadding(WindowInsets.safeDrawing.union(safeAreaInsets.asWindowInsets()))
                        .padding(24.dp),
            )
        }
    }

    // Reset Device = the SAME clean-slate reset as Settings ▸ Reset Connection, same confirmation.
    if (showResetConfirm) {
        ResetConnectionDialog(
            onConfirm = {
                logInfo("[UI_ACTION] Reset Device confirmed", tag = "UI")
                showResetConfirm = false
                scope.launch { carlinkManager.cleanSlateReset() }
            },
            onDismiss = { showResetConfirm = false },
        )
    }

    // The box published an SSP Numeric-Comparison code: the user must confirm on BOTH ends.
    ui.pairingCode?.let { code ->
        PairingCodeDialog(
            code = code,
            onAnswer = { accept ->
                logInfo("[UI_ACTION] Pairing ${if (accept) "PAIR" else "CANCEL"} for code $code", tag = "UI")
                carlinkManager.answerPairing(accept)
            },
        )
    }
}

/**
 * Wireless pairing prompt. The code is matched against the one on the iPhone; Pair sends
 * `CT_PAIR_CONFIRM 1`, Cancel `CT_PAIR_CONFIRM 0`. Not dismissable by scrim/back: an unanswered
 * prompt is cancelled by the box itself after ~55 s, and a stray tap must not be read as either.
 */
@Composable
private fun PairingCodeDialog(
    code: String,
    onAnswer: (Boolean) -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Pair with iPhone?") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text("Confirm that this code matches the one shown on the iPhone.")
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = code.chunked((code.length + 1) / 2).joinToString(" "),
                    style = MaterialTheme.typography.displayMedium,
                    color = colorScheme.primary,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onAnswer(true) }) { Text("Pair") } },
        dismissButton = { TextButton(onClick = { onAnswer(false) }) { Text("Cancel", color = colorScheme.error) } },
    )
}

/** The effects that bind the manager to the surface: init, one-shot start, night mode, state fan-out. */
@Composable
private fun SessionEffects(
    carlinkManager: CarlinkManager,
    ui: SessionUiState,
    surfaceState: VideoSurfaceState,
    onNavigateToSettings: () -> Unit,
    onStateChanged: (CarlinkManager.State) -> Unit,
    onViewAreaChanged: (Int) -> Unit,
) {
    // The callback object below is registered once per surface/size; read the latest lambdas
    // through rememberUpdatedState so a recomposed caller never leaves a stale capture inside it.
    val navigate by rememberUpdatedState(onNavigateToSettings)
    val stateSink by rememberUpdatedState(onStateChanged)
    val viewAreaSink by rememberUpdatedState(onViewAreaChanged)

    LaunchedEffect(ui.connectionState) {
        logInfo("[UI_STATE] MainScreen connection state: ${ui.connectionState}", tag = "UI")
        stateSink(ui.connectionState)
        // Streaming gate drops UP/CANCEL while paused — clear stale pointers when the session
        // leaves STREAMING so they can't resurface as phantom MOVE pointers on resume.
        if (ui.connectionState != CarlinkManager.State.STREAMING) ui.activeTouches.clear()
    }

    // Keep the overlay tied to the CURRENT renderer epoch and media liveness, not session history. This
    // also catches pauseVideo() retiring the epoch while MainActivity is backgrounded and a phone
    // disconnect that does not immediately move the manager out of STREAMING. Without this, a
    // resumed Activity can remain a black Surface with the Settings/Reset controls hidden.
    LaunchedEffect(ui.connectionState) {
        val canProject =
            ui.connectionState == CarlinkManager.State.DEVICE_CONNECTED ||
                ui.connectionState == CarlinkManager.State.STREAMING
        if (!canProject) {
            ui.videoOnScreen = false
            return@LaunchedEffect
        }

        var wasVisible = ui.videoOnScreen
        while (true) {
            val hasCurrentFrame = withContext(Dispatchers.IO) { carlinkManager.videoIsResponsive() }
            if (hasCurrentFrame != ui.videoOnScreen) {
                if (hasCurrentFrame) {
                    logInfo("[UI_STATE] Current video session responsive — hiding recovery overlay", tag = "UI")
                } else if (wasVisible) {
                    logInfo("[UI_STATE] Current video session unavailable or probe unanswered — showing recovery overlay", tag = "UI")
                }
                ui.videoOnScreen = hasCurrentFrame
            }
            wasVisible = hasCurrentFrame
            delay(VIDEO_VISIBILITY_POLL_MS)
        }
    }

    // Forward the AAOS day/night state to CarPlay: on reaching STREAMING and on every mid-session
    // theme flip (MainActivity handles the uiMode config change without recreating).
    val darkTheme = isSystemInDarkTheme()
    LaunchedEffect(darkTheme, ui.connectionState) {
        if (ui.connectionState == CarlinkManager.State.STREAMING) carlinkManager.setNightMode(darkTheme)
    }

    // Surface init for the adapter — uses container (content-area) dimensions. Idempotent across
    // Surface recreations; start() runs once per manager (the decoupled effect below).
    LaunchedEffect(surfaceState.surface, ui.containerSize) {
        val surface = surfaceState.surface ?: return@LaunchedEffect
        val size = ui.containerSize
        if (size.width <= 0 || size.height <= 0) return@LaunchedEffect
        val adapterWidth = size.width and 1.inv()
        val adapterHeight = size.height and 1.inv()
        logInfo(
            "[CARLINK_RESOLUTION] Container size: ${adapterWidth}x$adapterHeight (surface: ${surfaceState.width}x${surfaceState.height})",
            tag = "UI",
        )
        carlinkManager.initialize(
            surface = surface,
            surfaceWidth = adapterWidth,
            surfaceHeight = adapterHeight,
            callback =
                object : CarlinkManager.Callback {
                    override fun onStateChanged(state: CarlinkManager.State) {
                        ui.connectionState = state
                    }

                    override fun onStatusTextChanged(text: String) {
                        ui.statusText = text
                    }

                    override fun onHostUIPressed() {
                        // OEM "Exit" icon pressed in CarPlay → open Settings over the live video.
                        logInfo("[UI_NAV] Host UI requested — opening Settings", tag = "UI")
                        navigate()
                    }

                    override fun onViewAreaRequested(index: Int) {
                        ui.viewAreaIndex = index
                        viewAreaSink(index)
                    }

                    override fun onPhoneTypeChanged(phoneType: PhoneType) {
                        logInfo("[UI_SURFACE] Phone type changed: $phoneType", tag = "UI")
                    }

                    override fun onPairingCodeChanged(code: String?) {
                        ui.pairingCode = code
                    }
                },
        )
        ui.initializedForStart = true
    }

    // One-shot start per manager, DECOUPLED from the surface/size effect above: a surface swap or
    // late layout pass mid-connect must not cancel start(), and surface/size churn can't cancel
    // this effect because it is keyed only on the manager.
    LaunchedEffect(carlinkManager) {
        snapshotFlow { ui.initializedForStart }.first { it }
        carlinkManager.start()
    }
}

/** The SurfaceView, keyed on the manager so a rebuild inflates a fresh one (and a fresh HWC plane). */
@Composable
private fun ProjectionSurface(
    carlinkManager: CarlinkManager,
    ui: SessionUiState,
    surfaceState: VideoSurfaceState,
    surfaceRecreateToken: Int,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val panelWidthPx = carlinkManager.panelWidthPx
    val panelHeightPx = carlinkManager.panelHeightPx
    val panelWidth = with(density) { panelWidthPx.toDp() }
    val panelHeight = with(density) { panelHeightPx.toDp() }
    BoxWithConstraints(modifier = modifier) {
        // Track AAOS's live content area for diagnostics only. The stream and its SurfaceView
        // remain in the stable full-panel coordinate space even when this parent is narrowed.
        val containerPx = with(density) { IntSize(maxWidth.roundToPx(), maxHeight.roundToPx()) }
        LaunchedEffect(containerPx) {
            if (containerPx.width > 0 && containerPx.height > 0) {
                ui.containerSize = containerPx
                logInfo(
                    "[VIDEO_SURFACE] parent=${containerPx.width}x${containerPx.height}; " +
                        "pinning view to panel=${panelWidthPx}x$panelHeightPx",
                    tag = "UI",
                )
            }
        }
        key(carlinkManager, surfaceRecreateToken) {
            VideoSurface(
                // AAOS narrows the Compose content bounds when it exposes the compact CarPlay
                // area. Keep the SurfaceView at physical panel size and its origin at (0,0);
                // SurfaceControl then crops it to the requested view area. TopStart + unbounded
                // avoids centering this oversized child in the compact parent.
                modifier =
                    Modifier
                        .wrapContentSize(Alignment.TopStart, unbounded = true)
                        .requiredSize(panelWidth, panelHeight),
                viewAreaIndex = ui.viewAreaIndex,
                panelWidthPx = panelWidthPx,
                panelHeightPx = panelHeightPx,
                onSurfaceAvailable = { surface, width, height ->
                    logInfo("[UI_SURFACE] Surface available: ${width}x$height", tag = "UI")
                    surfaceState.onSurfaceAvailable(surface, width, height)
                },
                onSurfaceDestroyed = {
                    logInfo("[UI_SURFACE] Surface destroyed", tag = "UI")
                    surfaceState.onSurfaceDestroyed()
                    carlinkManager.onSurfaceDestroyed()
                },
                onSurfaceSizeChanged = { width, height ->
                    logInfo("[UI_SURFACE] Surface size changed: ${width}x$height", tag = "UI")
                    surfaceState.onSurfaceSizeChanged(width, height)
                },
                onTouchEvent = { event ->
                    // Read state through the holder — this lambda is captured once in the
                    // AndroidView factory; a pre-computed val would snapshot DISCONNECTED forever.
                    if (ui.projecting) {
                        if (BuildConfig.DEBUG) {
                            val now = System.currentTimeMillis()
                            if (now - ui.lastTouchTime > 1000) {
                                logDebug(
                                    "[UI_TOUCH] touch: action=${event.actionMasked}, pointers=${event.pointerCount}" +
                                        ", surface=${surfaceState.width}x${surfaceState.height}" +
                                        ", container=${ui.containerSize.width}x${ui.containerSize.height}",
                                    tag = "UI",
                                )
                                ui.lastTouchTime = now
                            }
                        }
                        handleTouchEvent(
                            event,
                            ui.activeTouches,
                            carlinkManager,
                            surfaceState.width,
                            surfaceState.height,
                            ui.containerSize.width,
                            ui.containerSize.height,
                        )
                    }
                    true
                },
            )
        }
    }
}

/** Scrim + logo + spinner + "[ status ]" + box status, centred, while no session streams. */
@Composable
private fun LoadingOverlay(
    carlinkManager: CarlinkManager,
    statusText: String,
) {
    val colorScheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier.fillMaxSize().background(colorScheme.scrim.copy(alpha = 0.7f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Image(
                painter = painterResource(id = R.drawable.ic_phone_projection),
                contentDescription = "Carlink",
                modifier = Modifier.height(220.dp),
            )
            Spacer(modifier = Modifier.height(24.dp))
            LoadingSpinner(color = colorScheme.primary)
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "[ $statusText ]",
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFFDDE4E5), // Fixed light color for dark overlay
            )
            BoxStatusLine(carlinkManager, Modifier.padding(top = 8.dp))
        }
    }
}

/** Top-left "Settings" and "Reset Device" (full session rebuild) buttons of the loading overlay. */
@Composable
private fun TopActions(
    onNavigateToSettings: () -> Unit,
    onResetConnection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val contentPadding = PaddingValues(horizontal = AutomotiveDimens.ButtonPaddingHorizontal, vertical = AutomotiveDimens.ButtonPaddingVertical)
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        FilledTonalButton(
            onClick = onNavigateToSettings,
            modifier = Modifier.heightIn(min = AutomotiveDimens.ButtonMinHeight),
            contentPadding = contentPadding,
        ) {
            Icon(imageVector = Icons.Default.Settings, contentDescription = "Settings", modifier = Modifier.size(AutomotiveDimens.IconSize))
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = "Settings", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        FilledTonalButton(
            onClick = onResetConnection,
            modifier = Modifier.heightIn(min = AutomotiveDimens.ButtonMinHeight),
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = colorScheme.errorContainer, contentColor = colorScheme.onErrorContainer),
            contentPadding = contentPadding,
        ) {
            Icon(imageVector = Icons.Default.RestartAlt, contentDescription = "Reset Device", modifier = Modifier.size(AutomotiveDimens.IconSize))
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = "Reset Device", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ==================== Touch forwarding ====================

/** In-memory per-pointer touch record (normalized 0..1 coords + last action) for deduping MOVE spam. */
private data class TouchPoint(
    val x: Float,
    val y: Float,
    val action: MultiTouchAction,
)

/**
 * Handle touch on the SurfaceView. CarPlay multitouch (type 0x17): normalize to 0..1 of the
 * SurfaceView. A deadband suppresses sub-pixel MOVE spam.
 */
private fun handleTouchEvent(
    event: MotionEvent,
    activeTouches: MutableMap<Int, TouchPoint>,
    carlinkManager: CarlinkManager,
    surfaceWidth: Int,
    surfaceHeight: Int,
    containerWidth: Int,
    containerHeight: Int,
) {
    if (surfaceWidth == 0 || surfaceHeight == 0 || containerWidth == 0 || containerHeight == 0) return

    // ACTION_CANCEL terminates ALL pointers of the gesture, not just actionIndex (which is
    // always 0 for CANCEL). Marking only one pointer UP left the rest in the map forever —
    // re-sent as phantom held fingers in every later frame until Reset Connection.
    if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
        if (activeTouches.isEmpty()) return
        val touchList =
            activeTouches.entries.map { entry ->
                MessageSerializer.TouchPoint(
                    x = entry.value.x,
                    y = entry.value.y,
                    action = MultiTouchAction.UP,
                    id = entry.key,
                )
            }
        activeTouches.clear()
        carlinkManager.sendMultiTouch(touchList)
        return
    }

    val pointerIndex = event.actionIndex
    val pointerId = event.getPointerId(pointerIndex)

    val action =
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> MultiTouchAction.DOWN
            MotionEvent.ACTION_MOVE -> MultiTouchAction.MOVE
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> MultiTouchAction.UP
            else -> return
        }

    // Clamp: MotionEvent coords legitimately go outside the view during drags that exit
    // the surface; out-of-range values (-0.01, 1.03) must not reach the wire.
    val x = (event.getX(pointerIndex) / surfaceWidth).coerceIn(0f, 1f)
    val y = (event.getY(pointerIndex) / surfaceHeight).coerceIn(0f, 1f)

    var changed = false
    when (action) {
        MultiTouchAction.DOWN -> {
            activeTouches[pointerId] = TouchPoint(x, y, action)
            changed = true
        }

        MultiTouchAction.MOVE -> {
            for (i in 0 until event.pointerCount) {
                val id = event.getPointerId(i)
                val px = (event.getX(i) / surfaceWidth).coerceIn(0f, 1f)
                val py = (event.getY(i) / surfaceHeight).coerceIn(0f, 1f)
                activeTouches[id]?.let { existing ->
                    // Deadband ≈ 0.3% of the normalized surface — suppresses sub-pixel MOVE spam.
                    val dx = kotlin.math.abs(existing.x - px) * 1000
                    val dy = kotlin.math.abs(existing.y - py) * 1000
                    if (dx > 3 || dy > 3) {
                        activeTouches[id] = TouchPoint(px, py, MultiTouchAction.MOVE)
                        changed = true
                    }
                }
            }
        }

        MultiTouchAction.UP -> {
            // Only announce UP for pointers we actually announced DOWN for — a pointer
            // whose DOWN was swallowed (zero-size guard, mid-gesture stream start) would
            // otherwise emit a spurious lone UP for an id the peer never saw.
            if (activeTouches.containsKey(pointerId)) {
                activeTouches[pointerId] = TouchPoint(x, y, action)
                changed = true
            }
        }

        else -> {}
    }

    // Send only when something actually changed. Before, a MOVE where no pointer beat the
    // deadband still re-sent identical coordinates at input rate (the deadband saved
    // nothing on the USB path), and a MOVE with an empty map (DOWN swallowed by the
    // zero-size guard) emitted an empty 0x17 frame.
    if (!changed || activeTouches.isEmpty()) return

    val touchList =
        activeTouches.entries.map { entry ->
            MessageSerializer.TouchPoint(
                x = entry.value.x,
                y = entry.value.y,
                action = entry.value.action,
                id = entry.key,
            )
        }

    carlinkManager.sendMultiTouch(touchList)
    activeTouches.entries.removeIf { it.value.action == MultiTouchAction.UP }
    // DOWN is an edge event: demote to MOVE after its first send so a stationary finger
    // in a multi-touch gesture is not re-announced as a fresh DOWN dozens of times/sec.
    for (entry in activeTouches.entries) {
        if (entry.value.action == MultiTouchAction.DOWN) {
            entry.setValue(entry.value.copy(action = MultiTouchAction.MOVE))
        }
    }
}
