package com.feathertv.launcher.data

import android.app.ActivityManager
import android.content.Context

/**
 * In-launcher equivalent of scripts/optimize-memory.sh: stops idle media apps
 * that are sitting in the background. Uses the public killBackgroundProcesses
 * API (no root), so apps that are actively playing or foregrounded are left
 * alone. Apps restart normally when opened.
 */
object MemoryOptimizer {

    // Mirrors the MEDIA_APPS list in scripts/optimize-memory.sh.
    private val MEDIA_APPS = listOf(
        "com.apple.atve.androidtv.appletv",    // Apple TV
        "com.netflix.ninja",                   // Netflix
        "ru.kinopoisk.tv",                     // Kinopoisk
        "com.amazon.amazonvideo.livingroom",   // Prime Video
        "com.yle.webtv",                       // YLE Areena
        "tv.wuaki.apptv"                       // Rakuten TV
    )

    /** Returns the number of installed background apps that were asked to stop. */
    fun optimize(context: Context): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        var stopped = 0
        for (pkg in MEDIA_APPS) {
            try {
                context.packageManager.getPackageInfo(pkg, 0) // throws if not installed
                am.killBackgroundProcesses(pkg)
                stopped++
            } catch (e: Exception) {
                // Package missing or kill rejected; skip quietly.
            }
        }
        return stopped
    }
}
