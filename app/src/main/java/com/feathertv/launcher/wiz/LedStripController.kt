package com.feathertv.launcher.wiz

import android.content.Context
import com.feathertv.launcher.data.AppInfo

/**
 * Unified interface for TV LED Backlight control across YB Launcher,
 * lifecycle events, and settings.
 */
interface LedStripController {

    val isAvailable: Boolean
    val currentIp: String

    fun init(context: Context)
    fun discover(onResult: ((Boolean, String?) -> Unit)? = null)

    // System & Lifecycle hooks
    fun onScreenPowerChanged(isScreenOn: Boolean)
    fun onAppFocused(app: AppInfo)
    fun onLauncherResumed(currentFocusedApp: AppInfo? = null)
    fun onLauncherPaused()
    fun onHdmiActivated()

    // Explicit lighting commands
    fun turnOn()
    fun turnOff()
    fun setRgb(r: Int, g: Int, b: Int, dimmingPercent: Int = 35)
    fun setCircadianBias()
    fun setStreamingMode()
    fun setGamingMode()
    fun setKelvin(kelvin: Int, dimmingPercent: Int = 80)
}
