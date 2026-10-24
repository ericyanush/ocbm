package com.carlink.gnss

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.HandlerThread
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max
import kotlin.math.min
import org.json.JSONObject

/** Reads the AAOS vehicle GPS provider and emits the GPGGA/GPRMC sentences requested by CarPlay. */
class GnssForwarder(
    context: Context,
    private val sendNmea: (String) -> String,
    private val log: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var listener: LocationListener? = null
    private var thread: HandlerThread? = null

    @Volatile private var running = false

    @Synchronized
    fun start() {
        if (running) return
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            log("[GNSS] fine location permission unavailable; forwarding remains disabled")
            return
        }
        val callback = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val payload = formatNmea(location)
                val report = runCatching { JSONObject(sendNmea(payload)) }.getOrElse {
                    JSONObject().put("reason", "forwarding_exception").put("errorType", it.javaClass.simpleName)
                }
                val sent = report.optInt("sentSentences") > 0
                val accuracy = if (location.hasAccuracy()) "${location.accuracy}m" else "unknown"
                log(
                    "[GNSS] fix lat=${"%.5f".format(Locale.US, location.latitude)} " +
                        "lon=${"%.5f".format(Locale.US, location.longitude)} " +
                        "accuracy=$accuracy sent=$sent forwarding=$report",
                )
            }

            override fun onProviderEnabled(provider: String) {
                log("[GNSS] provider enabled: $provider")
            }

            override fun onProviderDisabled(provider: String) {
                log("[GNSS] provider disabled: $provider")
            }

            @Deprecated("Deprecated in API 29")
            override fun onStatusChanged(
                provider: String?,
                status: Int,
                extras: Bundle?,
            ) = Unit
        }
        try {
            val callbackThread = HandlerThread("CarPlay-GNSS").apply { start() }
            thread = callbackThread
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1_000L,
                0f,
                callback,
                callbackThread.looper,
            )
            listener = callback
            running = true
            log("[GNSS] forwarding started (GPS_PROVIDER, 1 Hz, background callback thread)")
        } catch (t: Throwable) {
            thread?.quitSafely()
            thread = null
            listener = null
            log("[GNSS] unable to start location updates: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    @Synchronized
    fun stop() {
        if (!running) return
        listener?.let { runCatching { locationManager.removeUpdates(it) } }
        listener = null
        thread?.quitSafely()
        thread = null
        running = false
        log("[GNSS] forwarding stopped")
    }

    private fun formatNmea(location: Location): String {
        val time = utcTime(location.time)
        val date = utcDate(location.time)
        val (lat, latDir) = coordinate(location.latitude, 2)
        val (lon, lonDir) = coordinate(location.longitude, 3)
        val hdop = if (location.hasAccuracy()) "%.1f".format(Locale.US, min(25.0, max(0.5, location.accuracy / 5.0))) else "1.0"
        val satellites =
            location.extras
                ?.getInt("satellites", 0)
                ?.takeIf { it > 0 }
                ?.coerceAtMost(24)
                ?: if (location.hasAccuracy()) {
                    when {
                        location.accuracy < 3f -> 12
                        location.accuracy < 10f -> 8
                        location.accuracy < 30f -> 5
                        else -> 4
                    }
                } else {
                    8
                }
        val altitude = "%.1f".format(Locale.US, location.altitude)
        val gga = sentence("GPGGA,$time,$lat,$latDir,$lon,$lonDir,1,${"%02d".format(Locale.US, satellites)},$hdop,$altitude,M,0.0,M,,")
        val speedKnots = "%.1f".format(Locale.US, location.speed * 1.94384)
        val course = if (location.hasBearing()) "%.1f".format(Locale.US, location.bearing) else "0.0"
        val rmc = sentence("GPRMC,$time,A,$lat,$latDir,$lon,$lonDir,$speedKnots,$course,$date,,,A")
        return gga + rmc
    }

    private fun sentence(body: String): String {
        val checksum = body.fold(0) { value, char -> value xor char.code }
        return "\$$body*${"%02X".format(Locale.US, checksum)}\r\n"
    }

    private fun coordinate(
        value: Double,
        degreeDigits: Int,
    ): Pair<String, String> {
        val absolute = kotlin.math.abs(value)
        val degrees = absolute.toInt()
        val minutes = (absolute - degrees) * 60.0
        val direction =
            if (degreeDigits == 2) {
                if (value >= 0) "N" else "S"
            } else {
                if (value >= 0) "E" else "W"
            }
        return "%0${degreeDigits}d%07.4f".format(Locale.US, degrees, minutes) to direction
    }

    private fun utcTime(millis: Long) =
        SimpleDateFormat("HHmmss.00", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(millis)

    private fun utcDate(millis: Long) =
        SimpleDateFormat("ddMMyy", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(millis)
}
