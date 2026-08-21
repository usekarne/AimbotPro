package com.webstrike.aimbotpro.config

import com.webstrike.aimbotpro.Constants
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Singleton holding all toggle-able feature flags + tunable params.
 *
 * Backed by SettingsManager for persistence. All updates notify listeners
 * (the mod menu, the engine, the overlay, the services).
 */
object FeatureFlags {

    // ---- Toggleable features ----
    @Volatile var aimbotEnabled: Boolean = true
    @Volatile var triggerBotEnabled: Boolean = true
    @Volatile var recoilControlEnabled: Boolean = true
    @Volatile var aimSmoothEnabled: Boolean = false
    @Volatile var silentAimEnabled: Boolean = false
    @Volatile var headshotModeEnabled: Boolean = true
    @Volatile var scrollEnabled: Boolean = true

    @Volatile var espBoxesEnabled: Boolean = true
    @Volatile var espLinesEnabled: Boolean = false
    @Volatile var espDistanceEnabled: Boolean = true
    @Volatile var espNamesEnabled: Boolean = false
    @Volatile var fovCircleEnabled: Boolean = true
    @Volatile var crosshairEnabled: Boolean = true

    // ---- Tunable sliders ----
    @Volatile var aimSpeed: Float = Constants.Aim.DEFAULT_AIM_SPEED
    @Volatile var aimFov: Float = Constants.Aim.DEFAULT_FOV_RADIUS_DP
    @Volatile var aimSmoothness: Float = Constants.Aim.DEFAULT_SMOOTHNESS
    @Volatile var triggerDelayMs: Long = Constants.Aim.DEFAULT_TRIGGER_DELAY_MS
    @Volatile var minConfidence: Float = Constants.Detection.DEFAULT_CONF_THRESHOLD
    @Volatile var scrollAmount: Float = 200f

    // ---- Runtime state (non-persisted) ----
    @Volatile var serviceRunning: Boolean = false
    @Volatile var demoMode: Boolean = false

    private val listeners = CopyOnWriteArrayList<(String, Any) -> Unit>()

    fun init(settings: SettingsManager) {
        aimbotEnabled = settings.getBool(Keys.AIMBOT, true)
        triggerBotEnabled = settings.getBool(Keys.TRIGGER, true)
        recoilControlEnabled = settings.getBool(Keys.RECOIL, true)
        aimSmoothEnabled = settings.getBool(Keys.SMOOTH, false)
        silentAimEnabled = settings.getBool(Keys.SILENT, false)
        headshotModeEnabled = settings.getBool(Keys.HEADSHOT, true)
        scrollEnabled = settings.getBool(Keys.SCROLL, true)

        espBoxesEnabled = settings.getBool(Keys.ESP_BOXES, true)
        espLinesEnabled = settings.getBool(Keys.ESP_LINES, false)
        espDistanceEnabled = settings.getBool(Keys.ESP_DIST, true)
        espNamesEnabled = settings.getBool(Keys.ESP_NAMES, false)
        fovCircleEnabled = settings.getBool(Keys.FOV_CIRCLE, true)
        crosshairEnabled = settings.getBool(Keys.CROSSHAIR, true)

        aimSpeed = settings.getFloat(Keys.AIM_SPEED, Constants.Aim.DEFAULT_AIM_SPEED)
        aimFov = settings.getFloat(Keys.AIM_FOV, Constants.Aim.DEFAULT_FOV_RADIUS_DP)
        aimSmoothness = settings.getFloat(Keys.AIM_SMOOTH, Constants.Aim.DEFAULT_SMOOTHNESS)
        triggerDelayMs = settings.getLong(Keys.TRIGGER_DELAY, Constants.Aim.DEFAULT_TRIGGER_DELAY_MS)
        minConfidence = settings.getFloat(Keys.MIN_CONF, Constants.Detection.DEFAULT_CONF_THRESHOLD)
    }

