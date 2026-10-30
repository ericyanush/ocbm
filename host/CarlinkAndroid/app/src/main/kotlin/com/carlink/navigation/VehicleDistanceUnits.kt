package com.carlink.navigation

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.VehicleUnit
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import com.carlink.logging.Logger
import com.carlink.logging.logInfo
import com.carlink.logging.logWarn
import java.util.concurrent.Executors

/** Reads the vehicle's configured distance units; locale is only a fallback when the HAL omits it. */
object VehicleDistanceUnits {
    private val readExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "CarlinkDistanceUnits").apply { isDaemon = true }
    }
    @Volatile private var imperial: Boolean? = null
    @Volatile private var started = false
    private var car: Car? = null
    private var propertyManager: CarPropertyManager? = null

    fun isImperial(): Boolean? = imperial

    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            logInfo("vehicle distance units unavailable off automotive; using locale", tag = Logger.Tags.NAVI)
            return
        }
        runCatching {
            Car.createCar(context.applicationContext, Handler(Looper.getMainLooper()), Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connectedCar, ready ->
                if (ready) onCarReady(connectedCar) else {
                    propertyManager = null
                    car = null
                    imperial = null
                    logWarn("car service disconnected; distance units reverting to locale", tag = Logger.Tags.NAVI)
                }
            }
        }.onSuccess { car = it }
            .onFailure {
                logWarn("could not connect for vehicle distance units: ${it.message}; using locale", tag = Logger.Tags.NAVI)
            }
    }

    private fun onCarReady(connectedCar: Car) {
        val manager = runCatching {
            connectedCar.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
        }.getOrNull()
        if (manager == null) {
            logWarn("CarPropertyManager unavailable; distance units falling back to locale", tag = Logger.Tags.NAVI)
            return
        }
        propertyManager = manager
        runCatching {
            manager.registerCallback(callback, VehiclePropertyIds.DISTANCE_DISPLAY_UNITS, CarPropertyManager.SENSOR_RATE_ONCHANGE)
        }.onFailure {
            logWarn("could not subscribe to vehicle distance units: ${it.message}", tag = Logger.Tags.NAVI)
        }
        readExecutor.execute {
            runCatching { manager.getIntProperty(VehiclePropertyIds.DISTANCE_DISPLAY_UNITS, 0) }
                .onSuccess(::publish)
                .onFailure {
                    logWarn("could not read vehicle distance units: ${it.message}; using locale", tag = Logger.Tags.NAVI)
                }
        }
    }

    private val callback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            if (value.propertyId == VehiclePropertyIds.DISTANCE_DISPLAY_UNITS &&
                value.status == CarPropertyValue.STATUS_AVAILABLE && value.value is Int
            ) publish(value.value as Int)
        }

        override fun onErrorEvent(propertyId: Int, areaId: Int) {
            if (propertyId == VehiclePropertyIds.DISTANCE_DISPLAY_UNITS) {
                logWarn("vehicle distance unit update failed; retaining last known unit", tag = Logger.Tags.NAVI)
            }
        }
    }

    private fun publish(unit: Int) {
        val resolved = when (unit) {
            VehicleUnit.MILE -> true
            VehicleUnit.METER, VehicleUnit.KILOMETER, VehicleUnit.MILLIMETER -> false
            else -> null
        }
        imperial = resolved
        logInfo(
            "vehicle DISTANCE_DISPLAY_UNITS=$unit resolved=${when (resolved) { true -> "imperial"; false -> "metric"; null -> "unknown (locale fallback)" }}",
            tag = Logger.Tags.NAVI,
        )
    }
}
