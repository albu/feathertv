package com.feathertv.launcher.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class AppPreferences(context: Context) {

    private val prefs: SharedPreferences = run {
        val newPrefs = context.getSharedPreferences("feathertv_prefs", Context.MODE_PRIVATE)
        val oldPrefs = context.getSharedPreferences("yb_launcher_prefs", Context.MODE_PRIVATE)
        if (newPrefs.all.isEmpty() && oldPrefs.all.isNotEmpty()) {
            val editor = newPrefs.edit()
            for ((key, value) in oldPrefs.all) {
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is String -> editor.putString(key, value)
                    is Set<*> -> @Suppress("UNCHECKED_CAST") editor.putStringSet(key, value as Set<String>)
                }
            }
            editor.apply()
        }
        newPrefs
    }

    companion object {
        private const val KEY_HIDDEN = "hidden_apps"
        private const val KEY_COLUMNS = "grid_columns"
        private const val KEY_SHOW_HIDDEN = "show_hidden_apps"
        private const val KEY_APP_ORDER = "app_order"
        private const val KEY_TILE_COLORS = "tile_colors"
        private const val KEY_SEARCH_REGION = "search_region"
        private const val KEY_ACTIVE_SUBSCRIPTIONS = "active_subscriptions"
        private const val KEY_WIZ_SYNC = "wiz_sync_enabled"
        private const val KEY_WIZ_AUTO_POWER = "wiz_auto_power_enabled"
        private const val KEY_WIZ_APP_COLORS = "wiz_app_colors_enabled"
        private const val KEY_WIZ_STREAMING_PRESET = "wiz_streaming_preset"
        private const val KEY_WIZ_GAMING_PRESET = "wiz_gaming_preset"
        private const val KEY_WIZ_BRIGHTNESS = "wiz_brightness_scale"
        private const val KEY_WIZ_IP = "wiz_target_ip"

        const val DEFAULT_COLUMNS = 5
        val DEFAULT_SEARCH_REGION: String get() = com.feathertv.launcher.BuildConfig.DEFAULT_SEARCH_REGION
        val DEFAULT_WIZ_IP: String get() = com.feathertv.launcher.BuildConfig.DEFAULT_WIZ_IP
        const val DEFAULT_STREAMING_PRESET = "CIRCADIAN"
        const val DEFAULT_GAMING_PRESET = "EYE_FIRST"
        const val DEFAULT_BRIGHTNESS_SCALE = 80
    }

    /**
     * Cached dominant tile color per package (used for gradient card tiles).
     * Colors are extracted once, off the main thread, and stored here so
     * launches stay instant and RAM stays flat.
     */
    fun getTileColor(packageName: String): Int? {
        val raw = prefs.getString(KEY_TILE_COLORS, null) ?: return null
        return try {
            val json = JSONObject(raw)
            if (json.has(packageName)) json.getInt(packageName) else null
        } catch (e: Exception) {
            null
        }
    }

    fun setTileColors(colors: Map<String, Int>) {
        if (colors.isEmpty()) return
        val raw = prefs.getString(KEY_TILE_COLORS, null)
        val json = try {
            raw?.let { JSONObject(it) } ?: JSONObject()
        } catch (e: Exception) {
            JSONObject()
        }
        colors.forEach { (pkg, color) -> json.put(pkg, color) }
        prefs.edit().putString(KEY_TILE_COLORS, json.toString()).apply()
    }

    fun getHiddenApps(): Set<String> {
        return prefs.getStringSet(KEY_HIDDEN, emptySet()) ?: emptySet()
    }

    fun setHidden(packageName: String, hidden: Boolean) {
        val set = getHiddenApps().toMutableSet()
        if (hidden) {
            set.add(packageName)
        } else {
            set.remove(packageName)
        }
        prefs.edit().putStringSet(KEY_HIDDEN, set).apply()
    }

    fun isHidden(packageName: String): Boolean {
        return getHiddenApps().contains(packageName)
    }

    var gridColumns: Int
        get() = prefs.getInt(KEY_COLUMNS, DEFAULT_COLUMNS)
        set(value) = prefs.edit().putInt(KEY_COLUMNS, value).apply()

    var showHidden: Boolean
        get() = prefs.getBoolean(KEY_SHOW_HIDDEN, false)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_HIDDEN, value).apply()

    /** User-defined app order (package names, most significant first). */
    fun getAppOrder(): List<String> {
        val raw = prefs.getString(KEY_APP_ORDER, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun setAppOrder(order: List<String>) {
        val arr = JSONArray()
        order.forEach { arr.put(it) }
        prefs.edit().putString(KEY_APP_ORDER, arr.toString()).apply()
    }

    /** ISO country code used for TMDB watch/providers lookups. */
    var searchRegion: String
        get() = prefs.getString(KEY_SEARCH_REGION, DEFAULT_SEARCH_REGION)
            ?: DEFAULT_SEARCH_REGION
        set(value) = prefs.edit()
            .putString(KEY_SEARCH_REGION, value.uppercase(Locale.US))
            .apply()

    /**
     * Active subscription packages for TMDB search filtering.
     * Defaults to all supported packages.
     */
    fun getActiveSubscriptions(): Set<String> {
        val defaultSet = Providers.PREFERENCE_ORDER.toSet()
        return prefs.getStringSet(KEY_ACTIVE_SUBSCRIPTIONS, defaultSet) ?: defaultSet
    }

    fun isSubscriptionActive(packageName: String): Boolean {
        return getActiveSubscriptions().contains(packageName)
    }

    fun setSubscriptionActive(packageName: String, active: Boolean) {
        val set = getActiveSubscriptions().toMutableSet()
        if (active) {
            set.add(packageName)
        } else {
            set.remove(packageName)
        }
        prefs.edit().putStringSet(KEY_ACTIVE_SUBSCRIPTIONS, set).apply()
    }

    var wizSyncEnabled: Boolean
        get() = prefs.getBoolean(KEY_WIZ_SYNC, true)
        set(value) = prefs.edit().putBoolean(KEY_WIZ_SYNC, value).apply()

    var wizAutoPowerEnabled: Boolean
        get() = prefs.getBoolean(KEY_WIZ_AUTO_POWER, true)
        set(value) = prefs.edit().putBoolean(KEY_WIZ_AUTO_POWER, value).apply()

    var wizAppColorsEnabled: Boolean
        get() = prefs.getBoolean(KEY_WIZ_APP_COLORS, true)
        set(value) = prefs.edit().putBoolean(KEY_WIZ_APP_COLORS, value).apply()

    var streamingPreset: String
        get() = prefs.getString(KEY_WIZ_STREAMING_PRESET, DEFAULT_STREAMING_PRESET) ?: DEFAULT_STREAMING_PRESET
        set(value) = prefs.edit().putString(KEY_WIZ_STREAMING_PRESET, value).apply()

    var gamingPreset: String
        get() = prefs.getString(KEY_WIZ_GAMING_PRESET, DEFAULT_GAMING_PRESET) ?: DEFAULT_GAMING_PRESET
        set(value) = prefs.edit().putString(KEY_WIZ_GAMING_PRESET, value).apply()

    var brightnessScale: Int
        get() = prefs.getInt(KEY_WIZ_BRIGHTNESS, DEFAULT_BRIGHTNESS_SCALE)
        set(value) = prefs.edit().putInt(KEY_WIZ_BRIGHTNESS, value).apply()

    var wizTargetIp: String
        get() = prefs.getString(KEY_WIZ_IP, DEFAULT_WIZ_IP) ?: DEFAULT_WIZ_IP
        set(value) = prefs.edit().putString(KEY_WIZ_IP, value).apply()
}
