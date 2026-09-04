package com.feathertv.launcher.ui

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.SoundEffectConstants
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Toast
import com.feathertv.launcher.BuildConfig
import com.feathertv.launcher.LauncherApp
import com.feathertv.launcher.R
import com.feathertv.launcher.data.AppInfo
import com.feathertv.launcher.data.AppPreferences
import com.feathertv.launcher.data.MemoryOptimizer
import com.feathertv.launcher.data.Providers
import com.feathertv.launcher.databinding.DialogSettingsBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Leanback-style side panel: right-docked, full height, matching the look of the
 * stock Android TV settings (com.android.tv.settings).
 */
class SettingsDialog(
    context: Context,
    private val onSettingsChanged: () -> Unit,
    private val apps: List<AppInfo> = emptyList(),
    private val onManageApp: (AppInfo) -> Unit = {}
) : Dialog(context) {

    private lateinit var binding: DialogSettingsBinding
    private val preferences = AppPreferences(context)

    private var focusApplied = false

    companion object {
        private const val PANEL_WIDTH_DP = 360f
        private val REGIONS = listOf("FI", "SE", "DE", "GB", "US")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        binding = DialogSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Size and dock the window itself (360dp wide, full height, right edge);
        // the layout root fills the window and forms the side panel.
        val panelWidthPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, PANEL_WIDTH_DP, context.resources.displayMetrics
        ).toInt()
        window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.END or Gravity.TOP)
            setLayout(panelWidthPx, WindowManager.LayoutParams.MATCH_PARENT)
            setDimAmount(0f)
            setWindowAnimations(R.style.SettingsPanelAnimation)
        }

        setCanceledOnTouchOutside(true)

        updateUi()
        setupListeners()
    }

    override fun show() {
        super.show()
        binding.btnColumns.post {
            if (!focusApplied) {
                focusApplied = true
                binding.btnColumns.requestFocus()
            }
        }
    }

    override fun onBackPressed() {
        if (binding.gridColumnsOptions.visibility == View.VISIBLE) {
            collapseColumns()
        } else if (binding.subscriptionsOptions.visibility == View.VISIBLE) {
            collapseSubscriptions()
        } else {
            super.onBackPressed()
        }
    }

    private fun updateUi() {
        val currentColumns = preferences.gridColumns
        binding.tvColumnsValue.text =
            context.getString(R.string.settings_columns_value, currentColumns)

        val hiddenCount = preferences.getHiddenApps().size
        binding.tvHiddenAppsStatus.text = if (hiddenCount == 0) {
            context.getString(R.string.settings_hidden_none)
        } else {
            context.getString(R.string.settings_hidden_count, hiddenCount)
        }

        val activeSubs = preferences.getActiveSubscriptions()
        binding.tvSubscriptionsValue.text = when {
            activeSubs.isEmpty() -> context.getString(R.string.settings_subscriptions_none)
            activeSubs.size == Providers.ALL_PROVIDERS.size -> context.getString(R.string.settings_subscriptions_all)
            else -> {
                val labels = Providers.ALL_PROVIDERS
                    .filter { it.first in activeSubs }
                    .map { it.second }
                if (labels.size <= 2) labels.joinToString(", ")
                else context.getString(R.string.settings_subscriptions_count, labels.size)
            }
        }
        updateSubscriptionChecks()

        binding.tvRegionValue.text = preferences.searchRegion
        binding.tvTmdbStatus.text = if (BuildConfig.TMDB_API_KEY.isBlank()) {
            context.getString(R.string.settings_tmdb_missing)
        } else {
            context.getString(R.string.settings_tmdb_configured)
        }

        updateColumnChecks(currentColumns)
        updateWizUi()
    }

    private fun updateColumnChecks(currentColumns: Int) {
        mapOf(
            binding.checkColumn4 to 4,
            binding.checkColumn5 to 5,
            binding.checkColumn6 to 6,
            binding.checkColumn7 to 7
        ).forEach { (check, columns) ->
            check.visibility = if (columns == currentColumns) View.VISIBLE else View.INVISIBLE
        }
    }

    private fun updateSubscriptionChecks() {
        binding.checkSubApple.visibility =
            if (preferences.isSubscriptionActive(Providers.APPLE_PACKAGE)) View.VISIBLE else View.INVISIBLE
        binding.checkSubPrime.visibility =
            if (preferences.isSubscriptionActive(Providers.PRIME_PACKAGE)) View.VISIBLE else View.INVISIBLE
        binding.checkSubNetflix.visibility =
            if (preferences.isSubscriptionActive(Providers.NETFLIX_PACKAGE)) View.VISIBLE else View.INVISIBLE
        binding.checkSubDisney.visibility =
            if (preferences.isSubscriptionActive(Providers.DISNEY_PACKAGE)) View.VISIBLE else View.INVISIBLE
    }

    private fun updateWizUi() {
        val wizEnabled = preferences.wizSyncEnabled
        if (wizEnabled) {
            val ip = LauncherApp.ledController.currentIp
            val isAvailable = LauncherApp.ledController.isAvailable
            if (isAvailable) {
                binding.tvWizPowerValue.text = context.getString(R.string.settings_wiz_status_connected, ip)
                binding.tvWizPowerValue.setTextColor(context.getColor(R.color.accent))
            } else {
                binding.tvWizPowerValue.text = context.getString(R.string.settings_wiz_status_searching, ip)
                binding.tvWizPowerValue.setTextColor(context.getColor(R.color.text_secondary))
            }
        } else {
            binding.tvWizPowerValue.text = context.getString(R.string.settings_wiz_status_disabled)
            binding.tvWizPowerValue.setTextColor(context.getColor(R.color.text_secondary))
        }

        binding.tvWizStreamingValue.text = when (preferences.streamingPreset) {
            "CINEMA_WARM" -> context.getString(R.string.settings_wiz_stream_cinema)
            "D65_NEUTRAL" -> context.getString(R.string.settings_wiz_stream_d65)
            "COZY_DIM" -> context.getString(R.string.settings_wiz_stream_cozy)
            "VIVID_BRIGHT" -> context.getString(R.string.settings_wiz_stream_vivid)
            "OFF" -> context.getString(R.string.settings_wiz_stream_off)
            else -> context.getString(R.string.settings_wiz_stream_circadian)
        }

        binding.tvWizGamingValue.text = when (preferences.gamingPreset) {
            "XBOX_GREEN" -> context.getString(R.string.settings_wiz_preset_xbox_green)
            "CYBERPUNK_PURPLE" -> context.getString(R.string.settings_wiz_preset_cyberpunk_purple)
            "D65_NEUTRAL" -> context.getString(R.string.settings_wiz_preset_d65_neutral)
            else -> context.getString(R.string.settings_wiz_preset_eye_first)
        }

        binding.tvWizBrightnessValue.text = when (preferences.brightnessScale) {
            100 -> context.getString(R.string.settings_wiz_bright_100)
            60 -> context.getString(R.string.settings_wiz_bright_60)
            40 -> context.getString(R.string.settings_wiz_bright_40)
            else -> context.getString(R.string.settings_wiz_bright_80)
        }

        binding.tvWizAppColorsValue.text = if (preferences.wizAppColorsEnabled)
            context.getString(R.string.settings_wiz_app_colors_enabled)
        else
            context.getString(R.string.settings_wiz_app_colors_disabled)

        binding.tvWizAutoPowerValue.text = if (preferences.wizAutoPowerEnabled)
            context.getString(R.string.settings_wiz_auto_power_enabled)
        else
            context.getString(R.string.settings_wiz_auto_power_disabled)
    }

    private fun setupListeners() {
        binding.btnColumns.setOnClickListener {
            if (binding.gridColumnsOptions.visibility == View.VISIBLE) {
                collapseColumns()
            } else {
                collapseSubscriptions()
                binding.gridColumnsOptions.visibility = View.VISIBLE
                binding.btnColumn4.requestFocus()
            }
        }

        mapOf(
            binding.btnColumn4 to 4,
            binding.btnColumn5 to 5,
            binding.btnColumn6 to 6,
            binding.btnColumn7 to 7
        ).forEach { (btn, columns) ->
            btn.setOnClickListener {
                preferences.gridColumns = columns
                updateUi()
                onSettingsChanged()
            }
        }

        binding.btnToggleHiddenApps.setOnClickListener {
            preferences.showHidden = !preferences.showHidden
            updateUi()
            onSettingsChanged()
        }

        binding.btnManageApps.setOnClickListener {
            dismiss()
            AppListDialog(context, apps, onManageApp).show()
        }

        binding.btnOptimizeMemory.setOnClickListener {
            CoroutineScope(Dispatchers.Main.immediate).launch {
                val stopped = withContext(Dispatchers.IO) {
                    MemoryOptimizer.optimize(context)
                }
                Toast.makeText(
                    context,
                    context.getString(R.string.optimize_done, stopped),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        binding.btnSubscriptions.setOnClickListener {
            if (binding.subscriptionsOptions.visibility == View.VISIBLE) {
                collapseSubscriptions()
            } else {
                collapseColumns()
                binding.subscriptionsOptions.visibility = View.VISIBLE
                binding.btnSubApple.requestFocus()
            }
        }

        mapOf(
            binding.btnSubApple to Providers.APPLE_PACKAGE,
            binding.btnSubPrime to Providers.PRIME_PACKAGE,
            binding.btnSubNetflix to Providers.NETFLIX_PACKAGE,
            binding.btnSubDisney to Providers.DISNEY_PACKAGE
        ).forEach { (btn, pkg) ->
            btn.setOnClickListener {
                val current = preferences.isSubscriptionActive(pkg)
                preferences.setSubscriptionActive(pkg, !current)
                updateUi()
            }
        }

        binding.btnRegion.setOnClickListener {
            val regions = REGIONS
            val index = regions.indexOf(preferences.searchRegion)
            preferences.searchRegion = regions[(index + 1) % regions.size]
            updateUi()
        }

        binding.btnTmdbKey.setOnClickListener {
            Toast.makeText(context, R.string.settings_tmdb_hint, Toast.LENGTH_SHORT).show()
        }

        binding.btnWizPower.setOnClickListener {
            val newValue = !preferences.wizSyncEnabled
            preferences.wizSyncEnabled = newValue
            if (newValue) {
                LauncherApp.ledController.turnOn()
            } else {
                LauncherApp.ledController.turnOff()
            }
            updateUi()
        }

        val streamingPresets = listOf("CIRCADIAN", "CINEMA_WARM", "D65_NEUTRAL", "COZY_DIM", "VIVID_BRIGHT", "OFF")
        binding.btnWizStreaming.setOnClickListener {
            val idx = streamingPresets.indexOf(preferences.streamingPreset)
            val nextPreset = streamingPresets[(idx + 1) % streamingPresets.size]
            preferences.streamingPreset = nextPreset
            updateUi()
            if (preferences.wizSyncEnabled) {
                LauncherApp.ledController.setStreamingMode()
            }
        }

        val gamingPresets = listOf("EYE_FIRST", "XBOX_GREEN", "CYBERPUNK_PURPLE", "D65_NEUTRAL")
        binding.btnWizGaming.setOnClickListener {
            val idx = gamingPresets.indexOf(preferences.gamingPreset)
            val nextPreset = gamingPresets[(idx + 1) % gamingPresets.size]
            preferences.gamingPreset = nextPreset
            updateUi()
            if (preferences.wizSyncEnabled) {
                LauncherApp.ledController.setGamingMode()
            }
        }

        val brightnessScales = listOf(80, 100, 40, 60)
        binding.btnWizBrightness.setOnClickListener {
            val idx = brightnessScales.indexOf(preferences.brightnessScale)
            val nextScale = brightnessScales[(idx + 1) % brightnessScales.size]
            preferences.brightnessScale = nextScale
            updateUi()
            if (preferences.wizSyncEnabled) {
                LauncherApp.ledController.setStreamingMode()
            }
        }

        binding.btnWizAppColors.setOnClickListener {
            preferences.wizAppColorsEnabled = !preferences.wizAppColorsEnabled
            updateUi()
        }

        binding.btnWizAutoPower.setOnClickListener {
            preferences.wizAutoPowerEnabled = !preferences.wizAutoPowerEnabled
            updateUi()
        }

        val allRows = listOf(
            binding.btnColumns, binding.btnColumn4, binding.btnColumn5, binding.btnColumn6, binding.btnColumn7,
            binding.btnManageApps, binding.btnOptimizeMemory, binding.btnToggleHiddenApps,
            binding.btnSubscriptions, binding.btnSubApple, binding.btnSubPrime, binding.btnSubNetflix, binding.btnSubDisney,
            binding.btnRegion, binding.btnTmdbKey,
            binding.btnWizPower, binding.btnWizStreaming, binding.btnWizGaming, binding.btnWizBrightness,
            binding.btnWizAppColors, binding.btnWizAutoPower
        )
        allRows.forEach { row ->
            row.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    view.playSoundEffect(SoundEffectConstants.CLICK)
                }
            }
        }
    }

    private fun collapseColumns() {
        binding.gridColumnsOptions.visibility = View.GONE
        binding.btnColumns.requestFocus()
    }

    private fun collapseSubscriptions() {
        binding.subscriptionsOptions.visibility = View.GONE
        binding.btnSubscriptions.requestFocus()
    }
}
