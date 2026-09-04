package com.feathertv.launcher.ui

import android.view.View
import androidx.core.view.ViewCompat

object FocusAnimator {

    private const val FOCUS_SCALE = 1.08f
    private const val UNFOCUS_SCALE = 1.0f
    private const val DURATION_MS = 150L

    fun applyFocusEffect(view: View, hasFocus: Boolean) {
        val targetScale = if (hasFocus) FOCUS_SCALE else UNFOCUS_SCALE
        val targetElevation = if (hasFocus) 12f else 0f

        view.animate()
            .scaleX(targetScale)
            .scaleY(targetScale)
            .setDuration(DURATION_MS)
            .start()

        ViewCompat.setElevation(view, targetElevation)
    }

}
