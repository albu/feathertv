package com.feathertv.launcher.wiz

import android.content.Context
import android.util.Log
import com.feathertv.launcher.data.AppInfo
import com.feathertv.launcher.data.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

class WizLedStripController private constructor() : LedStripController {

    companion object {
        private const val TAG = "WizLedController"
        val TARGET_MAC: String get() = com.feathertv.launcher.BuildConfig.WIZ_MAC
        val DEFAULT_IP: String get() = com.feathertv.launcher.BuildConfig.DEFAULT_WIZ_IP

        @Volatile
        private var instance: WizLedStripController? = null

        fun getInstance(): WizLedStripController {
            return instance ?: synchronized(this) {
                instance ?: WizLedStripController().also { instance = it }
            }
        }
    }

    private enum class ActiveMode {
        OFF,
        RGB,
        KELVIN
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var preferences: AppPreferences? = null

    @Volatile
    override var isAvailable: Boolean = false
        private set

    @Volatile
    private var targetIp: String = DEFAULT_IP

    override val currentIp: String
        get() = targetIp

    @Volatile
    private var activeMode: ActiveMode = ActiveMode.OFF

    @Volatile
    private var lastSentKelvin: Int = -1

    @Volatile
    private var lastSentDimming: Int = -1

    private var commandJob: Job? = null
    private var focusDebounceJob: Job? = null
    private var periodicTickerJob: Job? = null
    private var isLauncherForeground: Boolean = true
    private var isScreenOn: Boolean = true

    override fun init(context: Context) {
        val prefs = AppPreferences(context.applicationContext)
        preferences = prefs
        targetIp = prefs.wizTargetIp.ifEmpty { DEFAULT_IP }

        if (prefs.wizSyncEnabled) {
            discover()
        }

        startPeriodicTicker()
    }

