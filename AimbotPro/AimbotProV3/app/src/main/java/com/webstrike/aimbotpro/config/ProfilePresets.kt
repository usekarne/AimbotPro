package com.webstrike.aimbotpro.config

import com.webstrike.aimbotpro.Constants
import com.webstrike.aimbotpro.utils.Logger

/**
 * Saved preset profiles — let the user load named configurations for
 * different games / playstyles (e.g. "Sniper Slow", "Aggressive Pistol",
 * "Defensive Tracking").
 *
 * Persists to SharedPreferences via [SettingsManager] under the
 * `preset.<name>.<key>` namespace. The "default" preset is always
 * available and reflects the live state of [FeatureFlags] at the time
 * [savePreset] was first called.
 *
 * Thread-safe — all operations are synchronized on the singleton instance.
 */
object ProfilePresets {

    private const val TAG = "ProfilePresets"
    private const val PRESET_PREFIX = "preset."
    private const val ACTIVE_PRESET_KEY = "preset.active"
    private const val DEFAULT_PRESET_NAME = "default"

    /** Built-in default presets shipped with the app. */
    val builtinPresets: List<String> = listOf(
        DEFAULT_PRESET_NAME,
        "sniper",
        "pistol",
        "rifle",
        "tracking"
    )

    /**
     * Save the current [FeatureFlags] state as a named preset.
     * Overwrites any existing preset with the same name.
     */
    fun savePreset(name: String) {
        if (name.isBlank()) {
            Logger.w(TAG, "savePreset: name is blank; ignoring")
            return
        }
        val settings = SettingsManager.get()
        synchronized(this) {
            // Snapshot all current flag values to the preset namespace.
            putPresetBool(settings, name, FeatureFlags.Keys.AIMBOT, FeatureFlags.aimbotEnabled)
            putPresetBool(settings, name, FeatureFlags.Keys.TRIGGER, FeatureFlags.triggerBotEnabled)
            putPresetBool(settings, name, FeatureFlags.Keys.RECOIL, FeatureFlags.recoilControlEnabled)
            putPresetBool(settings, name, FeatureFlags.Keys.SMOOTH, FeatureFlags.aimSmoothEnabled)
            putPresetBool(settings, name, FeatureFlags.Keys.SILENT, FeatureFlags.silentAimEnabled)
            putPresetBool(settings, name, FeatureFlags.Keys.HEADSHOT, FeatureFlags.headshotModeEnabled)
            putPresetBool(settings, name, FeatureFlags.Keys.ESP_BOXES, FeatureFlags.espBoxesEnabled)
            putPresetBool(settings, name, FeatureFlags.Keys.ESP_LINES, FeatureFlags.espLinesEnabled)
            putPresetBool(settings, name, FeatureFlags.Keys.ESP_DIST, FeatureFlags.espDistanceEnabled)
            putPresetBool(settings, name, FeatureFlags.Keys.ESP_NAMES, FeatureFlags.espNamesEnabled)
            putPresetBool(settings, name, FeatureFlags.Keys.FOV_CIRCLE, FeatureFlags.fovCircleEnabled)
            putPresetBool(settings, name, FeatureFlags.Keys.CROSSHAIR, FeatureFlags.crosshairEnabled)
            putPresetFloat(settings, name, FeatureFlags.Keys.AIM_SPEED, FeatureFlags.aimSpeed)
            putPresetFloat(settings, name, FeatureFlags.Keys.AIM_FOV, FeatureFlags.aimFov)
            putPresetFloat(settings, name, FeatureFlags.Keys.AIM_SMOOTH, FeatureFlags.aimSmoothness)
            putPresetLong(settings, name, FeatureFlags.Keys.TRIGGER_DELAY, FeatureFlags.triggerDelayMs)
            putPresetFloat(settings, name, FeatureFlags.Keys.MIN_CONF, FeatureFlags.minConfidence)
            settings.setString(ACTIVE_PRESET_KEY, name)
        }
        Logger.i(TAG, "Preset '$name' saved")
    }

