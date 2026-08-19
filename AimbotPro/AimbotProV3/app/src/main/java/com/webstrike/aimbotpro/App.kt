package com.webstrike.aimbotpro

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.webstrike.aimbotpro.config.FeatureFlags
import com.webstrike.aimbotpro.config.SettingsManager
import com.webstrike.aimbotpro.utils.Logger

/**
 * Application entry point — initializes singletons (settings, feature flags, logger).
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Logger first so everything else can log
        Logger.init(BuildConfig.DEBUG_MODE)

        // Persistent settings + feature flags
        val prefs: SharedPreferences =
            getSharedPreferences(SettingsManager.PREFS_NAME, Context.MODE_PRIVATE)
        SettingsManager.init(prefs)
        FeatureFlags.init(SettingsManager.get())

        Logger.i(TAG, "AimbotPro v${BuildConfig.VERSION_NAME} initialized")
        Logger.i(TAG, "Package: ${BuildConfig.APPLICATION_ID}")
        Logger.i(TAG, "Debug build: ${BuildConfig.DEBUG_MODE}")
    }

    companion object {
        private const val TAG = "App"

        @Volatile
        private var instance: App? = null

        fun get(): App =
            instance ?: throw IllegalStateException("App not yet created")
    }
}
