package com.webstrike.aimbotpro.config

import android.content.SharedPreferences

/**
 * Thin wrapper around SharedPreferences for typed access.
 * Singleton — initialized once in [com.webstrike.aimbotpro.App.onCreate].
 */
class SettingsManager private constructor(
    private val prefs: SharedPreferences
) {

    fun getBool(key: String, default: Boolean): Boolean =
        prefs.getBoolean(key, default)

    fun setBool(key: String, value: Boolean) =
        prefs.edit().putBoolean(key, value).apply()

    fun getFloat(key: String, default: Float): Float =
        prefs.getFloat(key, default)

    fun setFloat(key: String, value: Float) =
        prefs.edit().putFloat(key, value).apply()

    fun getLong(key: String, default: Long): Long =
        prefs.getLong(key, default)

    fun setLong(key: String, value: Long) =
        prefs.edit().putLong(key, value).apply()

    fun getString(key: String, default: String): String =
        prefs.getString(key, default) ?: default

    fun setString(key: String, value: String) =
        prefs.edit().putString(key, value).apply()

    fun getInt(key: String, default: Int): Int =
        prefs.getInt(key, default)

    fun setInt(key: String, value: Int) =
        prefs.edit().putInt(key, value).apply()

    /** True iff [key] is present in the prefs (regardless of value). */
    fun contains(key: String): Boolean = prefs.contains(key)

    /** Remove a single key (no-op if absent). */
    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    /** Snapshot all keys present in the prefs. Used for prefix-based deletion. */
    fun allKeys(): Set<String> = prefs.all.keys

    companion object {
        const val PREFS_NAME = "aimbot_prefs"

        @Volatile
        private var instance: SettingsManager? = null

        fun init(prefs: SharedPreferences) {
            if (instance == null) {
                synchronized(this) {
                    if (instance == null) instance = SettingsManager(prefs)
                }
            }
        }

        fun get(): SettingsManager =
            instance ?: throw IllegalStateException("SettingsManager not initialized")
    }
}