    private fun startPeriodicTicker() {
        periodicTickerJob?.cancel()
        periodicTickerJob = scope.launch {
            while (true) {
                delay(120_000) // Re-evaluate every 2 minutes for seamless sunset/twilight progression
                try {
                    if (!isScreenOn) continue
                    val prefs = preferences ?: continue
                    if (!prefs.wizSyncEnabled) continue

                    // When outside the launcher (streaming movie or other app active), track live sun position
                    if (!isLauncherForeground) {
                        if (prefs.streamingPreset == "CIRCADIAN") {
                            setStreamingModeInternal(force = false)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Periodic ticker error: ${e.message}")
                }
            }
        }
    }

    override fun discover(onResult: ((Boolean, String?) -> Unit)?) {
        scope.launch {
            try {
                val foundIp = WizProtocol.broadcastDiscovery(TARGET_MAC, timeoutMs = 1500)
                if (foundIp != null) {
                    targetIp = foundIp
                    isAvailable = true
                    preferences?.wizTargetIp = foundIp
                    Log.d(TAG, "WiZ Strip discovered at: $foundIp")
                    onResult?.invoke(true, foundIp)
                } else {
                    isAvailable = false
                    onResult?.invoke(false, targetIp)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Discovery failed: ${e.message}")
                isAvailable = false
                onResult?.invoke(false, null)
            }
        }
    }

    private fun scaleDimming(baseDimming: Int): Int {
        val userScale = preferences?.brightnessScale ?: 80
        val scaled = (baseDimming * userScale / 80.0).toInt()
        return scaled.coerceIn(10, 100)
    }

    override fun onScreenPowerChanged(isScreenOn: Boolean) {
        this.isScreenOn = isScreenOn
        val prefs = preferences ?: return
        if (!prefs.wizSyncEnabled) return
        if (!prefs.wizAutoPowerEnabled) {
            Log.d(TAG, "Screen state changed ($isScreenOn) but auto-power is disabled in settings")
            return
        }

        if (isScreenOn) {
            Log.d(TAG, "Screen turned ON -> Applying Streaming / Bias Lighting Mode")
            lastSentKelvin = -1
            lastSentDimming = -1
            setStreamingModeInternal(force = true)
        } else {
            Log.d(TAG, "Screen turned OFF (TV Standby) -> Turning off backlight")
            turnOff()
        }
    }

    override fun onAppFocused(app: AppInfo) {
        val prefs = preferences ?: return
        if (!prefs.wizSyncEnabled || !prefs.wizAppColorsEnabled) return

        val color = app.tileColor
        if (color == null) {
            // Uncolored tile fallback: maintain gentle ambient streaming preset instead of turning off
            setStreamingModeInternal(force = false)
            return
        }

        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF

        // Invalidate kelvin cache immediately so subsequent pause/streaming transitions are never blocked
        lastSentKelvin = -1
        lastSentDimming = -1

        focusDebounceJob?.cancel()
        focusDebounceJob = scope.launch {
            delay(40) // 40ms debounce for smooth D-pad scrolling
            val dim = scaleDimming(35)
            val payload = WizProtocol.buildSetRgb(r, g, b, dimming = dim)
            activeMode = ActiveMode.RGB
            sendPayload(payload, repeatCount = 1)
        }
    }

    override fun onLauncherResumed(currentFocusedApp: AppInfo?) {
        isLauncherForeground = true
        val prefs = preferences ?: return
        if (!prefs.wizSyncEnabled) return

        focusDebounceJob?.cancel()
        if (prefs.wizAppColorsEnabled && currentFocusedApp != null) {
            onAppFocused(currentFocusedApp)
        } else {
            setStreamingModeInternal(force = true)
        }
    }

    override fun onLauncherPaused() {
        isLauncherForeground = false
        val prefs = preferences ?: return
        if (!prefs.wizSyncEnabled) return

        // Leaving launcher for streaming (Netflix, YouTube, Prime, etc.): cancel focus debounce & apply streaming preset immediately
        focusDebounceJob?.cancel()
        setStreamingModeInternal(force = true)
    }

    override fun turnOn() {
        setStreamingModeInternal(force = true)
    }

    override fun turnOff() {
        focusDebounceJob?.cancel()
        commandJob?.cancel()
        activeMode = ActiveMode.OFF
        lastSentKelvin = -1
        lastSentDimming = -1
        scope.launch {
            sendPayload(WizProtocol.buildSetPower(false), repeatCount = 2)
        }
    }

    override fun setRgb(r: Int, g: Int, b: Int, dimmingPercent: Int) {
        val prefs = preferences
        if (prefs != null && !prefs.wizSyncEnabled) return

        focusDebounceJob?.cancel()
        commandJob?.cancel()
        lastSentKelvin = -1
        lastSentDimming = -1
        activeMode = ActiveMode.RGB
        commandJob = scope.launch {
            val dim = scaleDimming(dimmingPercent)
            sendPayload(WizProtocol.buildSetRgb(r, g, b, dim), repeatCount = 2)
        }
    }

    private fun sendKelvin(kelvin: Int, dimming: Int, force: Boolean = false) {
        val shouldSend = force ||
            activeMode != ActiveMode.KELVIN ||
            kelvin != lastSentKelvin ||
            Math.abs(dimming - lastSentDimming) >= 1

        if (shouldSend) {
            val isModeTransition = activeMode != ActiveMode.KELVIN
            activeMode = ActiveMode.KELVIN
            lastSentKelvin = kelvin
            lastSentDimming = dimming
            val payload = WizProtocol.buildSetKelvin(kelvin, dimming)
            // Use 2-packet burst when forcing or switching modes to guarantee delivery over Wi-Fi
            val repeats = if (force || isModeTransition) 2 else 1
            sendPayload(payload, repeatCount = repeats)
        }
    }

    override fun setCircadianBias() {
        setStreamingModeInternal(force = true)
    }

    override fun setStreamingMode() {
        setStreamingModeInternal(force = false)
    }

    private fun setStreamingModeInternal(force: Boolean) {
        val prefs = preferences ?: return
        if (!prefs.wizSyncEnabled) return

        focusDebounceJob?.cancel()
        commandJob?.cancel()
        commandJob = scope.launch {
            when (prefs.streamingPreset) {
                "CINEMA_WARM" -> {
                    val dim = scaleDimming(50)
                    Log.d(TAG, "Applying Cinema Warm Streaming Preset (2700K @ $dim%, force=$force)")
                    sendKelvin(2700, dim, force = force)
                }
                "D65_NEUTRAL" -> {
                    val dim = scaleDimming(75)
                    Log.d(TAG, "Applying D65 Neutral Streaming Preset (6500K @ $dim%, force=$force)")
                    sendKelvin(6500, dim, force = force)
                }
                "COZY_DIM" -> {
                    val dim = scaleDimming(25)
                    Log.d(TAG, "Applying Cozy Dim Streaming Preset (2200K @ $dim%, force=$force)")
                    sendKelvin(2200, dim, force = force)
                }
                "VIVID_BRIGHT" -> {
                    val dim = scaleDimming(90)
                    Log.d(TAG, "Applying Vivid Bright Streaming Preset (6000K @ $dim%, force=$force)")
                    sendKelvin(6000, dim, force = force)
                }
                "OFF" -> {
                    Log.d(TAG, "Streaming Preset: OFF (Dark Cinema, force=$force)")
                    activeMode = ActiveMode.OFF
                    lastSentKelvin = -1
                    lastSentDimming = -1
                    sendPayload(WizProtocol.buildSetPower(false), repeatCount = 2)
                }
                else -> { // "CIRCADIAN" (Solar Curve)
                    val setting = CircadianEngine.calculate()
                    val dim = scaleDimming(setting.dimmingPercent)
                    Log.d(TAG, "Applying Solar Circadian Streaming Mode (${setting.tempKelvin}K @ $dim%, force=$force, Elevation: ${"%.1f".format(setting.solarElevationDeg)}°)")
                    sendKelvin(setting.tempKelvin, dim, force = force)
                }
            }
        }
    }

    override fun setGamingMode() {
        val prefs = preferences ?: return
        if (!prefs.wizSyncEnabled) return

        focusDebounceJob?.cancel()
        commandJob?.cancel()
        commandJob = scope.launch {
            when (prefs.gamingPreset) {
                "XBOX_GREEN" -> {
                    val dim = scaleDimming(75)
                    Log.d(TAG, "Applying Xbox Emerald Green Gaming Preset (#107C10 @ $dim%)")
                    activeMode = ActiveMode.RGB
                    lastSentKelvin = -1
                    lastSentDimming = -1
                    sendPayload(WizProtocol.buildSetRgb(16, 124, 16, dim), repeatCount = 2)
                }
                "CYBERPUNK_PURPLE" -> {
                    val dim = scaleDimming(75)
                    Log.d(TAG, "Applying Cyberpunk Purple Gaming Preset (#9C27B0 @ $dim%)")
                    activeMode = ActiveMode.RGB
                    lastSentKelvin = -1
                    lastSentDimming = -1
                    sendPayload(WizProtocol.buildSetRgb(156, 39, 176, dim), repeatCount = 2)
                }
                "D65_NEUTRAL" -> {
                    val dim = scaleDimming(70)
                    Log.d(TAG, "Applying D65 Neutral 6500K Gaming Preset (@ $dim%)")
                    sendKelvin(6500, dim, force = true)
                }
                else -> { // "EYE_FIRST" (Adaptive Eye-First: high clarity 6000K daylight/evening, warm 3000K late night)
                    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                    if (hour >= 22 || hour < 6) {
                        val dim = scaleDimming(45)
                        Log.d(TAG, "Applying Eye-First Gaming Preset (Late Night Rest: 3000K @ $dim%)")
                        sendKelvin(3000, dim, force = true)
                    } else {
                        val dim = scaleDimming(70)
                        Log.d(TAG, "Applying Eye-First Gaming Preset (High Acuity Focus: 6000K @ $dim%)")
                        sendKelvin(6000, dim, force = true)
                    }
                }
            }
        }
    }

    override fun onHdmiActivated() {
        Log.d(TAG, "HDMI / Gaming Mode explicitly triggered -> Switching to Gaming Preset")
        setGamingMode()
    }

    override fun setKelvin(kelvin: Int, dimmingPercent: Int) {
        val prefs = preferences
        if (prefs != null && !prefs.wizSyncEnabled) return

        focusDebounceJob?.cancel()
        commandJob?.cancel()
        commandJob = scope.launch {
            val dim = scaleDimming(dimmingPercent)
            sendKelvin(kelvin, dim, force = true)
        }
    }

    private fun sendPayload(json: String, repeatCount: Int = 1) {
        try {
            WizProtocol.sendUdp(targetIp, json, repeatCount = repeatCount)
        } catch (e: Exception) {
            Log.w(TAG, "UDP send error to $targetIp: ${e.message}")
            discover()
        }
    }
}
