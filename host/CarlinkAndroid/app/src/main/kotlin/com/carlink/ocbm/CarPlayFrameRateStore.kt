package com.carlink.ocbm

import android.content.Context

/** Maximum frame rate offered to the phone for a new CarPlay session. */
enum class CarPlayFrameRate(val label: String, val fps: Int?) {
    AUTO("Auto", null), FPS_30("30 fps", 30), FPS_60("60 fps", 60);

    fun resolve(detected: Int): Int = fps ?: detected
}

internal class CarPlayFrameRateStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("carlink_receiver", Context.MODE_PRIVATE)
    val mode: CarPlayFrameRate get() = runCatching {
        CarPlayFrameRate.valueOf(prefs.getString("frame_rate", CarPlayFrameRate.AUTO.name) ?: CarPlayFrameRate.AUTO.name)
    }.getOrDefault(CarPlayFrameRate.AUTO)
    fun save(mode: CarPlayFrameRate): Boolean = prefs.edit().putString("frame_rate", mode.name).commit()
}
