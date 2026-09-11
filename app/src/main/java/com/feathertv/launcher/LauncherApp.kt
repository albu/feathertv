package com.feathertv.launcher

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.http.HttpResponseCache
import android.util.Log
import com.feathertv.launcher.wiz.LedStripController
import com.feathertv.launcher.wiz.WizLedStripController
import java.io.File

class LauncherApp : Application() {

    companion object {
        private const val TAG = "LauncherApp"
        val ledController: LedStripController by lazy { WizLedStripController.getInstance() }
    }

    private val screenPowerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> {
                    Log.d(TAG, "TV Screen ON (Wake / HDMI-CEC / Remote) -> Notifying LED Controller")
                    ledController.onScreenPowerChanged(true)
                }
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d(TAG, "TV Screen OFF (Standby / HDMI-CEC) -> Notifying LED Controller")
                    ledController.onScreenPowerChanged(false)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Initializing YB Launcher & LED Strip Controller")
        ledController.init(this)

        // Install HTTP response cache for HttpURLConnection (15MB on flash)
        try {
            val httpCacheDir = File(cacheDir, "http")
            val httpCacheSize = 15L * 1024L * 1024L
            if (HttpResponseCache.getInstalled() == null) {
                HttpResponseCache.install(httpCacheDir, httpCacheSize)
            }
        } catch (e: Exception) {
            Log.w(TAG, "HTTP response cache installation failed", e)
        }

        // Register power receiver across the entire app lifecycle to catch Xbox HDMI-CEC wake/sleep
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenPowerReceiver, filter)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_MODERATE) {
            try {
                HttpResponseCache.getInstalled()?.flush()
            } catch (e: Exception) {
                // Ignore flush failures on trim
            }
        }
    }
}
