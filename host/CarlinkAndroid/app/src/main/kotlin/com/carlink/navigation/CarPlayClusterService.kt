package com.carlink.navigation

import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.validation.HostValidator
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/** Headless Car App Library endpoint that relays native CarPlay iAP2 guidance to AAOS. */
class CarPlayClusterService : CarAppService() {
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = CarPlayClusterSession()
}

private class CarPlayClusterSession : Session() {
    companion object {
        // AAOS may create one session per display. Only one may own NavigationManager.
        private val primarySession = AtomicReference<CarPlayClusterSession?>(null)
        private const val TAG = "CarlinkClusterNav"
    }

    private var manager: NavigationManager? = null
    private var scope: CoroutineScope? = null
    private var started = false
    private var hasActiveRoute = false
    private var isPrimary = false

    override fun onCreateScreen(intent: android.content.Intent): Screen {
        isPrimary = primarySession.compareAndSet(null, this)
        if (!isPrimary) {
            Log.i(TAG, "additional display session created; navigation updates remain with primary")
            return ClusterStatusScreen(carContext)
        }
        scope = CoroutineScope(Dispatchers.Main.immediate)
        runCatching {
            carContext.getCarService(NavigationManager::class.java).also { nav ->
                nav.setNavigationManagerCallback(object : NavigationManagerCallback {
                    override fun onStopNavigation() {
                        started = false
                    }

                    override fun onAutoDriveEnabled() = Unit
                })
                // Start once so the AAOS Templates Host can create the cluster surface;
                // the first update ends this idle session if no CarPlay route is active.
                runCatching { nav.navigationStarted(); started = true }
            }
        }.onSuccess {
            manager = it
            Log.i(TAG, "primary AAOS navigation session started")
        }.onFailure {
            Log.e(TAG, "failed to initialize NavigationManager", it)
        }

        val sessionScope = scope
        if (sessionScope != null) sessionScope.launch {
            CarPlayNavigationState.state.collect { state -> publish(state) }
        }
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                if (!isPrimary) return
                scope?.cancel()
                scope = null
                if (started) runCatching { manager?.navigationEnded() }
                started = false
                manager = null
                primarySession.compareAndSet(this@CarPlayClusterSession, null)
                Log.i(TAG, "primary AAOS navigation session ended")
            }
        })
        return ClusterStatusScreen(carContext)
    }

    private fun publish(state: CarPlayNavigationSnapshot) {
        val nav = manager ?: return
        if (state.active) {
            hasActiveRoute = true
            if (!started) runCatching { nav.navigationStarted(); started = true }
            runCatching { nav.updateTrip(CarPlayTripBuilder.build(state, carContext)) }
                .onFailure { Log.w(TAG, "updateTrip failed state=${state.routeState} index=${state.currentIndex}", it) }
        } else if (started && hasActiveRoute) {
            runCatching { nav.navigationEnded() }
                .onFailure { Log.w(TAG, "navigationEnded failed", it) }
            started = false
            hasActiveRoute = false
        }
    }
}

private class ClusterStatusScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template = NavigationTemplate.Builder()
        .setNavigationInfo(MessageInfo.Builder("CarPlay navigation is relayed to the vehicle cluster.").build())
        .setActionStrip(ActionStrip.Builder().addAction(Action.APP_ICON).build())
        .build()
}
