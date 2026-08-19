package com.webstrike.aimbotpro.utils

import android.util.Log
import com.webstrike.aimbotpro.BuildConfig
import timber.log.Timber

/**
 * Centralized logging facade.
 *
 * - Debug builds: plants a [Timber.DebugTree] (tagged, pretty-printed stack
 *   traces, automatic logcat integration). All priority levels emitted.
 * - Release builds: plants a [ReleaseTree] that routes WARN + ERROR to
 *   [android.util.Log] (and suppresses VERBOSE / DEBUG / INFO). This
 *   guarantees [Logger.w] / [Logger.e] are NEVER silent in production — a
 *   v3 bug where release builds had no tree meant all error logging was
 *   silently dropped.
 *
 * All public methods are thread-safe (Timber itself is). No reflection.
 */
object Logger {

    @Volatile private var initialized = false
    @Volatile private var verbose = false

    fun init(debugMode: Boolean) {
        if (initialized) {
            // Re-init is a no-op except for the verbose flag (allow runtime
            // overrides via debugger if needed).
            verbose = debugMode
            return
        }
        verbose = debugMode
        initialized = true
        // Plant only once per process. Timber.treeCount is the public API.
        if (Timber.treeCount == 0) {
            if (debugMode) {
                Timber.plant(Timber.DebugTree())
            } else {
                Timber.plant(ReleaseTree())
            }
        }
        i(TAG, "Logger ready (verbose=$verbose, debug=$debugMode)")
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
        // Always go through Timber — in release builds, the ReleaseTree
        // routes WARN+ to android.util.Log; in debug, the DebugTree routes
        // everything to logcat with caller class + line numbers.
        val tagged = Timber.tag(tag)
        when (priority) {
            LogPriority.VERBOSE -> if (t != null) tagged.v(t, msg) else tagged.v(msg)
            LogPriority.DEBUG   -> if (t != null) tagged.d(t, msg) else tagged.d(msg)
            LogPriority.INFO    -> if (t != null) tagged.i(t, msg) else tagged.i(msg)
            LogPriority.WARN    -> if (t != null) tagged.w(t, msg) else tagged.w(msg)
            LogPriority.ERROR   -> if (t != null) tagged.e(t, msg) else tagged.e(msg)
        }
    }

    private enum class LogPriority { VERBOSE, DEBUG, INFO, WARN, ERROR }

    /**
     * Release-build Timber tree. Routes WARN + ERROR to [android.util.Log]
     * so production crash logs are visible in `adb logcat *:W`. VERBOSE /
     * DEBUG / INFO are suppressed to keep logcat clean and avoid leaking
     * debugging context in release builds.
     *
     * Tag is set via [Timber.tag] before each call — we honour the caller's
     * tag instead of using Timber's auto-tagging (which inspects the call
     * stack and is unavailable in release builds due to R8 obfuscation).
     */
    private class ReleaseTree : Timber.Tree() {
        override fun isLoggable(tag: String?, priority: Int): Boolean =
            priority >= Log.WARN

        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            when (priority) {
                Log.WARN -> if (t != null) Log.w(tag, message, t) else Log.w(tag, message)
                Log.ERROR -> if (t != null) Log.e(tag, message, t) else Log.e(tag, message)
                Log.ASSERT -> Log.e(tag, message, t)
                // VERBOSE / DEBUG / INFO are suppressed — keep logcat clean.
            }
        }
    }

    private const val TAG = "AimbotPro"
}
