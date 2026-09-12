package com.feathertv.launcher.data

import android.app.ActivityManager
import android.content.Context

/**
 * Strict 2-App RAM Management:
 * Ensures at most 2 media apps remain resident in RAM simultaneously.
 * When a 3rd media app is opened, the oldest media app and any background
 * auto-started streaming apps are killed via killBackgroundProcesses,
 * preventing MediaTek CMA / ION video decoder memory exhaustion and kernel panics.
 */
object MemoryOptimizer {

    const val MAX_RESIDENT_MEDIA_APPS = 2

    // Media apps that consume hardware video decoders and heavy background RAM.
    val MEDIA_APPS = listOf(
        "com.google.android.youtube.tv",       // YouTube
        "com.amazon.amazonvideo.livingroom",   // Prime Video
        "com.apple.atve.androidtv.appletv",    // Apple TV
        "com.netflix.ninja",                   // Netflix
        "ru.kinopoisk.tv",                     // Kinopoisk
        "com.wbd.stream",                      // Max / HBO Max
        "com.disney.disneyplus",               // Disney+
        "com.yle.webtv",                       // YLE Areena
        "tv.wuaki.apptv"                       // Rakuten TV
    )

    /**
     * Called when an app is launched. If it's a media app, updates the MRU list
     * and evicts any media app beyond the 2 most recent.
     */
    fun onAppLaunched(context: Context, packageName: String) {
        val prefs = AppPreferences(context)
        val isMedia = packageName in MEDIA_APPS

        val recent = prefs.getRecentMediaApps().toMutableList()
        if (isMedia) {
            recent.remove(packageName)
            recent.add(0, packageName)
            prefs.setRecentMediaApps(recent.take(MAX_RESIDENT_MEDIA_APPS))
        }

        val keepInRam = recent.take(MAX_RESIDENT_MEDIA_APPS).toSet()

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (pkg in MEDIA_APPS) {
            if (pkg !in keepInRam && pkg != packageName) {
                try {
                    am.killBackgroundProcesses(pkg)
                } catch (e: Exception) {
                    // Package missing or kill rejected; skip quietly.
                }
            }
        }
    }

    /**
     * Trims background media apps so only the 2 most recent are allowed in RAM.
     * Evicts any background streaming apps that auto-started in the background.
     */
    fun trimToRecentMediaApps(context: Context): Int {
        val prefs = AppPreferences(context)
        val keepInRam = prefs.getRecentMediaApps().take(MAX_RESIDENT_MEDIA_APPS).toSet()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        var stopped = 0
        for (pkg in MEDIA_APPS) {
            if (pkg !in keepInRam) {
                try {
                    context.packageManager.getPackageInfo(pkg, 0)
                    am.killBackgroundProcesses(pkg)
                    stopped++
                } catch (e: Exception) {
                    // Missing or rejected
                }
            }
        }
        return stopped
    }

    /** Evicts all media apps from background (e.g. for search or manual boost). */
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
