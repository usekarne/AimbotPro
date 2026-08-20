package com.webstrike.aimbotpro.utils

import android.util.Log
import com.webstrike.aimbotpro.BuildConfig
import timber.log.Timber

/**
 * Centralized logging facade.
 *
 * - Debug builds: plants a [Timber.DebugTree] (all levels to logcat).
 * - Release builds: routes **WARN + ERROR** directly to [android.util.Log]
 *   via [ReleaseTree]. VERBOSE / DEBUG / INFO are suppressed.
 *
 * ## v4.1 hardening
 * The release tree now writes directly to `android.util.Log` without
 * going through Timber's internal dispatch. This ensures that even if
 * R8 optimizes away the Timber tree lookup, warn/error messages still
 * reach logcat — critical for diagnosing production issues.
 */
object Logger {

    @Volatile private var initialized = false
    @Volatile private var verbose = false

    fun init(debugMode: Boolean) {
        if (initialized) {
            verbose = debugMode
            return
        }
        verbose = debugMode
        initialized = true
        if (Timber.treeCount == 0) {
            if (debugMode) {
                Timber.plant(Timber.DebugTree())
            } else {
                Timber.plant(ReleaseTree())
            }
        }
        // Use Log.w directly so this message is always visible (even if
        // Timber tree gets optimized away in release).
        Log.w(TAG, "Logger ready (verbose=$verbose, debug=$debugMode)")
    }

    fun v(tag: String, msg: String) {
        if (verbose) log(LogPriority.VERBOSE, tag, msg, null)
    }

    fun d(tag: String, msg: String) {
        if (verbose) log(LogPriority.DEBUG, tag, msg, null)
    }

    fun i(tag: String, msg: String) {
        log(LogPriority.INFO, tag, msg, null)
    }

    fun w(tag: String, msg: String, t: Throwable? = null) {
        log(LogPriority.WARN, tag, msg, t)
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        log(LogPriority.ERROR, tag, msg, t)
    }

    private fun log(priority: LogPriority, tag: String, msg: String, t: Throwable?) {
        if (!initialized) return
        when (priority) {
            LogPriority.VERBOSE -> if (t != null) Timber.tag(tag).v(t, msg) else Timber.tag(tag).v(msg)
            LogPriority.DEBUG   -> if (t != null) Timber.tag(tag).d(t, msg) else Timber.tag(tag).d(msg)
            LogPriority.INFO    -> if (t != null) Timber.tag(tag).i(t, msg) else Timber.tag(tag).i(msg)
            // WARN and ERROR go directly to android.util.Log to bypass
            // any potential R8 optimization of the Timber dispatch path.
            LogPriority.WARN    -> {
                if (t != null) Log.w(tag, msg, t) else Log.w(tag, msg)
            }
            LogPriority.ERROR   -> {
                if (t != null) Log.e(tag, msg, t) else Log.e(tag, msg)
            }
        }
    }

    private enum class LogPriority { VERBOSE, DEBUG, INFO, WARN, ERROR }

    /**
     * Release-build Timber tree. Routes WARN + ERROR to [android.util.Log].
     * VERBOSE / DEBUG / INFO are suppressed.
     *
     * NOTE: In v4.1, Logger.log() bypasses this tree for WARN/ERROR and
     * calls `android.util.Log` directly. This tree is kept as a safety
     * net for any code that calls Timber directly.
     */
    private class ReleaseTree : Timber.Tree() {
        override fun isLoggable(tag: String?, priority: Int): Boolean =
            priority >= Log.WARN

        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            when (priority) {
                Log.WARN -> if (t != null) Log.w(tag, message, t) else Log.w(tag, message)
                Log.ERROR -> if (t != null) Log.e(tag, message, t) else Log.e(tag, message)
                Log.ASSERT -> Log.e(tag, message, t)
            }
        }
    }

    private const val TAG = "AimbotPro"
}
