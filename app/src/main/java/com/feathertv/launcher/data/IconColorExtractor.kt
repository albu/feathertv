package com.feathertv.launcher.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.palette.graphics.Palette

/**
 * Extracts a single dominant color from an app icon. Cheap by design: the
 * drawable is rendered into a tiny bitmap (max 64px) and Palette runs on that.
 * Always call from a background thread, never on the UI thread.
 */
object IconColorExtractor {

    private const val SAMPLE_SIZE = 64
    private const val MAX_COLORS = 12

    fun extract(drawable: Drawable?): Int? {
        if (drawable == null) return null
        val intrinsicWidth = drawable.intrinsicWidth
        val intrinsicHeight = drawable.intrinsicHeight
        if (intrinsicWidth <= 0 || intrinsicHeight <= 0) return null

        val scale = minOf(1f, SAMPLE_SIZE / maxOf(intrinsicWidth, intrinsicHeight).toFloat())
        val width = (intrinsicWidth * scale).toInt().coerceAtLeast(1)
        val height = (intrinsicHeight * scale).toInt().coerceAtLeast(1)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, width, height)
            drawable.draw(canvas)

            val palette = Palette.from(bitmap).maximumColorCount(MAX_COLORS).generate()
            return palette.vibrantSwatch
                ?.let { it.rgb }
                ?: palette.dominantSwatch?.let { it.rgb }
                ?: palette.mutedSwatch?.let { it.rgb }
        } catch (e: Exception) {
            return null
        } finally {
            bitmap.recycle()
        }
    }
}
