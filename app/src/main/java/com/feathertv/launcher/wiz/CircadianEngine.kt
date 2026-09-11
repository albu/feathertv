package com.feathertv.launcher.wiz

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin

data class CircadianSetting(
    val tempKelvin: Int,
    val dimmingPercent: Int,
    val solarElevationDeg: Double
)

/**
 * Astronomical solar calculation engine for adaptive circadian bias lighting.
 *
 * Accounts for seasonal day-length variance by calculating solar elevation angle (α)
 * and mapping it to scientifically optimal bias lighting (calibrated for diffuse wall bounce):
 * - Full Daylight (α > 5°): Reference D65 (6500K @ 85%) to overcome ambient daylight and boost LCD contrast.
 * - Twilight / Sunset (-6° <= α <= 5°): Smooth transition 6500K -> 3500K, 85% -> 60% dimming.
 * - Nautical / Astronomical Dusk (-18° <= α < -6°): Smooth transition 3500K -> 2600K, 60% -> 48% dimming.
 * - Deep Night (α < -18°):
 *   - Evening (before 23:00): Warm 2600K @ 48%.
 *   - Late Night (23:00 - 06:00): Melatonin-protective ultra-warm 2200K @ 32%.
 */
object CircadianEngine {

    val LATITUDE: Double get() = com.feathertv.launcher.BuildConfig.CIRCADIAN_LAT.toDoubleOrNull() ?: 51.5074
    val LONGITUDE: Double get() = com.feathertv.launcher.BuildConfig.CIRCADIAN_LON.toDoubleOrNull() ?: -0.1278

    fun calculate(calendar: Calendar = Calendar.getInstance()): CircadianSetting {
        val elevation = calculateSolarElevation(calendar, LATITUDE, LONGITUDE)
        val localHour = calendar.get(Calendar.HOUR_OF_DAY) + calendar.get(Calendar.MINUTE) / 60.0

        val (temp, dimming) = when {
            elevation > 5.0 -> {
                // Daylight: Crisp D65 bias with enough power to overcome ambient diffuse room light
                6500 to 85
            }
            elevation >= -6.0 -> {
                // Civil Twilight / Sunset (+5° down to -6°): smooth transition 6500K -> 3500K, 85% -> 60%
                val factor = (elevation - (-6.0)) / (5.0 - (-6.0))
                val k = (3500 + (6500 - 3500) * factor).toInt()
                val d = (60 + (85 - 60) * factor).toInt()
                k to d
            }
            elevation >= -18.0 -> {
                // Dusk / Evening (-6° down to -18°): smooth transition 3500K -> 2600K, 60% -> 48%
                val factor = (elevation - (-18.0)) / ((-6.0) - (-18.0))
                val k = (2600 + (3500 - 2600) * factor).toInt()
                val d = (48 + (60 - 48) * factor).toInt()
                k to d
            }
            else -> {
                // Deep Night:
                if (localHour >= 23.0 || localHour < 6.0) {
                    // Late Night Bedtime: 2200K warm candle glow @ 32% (comfortably visible yet zero blue light)
                    2200 to 32
                } else {
                    // Evening Cinema: 2600K @ 48%
                    2600 to 48
                }
            }
        }

        return CircadianSetting(temp, dimming, elevation)
    }

    private fun calculateSolarElevation(cal: Calendar, lat: Double, lon: Double): Double {
        val utcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = cal.timeInMillis
        }
        val dayOfYear = utcCal.get(Calendar.DAY_OF_YEAR)
        val utcHour = utcCal.get(Calendar.HOUR_OF_DAY) +
                utcCal.get(Calendar.MINUTE) / 60.0 +
                utcCal.get(Calendar.SECOND) / 3600.0

        val gamma = 2.0 * Math.PI / 365.0 * (dayOfYear - 1 + (utcHour - 12.0) / 24.0)

        // Equation of time in minutes
        val eqtime = 229.18 * (0.000075 + 0.001868 * cos(gamma) - 0.032077 * sin(gamma)
                - 0.014615 * cos(2.0 * gamma) - 0.040849 * sin(2.0 * gamma))

        // Solar declination in radians
        val decl = 0.006918 - 0.399912 * cos(gamma) + 0.070257 * sin(gamma) -
                0.006758 * cos(2.0 * gamma) + 0.000907 * sin(2.0 * gamma) -
                0.002697 * cos(3.0 * gamma) + 0.00148 * sin(3.0 * gamma)

        val timeOffset = eqtime + 4.0 * lon
        val tst = (utcHour * 60.0 + timeOffset) % 1440.0
        val ha = Math.toRadians((tst / 4.0) - 180.0)

        val latRad = Math.toRadians(lat)
        val sinElev = sin(latRad) * sin(decl) + cos(latRad) * cos(decl) * cos(ha)
        val clampedSin = sinElev.coerceIn(-1.0, 1.0)
        return Math.toDegrees(asin(clampedSin))
    }
}
