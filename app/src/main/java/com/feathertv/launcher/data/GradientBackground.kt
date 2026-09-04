package com.feathertv.launcher.data

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Generates the Midnight Sky background with ordered (Bayer 8x8) dithering
 * baked directly into the pixels, so an 8-bit UI/compositor pipeline cannot
 * show gradient bands. Generated once at startup.
 */
object GradientBackground {

    // 8x8 Bayer matrix (0..63).
    private val BAYER = arrayOf(
        intArrayOf(0, 32, 8, 40, 2, 34, 10, 42),
        intArrayOf(48, 16, 56, 24, 50, 18, 58, 26),
        intArrayOf(12, 44, 4, 36, 14, 46, 6, 38),
        intArrayOf(60, 28, 52, 20, 62, 30, 54, 22),
        intArrayOf(3, 35, 11, 43, 1, 33, 9, 41),
        intArrayOf(51, 19, 59, 27, 49, 17, 57, 25),
        intArrayOf(15, 47, 7, 39, 13, 45, 5, 37),
        intArrayOf(63, 31, 55, 23, 61, 29, 53, 21)
    )

    // Vertical gradient stops (0..255): top sapphire -> mid slate -> bottom obsidian.
    // Deliberately wider than a barely-there tint so the fade is actually visible.
    private val TOP = intArrayOf(36, 59, 102)   // #243B66
    private val MID = intArrayOf(20, 34, 63)    // #14223F
    private val BOTTOM = intArrayOf(7, 10, 18)  // #070A12
    private const val MID_FRACTION = 0.35f

    fun generate(width: Int, height: Int): Bitmap {
        val pixels = IntArray(width * height)
        val base = FloatArray(3)
        for (y in 0 until height) {
            val t = y.toFloat() / height
            if (t < MID_FRACTION) {
                lerp(base, TOP, MID, t / MID_FRACTION)
            } else {
                lerp(base, MID, BOTTOM, (t - MID_FRACTION) / (1f - MID_FRACTION))
            }

            val row = y * width
            val by = y and 7
            for (x in 0 until width) {
                // Ordered dither: add [-0.5, 0.5) noise scaled to one 8-bit step.
                val noise = (BAYER[by][x and 7] - 31.5f) / 64f
                val r = (base[0] + noise).toInt().coerceIn(0, 255)
                val g = (base[1] + noise).toInt().coerceIn(0, 255)
                val b = (base[2] + noise).toInt().coerceIn(0, 255)
                pixels[row + x] = Color.rgb(r, g, b)
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun lerp(out: FloatArray, a: IntArray, b: IntArray, u: Float) {
        val inv = 1f - u
        out[0] = a[0] * inv + b[0] * u
        out[1] = a[1] * inv + b[1] * u
        out[2] = a[2] * inv + b[2] * u
    }
}
