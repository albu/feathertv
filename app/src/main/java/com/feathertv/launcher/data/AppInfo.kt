package com.feathertv.launcher.data

import android.graphics.drawable.Drawable

data class AppInfo(
    val packageName: String,
    val activityName: String,
    val label: String,
    val icon: Drawable?,
    val tileColor: Int? = null,
    val isHidden: Boolean = false
) {
    val uniqueKey: String
        get() = "$packageName/$activityName"
}
