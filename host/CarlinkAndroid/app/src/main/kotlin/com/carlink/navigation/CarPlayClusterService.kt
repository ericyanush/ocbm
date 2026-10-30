package com.carlink.navigation

import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.validation.HostValidator
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import com.carlink.BuildConfig
import com.carlink.CarlinkApplication
import com.carlink.logging.Logger
import com.carlink.logging.logError
import com.carlink.logging.logInfo
import com.carlink.logging.logWarn
import com.carlink.ui.settings.TurnAlertStore
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Headless Car App Library endpoint that relays native CarPlay iAP2 guidance to AAOS. */
class CarPlayClusterService : CarAppService() {
    override fun onCreate() {
        super.onCreate()
        // Application.onCreate normally initializes this process first. Ensure it explicitly
        // here too, so the service remains file-logged and crash-captured if moved to its own
        // android:process in a future manifest change.
        CarlinkApplication.ensureProcessDiagnostics(application)
        val processName = if (android.os.Build.VERSION.SDK_INT >= 28) Application.getProcessName() else packageName
        logInfo("service created process=$processName pid=${Process.myPid()}", tag = Logger.Tags.CLUSTER)
    }

    override fun onDestroy() {
        logInfo("service destroyed pid=${Process.myPid()}", tag = Logger.Tags.CLUSTER)
        super.onDestroy()
    }

    override fun createHostValidator(): HostValidator = if (BuildConfig.DEBUG) {
        HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    } else {
        HostValidator.Builder(applicationContext).addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample).build()
    }

    @Suppress("DEPRECATION")
    override fun onCreateSession(): Session = CarPlayClusterSession(displayType = null)

    override fun onCreateSession(sessionInfo: SessionInfo): Session =
        CarPlayClusterSession(displayType = sessionInfo.displayType)
}

private class CarPlayClusterSession(private val displayType: Int?) : Session() {

    private var manager: NavigationManager? = null
    private var scope: CoroutineScope? = null
    private var started = false
    private var bindingStateRegistered = false
    private var lastTripLogAtMs = 0L
    private val ticker = Handler(Looper.getMainLooper())
    private var lastAlertPhase = TurnAlert.Phase.NORMAL
    private val alertTick = object : Runnable {
        override fun run() {
            val state = NavigationStateManager.state.value
            val phase = TurnAlert.phase(TurnAlertStore.get(carContext).current, SystemClock.elapsedRealtime())
            val changed = phase != lastAlertPhase
            lastAlertPhase = phase
            TurnAlert.phaseNow = phase
            TurnAlert.coloredBeat = when (phase) {
                TurnAlert.Phase.NORMAL -> false
                TurnAlert.Phase.SOLID -> true
                TurnAlert.Phase.FLASH -> !TurnAlert.coloredBeat
            }
            if (state.isActive && (changed || phase == TurnAlert.Phase.FLASH)) publish(state)
            ticker.postDelayed(this, 500L)
        }
    }

    override fun onCreateScreen(intent: android.content.Intent): Screen {
        if (!bindingStateRegistered) {
            ClusterBindingState.sessionStarted(this)
            bindingStateRegistered = true
        }
        logInfo(
            "session created id=${System.identityHashCode(this)} displayType=${displayType ?: "legacy"} " +
                "api=${carContext.carAppApiLevel} routeActive=${NavigationStateManager.state.value.isActive}",
            tag = Logger.Tags.CLUSTER,
        )
        scope = CoroutineScope(Dispatchers.Main.immediate)
        runCatching {
            carContext.getCarService(NavigationManager::class.java).also { nav ->
                nav.setNavigationManagerCallback(object : NavigationManagerCallback {
                    override fun onStopNavigation() {
                        started = false
                        logInfo(
                            "onStopNavigation session=${System.identityHashCode(this@CarPlayClusterSession)}",
                            tag = Logger.Tags.CLUSTER,
                        )
                    }

                    override fun onAutoDriveEnabled() = Unit
                })
            }
        }.onSuccess {
            manager = it
            logInfo(
                "NavigationManager ready session=${System.identityHashCode(this)} displayType=${displayType ?: "legacy"}",
                tag = Logger.Tags.CLUSTER,
            )
        }.onFailure {
            logError("failed to initialize NavigationManager displayType=${displayType ?: "legacy"}", Logger.Tags.CLUSTER, it)
        }

        val sessionScope = scope
        if (sessionScope != null) sessionScope.launch {
            NavigationStateManager.state.collect { state ->
                TurnAlert.observe(state, SystemClock.elapsedRealtime())
                publish(state)
            }
        }
        ticker.post(alertTick)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                scope?.cancel()
                scope = null
                ticker.removeCallbacks(alertTick)
                if (started) runCatching { manager?.navigationEnded() }
                started = false
                manager = null
                if (bindingStateRegistered) {
                    ClusterBindingState.sessionEnded(this@CarPlayClusterSession)
                    bindingStateRegistered = false
                }
                logInfo(
                    "session ended id=${System.identityHashCode(this@CarPlayClusterSession)} displayType=${displayType ?: "legacy"}",
                    tag = Logger.Tags.CLUSTER,
                )
            }
        })
        return ClusterStatusScreen(carContext)
    }

    private fun publish(state: NavigationState) {
        val nav = manager ?: return
        TurnAlert.phaseNow = TurnAlert.phase(TurnAlertStore.get(carContext).current, SystemClock.elapsedRealtime())
        if (state.isActive) {
            if (!started) {
                runCatching { nav.navigationStarted() }
                    .onSuccess {
                        started = true
                        logInfo(
                            "navigationStarted session=${System.identityHashCode(this)} displayType=${displayType ?: "legacy"}",
                            tag = Logger.Tags.CLUSTER,
                        )
                    }
                    .onFailure {
                        logWarn("navigationStarted failed displayType=${displayType ?: "legacy"}: ${it.message}", tag = Logger.Tags.CLUSTER)
                        return
                    }
            }
            runCatching { nav.updateTrip(TripBuilder.buildTrip(state, carContext)) }
                .onSuccess {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastTripLogAtMs >= 5_000L) {
                        lastTripLogAtMs = now
                        logInfo(
                            "updateTrip session=${System.identityHashCode(this)} displayType=${displayType ?: "legacy"} " +
                                "maneuver=${state.maneuverType} distanceM=${state.remainDistance}",
                            tag = Logger.Tags.CLUSTER,
                        )
                    }
                }
                .onFailure { logWarn("updateTrip failed maneuver=${state.maneuverType}: ${it.message}", tag = Logger.Tags.CLUSTER) }
        } else if (started) {
            runCatching { nav.navigationEnded() }
                .onFailure { logWarn("navigationEnded failed: ${it.message}", tag = Logger.Tags.CLUSTER) }
                .onSuccess { logInfo("navigationEnded session=${System.identityHashCode(this)}", tag = Logger.Tags.CLUSTER) }
            started = false
        }
    }
}

private class ClusterStatusScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template = NavigationTemplate.Builder()
        .setNavigationInfo(MessageInfo.Builder("CarPlay navigation is relayed to the vehicle cluster.").build())
        .setActionStrip(ActionStrip.Builder().addAction(Action.APP_ICON).build())
        .build()
}
