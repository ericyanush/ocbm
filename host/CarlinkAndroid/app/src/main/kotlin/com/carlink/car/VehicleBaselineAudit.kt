package com.carlink.car

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.carlink.logging.Logger
import com.carlink.logging.logInfo
import com.carlink.logging.logWarn

/** Read-only AAOS inventory for the OpenAutoLink porting baseline. No vehicle values are read. */
object VehicleBaselineAudit {
    private val permissions =
        listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            "android.car.permission.CAR_ENERGY",
            "android.car.permission.CAR_ENERGY_PORTS",
            "android.car.permission.CAR_INFO",
            "android.car.permission.CAR_POWERTRAIN",
            "android.car.permission.CAR_SPEED",
            "android.car.permission.CAR_EXTERIOR_ENVIRONMENT",
            "android.car.permission.READ_CAR_DISPLAY_UNITS",
            "android.car.permission.CAR_MILEAGE",
            "android.car.permission.CAR_TIRES",
            "android.car.permission.CAR_DYNAMICS_STATE",
        )

    fun record(context: Context) {
        val manager = context.packageManager
        logInfo(
            "[VEHICLE_BASELINE] manufacturer=${Build.MANUFACTURER} model=${Build.MODEL} " +
                "device=${Build.DEVICE} display=${Build.DISPLAY} android=${Build.VERSION.RELEASE} " +
                "sdk=${Build.VERSION.SDK_INT} automotive=${manager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)}",
            tag = Logger.Tags.ADAPTR,
        )

        val requested: Set<String>? =
            runCatching {
                manager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
                    .requestedPermissions?.toSet().orEmpty()
            }.onFailure { error ->
                logWarn("[VEHICLE_BASELINE] cannot inspect manifest permissions: ${error.javaClass.simpleName}", tag = Logger.Tags.ADAPTR)
            }.getOrNull()

        for (permission in permissions) {
            val state =
                when {
                    requested == null -> "request_unknown"
                    permission !in requested -> "not_requested"
                    context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED -> "granted"
                    else -> "denied"
                }
            logInfo("[VEHICLE_BASELINE] permission=$permission state=$state", tag = Logger.Tags.ADAPTR)
        }
    }
}