    /**
     * Load a named preset and apply it to [FeatureFlags]. If the preset
     * does not exist, no-op (logged).
     */
    fun loadPreset(name: String) {
        if (name.isBlank()) {
            Logger.w(TAG, "loadPreset: name is blank; ignoring")
            return
        }
        val settings = SettingsManager.get()
        synchronized(this) {
            if (!presetExists(name, settings)) {
                Logger.w(TAG, "loadPreset: preset '$name' not found")
                return
            }
            FeatureFlags.setBool(FeatureFlags.Keys.AIMBOT, getPresetBool(settings, name, FeatureFlags.Keys.AIMBOT, true))
            FeatureFlags.setBool(FeatureFlags.Keys.TRIGGER, getPresetBool(settings, name, FeatureFlags.Keys.TRIGGER, false))
            FeatureFlags.setBool(FeatureFlags.Keys.RECOIL, getPresetBool(settings, name, FeatureFlags.Keys.RECOIL, false))
            FeatureFlags.setBool(FeatureFlags.Keys.SMOOTH, getPresetBool(settings, name, FeatureFlags.Keys.SMOOTH, true))
            FeatureFlags.setBool(FeatureFlags.Keys.SILENT, getPresetBool(settings, name, FeatureFlags.Keys.SILENT, false))
            FeatureFlags.setBool(FeatureFlags.Keys.HEADSHOT, getPresetBool(settings, name, FeatureFlags.Keys.HEADSHOT, false))
            FeatureFlags.setBool(FeatureFlags.Keys.ESP_BOXES, getPresetBool(settings, name, FeatureFlags.Keys.ESP_BOXES, true))
            FeatureFlags.setBool(FeatureFlags.Keys.ESP_LINES, getPresetBool(settings, name, FeatureFlags.Keys.ESP_LINES, false))
            FeatureFlags.setBool(FeatureFlags.Keys.ESP_DIST, getPresetBool(settings, name, FeatureFlags.Keys.ESP_DIST, true))
            FeatureFlags.setBool(FeatureFlags.Keys.ESP_NAMES, getPresetBool(settings, name, FeatureFlags.Keys.ESP_NAMES, false))
            FeatureFlags.setBool(FeatureFlags.Keys.FOV_CIRCLE, getPresetBool(settings, name, FeatureFlags.Keys.FOV_CIRCLE, true))
            FeatureFlags.setBool(FeatureFlags.Keys.CROSSHAIR, getPresetBool(settings, name, FeatureFlags.Keys.CROSSHAIR, true))
            FeatureFlags.setFloat(FeatureFlags.Keys.AIM_SPEED, getPresetFloat(settings, name, FeatureFlags.Keys.AIM_SPEED, Constants.Aim.DEFAULT_AIM_SPEED))
            FeatureFlags.setFloat(FeatureFlags.Keys.AIM_FOV, getPresetFloat(settings, name, FeatureFlags.Keys.AIM_FOV, Constants.Aim.DEFAULT_FOV_RADIUS_DP))
            FeatureFlags.setFloat(FeatureFlags.Keys.AIM_SMOOTH, getPresetFloat(settings, name, FeatureFlags.Keys.AIM_SMOOTH, Constants.Aim.DEFAULT_SMOOTHNESS))
            FeatureFlags.setLong(FeatureFlags.Keys.TRIGGER_DELAY, getPresetLong(settings, name, FeatureFlags.Keys.TRIGGER_DELAY, Constants.Aim.DEFAULT_TRIGGER_DELAY_MS))
            FeatureFlags.setFloat(FeatureFlags.Keys.MIN_CONF, getPresetFloat(settings, name, FeatureFlags.Keys.MIN_CONF, Constants.Detection.DEFAULT_CONF_THRESHOLD))
            settings.setString(ACTIVE_PRESET_KEY, name)
        }
        Logger.i(TAG, "Preset '$name' loaded")
    }

    /**
     * Delete a named preset. Cannot delete the `default` preset (it's
     * the fallback for "no preset").
     *
     * NOTE: SharedPreferences doesn't support prefix-based deletion, so we
     * enumerate `prefs.all` and remove matching keys explicitly.
     */
    fun deletePreset(name: String) {
        if (name == DEFAULT_PRESET_NAME) {
            Logger.w(TAG, "Cannot delete the default preset")
            return
        }
        val settings = SettingsManager.get()
        synchronized(this) {
            val prefix = "${PRESET_PREFIX}${name}."
            val activePreset = activePreset()
            // Drop every key in our preset's namespace. settings.allKeys()
            // returns a snapshot so we iterate then call remove for each.
            for (key in settings.allKeys()) {
                if (key.startsWith(prefix)) {
                    settings.remove(key)
                }
            }
            // If the active preset was the one we just deleted, fall back
            // to "default" so the next loadPreset doesn't fail silently.
            if (activePreset == name) {
                settings.setString(ACTIVE_PRESET_KEY, DEFAULT_PRESET_NAME)
            }
        }
        Logger.i(TAG, "Preset '$name' deleted")
    }

    /** The name of the currently-active preset, or "default" if none. */
    fun activePreset(): String =
        SettingsManager.get().getString(ACTIVE_PRESET_KEY, DEFAULT_PRESET_NAME)

    /** True iff a saved preset with the given [name] exists. */
    fun presetExists(name: String): Boolean =
        presetExists(name, SettingsManager.get())

    private fun presetExists(name: String, settings: SettingsManager): Boolean {
        // A preset exists iff at least one of its keys is present.
        return settings.contains("${PRESET_PREFIX}${name}.${FeatureFlags.Keys.AIMBOT}")
    }

    // ---------- Per-key helpers (scoped to the preset's namespace) ----------

    private fun presetKey(name: String, key: String) = "${PRESET_PREFIX}${name}.${key}"

    private fun putPresetBool(s: SettingsManager, name: String, key: String, v: Boolean) =
        s.setBool(presetKey(name, key), v)

    private fun putPresetFloat(s: SettingsManager, name: String, key: String, v: Float) =
        s.setFloat(presetKey(name, key), v)

    private fun putPresetLong(s: SettingsManager, name: String, key: String, v: Long) =
        s.setLong(presetKey(name, key), v)

    private fun getPresetBool(s: SettingsManager, name: String, key: String, default: Boolean): Boolean =
        s.getBool(presetKey(name, key), default)

    private fun getPresetFloat(s: SettingsManager, name: String, key: String, default: Float): Float =
        s.getFloat(presetKey(name, key), default)

    private fun getPresetLong(s: SettingsManager, name: String, key: String, default: Long): Long =
        s.getLong(presetKey(name, key), default)
}