    fun setBool(key: String, value: Boolean) {
        // Update the @Volatile field BEFORE persisting — a concurrent reader
        // would otherwise observe stale flag value while prefs already
        // reflect the new value.
        when (key) {
            Keys.AIMBOT -> aimbotEnabled = value
            Keys.TRIGGER -> triggerBotEnabled = value
            Keys.RECOIL -> recoilControlEnabled = value
            Keys.SMOOTH -> aimSmoothEnabled = value
            Keys.SILENT -> silentAimEnabled = value
            Keys.HEADSHOT -> headshotModeEnabled = value
            Keys.SCROLL -> scrollEnabled = value
            Keys.ESP_BOXES -> espBoxesEnabled = value
            Keys.ESP_LINES -> espLinesEnabled = value
            Keys.ESP_DIST -> espDistanceEnabled = value
            Keys.ESP_NAMES -> espNamesEnabled = value
            Keys.FOV_CIRCLE -> fovCircleEnabled = value
            Keys.CROSSHAIR -> crosshairEnabled = value
        }
        SettingsManager.get().setBool(key, value)
        notify(key, value)
    }

    fun setFloat(key: String, value: Float) {
        when (key) {
            Keys.AIM_SPEED -> aimSpeed = value
            Keys.AIM_FOV -> aimFov = value
            Keys.AIM_SMOOTH -> aimSmoothness = value
            Keys.MIN_CONF -> minConfidence = value
            Keys.SCROLL_AMOUNT -> scrollAmount = value
        }
        SettingsManager.get().setFloat(key, value)
        notify(key, value)
    }

    fun setLong(key: String, value: Long) {
        when (key) {
            Keys.TRIGGER_DELAY -> triggerDelayMs = value
        }
        SettingsManager.get().setLong(key, value)
        notify(key, value)
    }

    fun addListener(listener: (key: String, value: Any) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (key: String, value: Any) -> Unit) {
        listeners -= listener
    }

    private fun notify(key: String, value: Any) {
        listeners.forEach {
            runCatching { it(key, value) }
                .onFailure { e ->
                    com.webstrike.aimbotpro.utils.Logger.w(
                        "FeatureFlags",
                        "Listener for key='$key' threw: ${e.message}",
                        e
                    )
                }
        }
    }

    object Keys {
        // toggles
        const val AIMBOT = "feat.aimbot"
        const val TRIGGER = "feat.trigger"
        const val RECOIL = "feat.recoil"
        const val SMOOTH = "feat.smooth"
        const val SILENT = "feat.silent"
        const val HEADSHOT = "feat.headshot"
        const val SCROLL = "feat.scroll"
        const val ESP_BOXES = "feat.esp.boxes"
        const val ESP_LINES = "feat.esp.lines"
        const val ESP_DIST = "feat.esp.dist"
        const val ESP_NAMES = "feat.esp.names"
        const val FOV_CIRCLE = "feat.fov.circle"
        const val CROSSHAIR = "feat.crosshair"
        // sliders
        const val AIM_SPEED = "slider.aim.speed"
        const val AIM_FOV = "slider.aim.fov"
        const val AIM_SMOOTH = "slider.aim.smooth"
        const val TRIGGER_DELAY = "slider.trigger.delay"
        const val MIN_CONF = "slider.confidence"
        const val SCROLL_AMOUNT = "slider.scroll.amount"

        // ---- Validation set (used by CoreAimbotService to reject bogus TOGGLE_FEATURE intents) ----
        /** All known boolean toggle keys. */
        val BOOL_KEYS: Set<String> = setOf(
            AIMBOT, TRIGGER, RECOIL, SMOOTH, SILENT, HEADSHOT, SCROLL,
            ESP_BOXES, ESP_LINES, ESP_DIST, ESP_NAMES, FOV_CIRCLE, CROSSHAIR
        )
        /** All known float slider keys. */
        val FLOAT_KEYS: Set<String> = setOf(
            AIM_SPEED, AIM_FOV, AIM_SMOOTH, MIN_CONF, SCROLL_AMOUNT
        )
        /** All known long keys. */
        val LONG_KEYS: Set<String> = setOf(
            TRIGGER_DELAY
        )
    }

    /** Returns `true` iff [key] is a recognised FeatureFlags key. */
    fun isValidKey(key: String): Boolean =
        key in Keys.BOOL_KEYS || key in Keys.FLOAT_KEYS || key in Keys.LONG_KEYS
}
