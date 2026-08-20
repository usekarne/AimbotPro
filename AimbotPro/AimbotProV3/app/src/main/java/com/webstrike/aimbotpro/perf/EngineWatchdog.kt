package com.webstrike.aimbotpro.perf

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.webstrike.aimbotpro.utils.Logger
import java.util.concurrent.atomic.AtomicLong

/**
 * Watchdog that monitors the [com.webstrike.aimbotpro.core.Engine] inference
 * loop for liveness. If no frame is processed within [heartbeatTimeoutMs],
 * the registered [onStall] callback is invoked so the host service can
 * attempt recovery (e.g. restart the engine, broadcast a status update).
 *
 * ## Why
 *
 * The Engine's loop swallows all [Exception]s to honour the "don't crash
 * the game" robustness contract. This means a chronic TFLite / overlay /
 * accessibility failure could leave the loop spinning without ever producing
 * a useful frame — the user sees a static overlay with zero FPS but no error
 * state. The watchdog surfaces that condition explicitly.
 *
 * ## Threading
 *
 * Runs on its own [HandlerThread] (`"engine-watchdog"`) so it cannot be
 * starved by the inference loop it monitors.
 *
 * ## Lifecycle
 *
 * Constructed once per service session. Call [start] after Engine.start
 * completes; call [stop] before Engine.stop. Idempotent.
 *
 * @param heartbeatTimeoutMs max wall-clock time allowed between frames
 *                            before [onStall] is invoked. Default 5 seconds
 *                            (a 60 FPS loop normally heartbeats every ~17 ms,
 *                            so 5 s indicates a chronic stall).
 * @param checkIntervalMs    how often the watchdog polls. Default 1 second.
 * @param onStall            invoked on the watchdog thread when a stall is
 *                            detected. Receives the elapsed ms since the
 *                            last successful frame.
 */
class EngineWatchdog(
    private val heartbeatTimeoutMs: Long = DEFAULT_HEARTBEAT_TIMEOUT_MS,
    private val checkIntervalMs: Long = DEFAULT_CHECK_INTERVAL_MS,
    private val onStall: (elapsedMs: Long) -> Unit
) {

    private val lastHeartbeatNs = AtomicLong(0L)

    @Volatile
    private var handler: Handler? = null
    @Volatile
    private var thread: HandlerThread? = null
    @Volatile
    private var running: Boolean = false

    private val checkRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            val lastNs = lastHeartbeatNs.get()
            if (lastNs > 0L) {
                val elapsedMs = (SystemClock.elapsedRealtimeNanos() - lastNs) / 1_000_000L
                if (elapsedMs > heartbeatTimeoutMs) {
                    Logger.w(TAG, "Engine stall detected: ${elapsedMs}ms since last heartbeat")
                    Telemetry.event(Telemetry.Engine.FRAME_FAILED, "stall ${elapsedMs}ms")
                    runCatching { onStall(elapsedMs) }
                        .onFailure { Logger.w(TAG, "onStall threw: ${it.message}", it) }
                    // Reset the heartbeat so we don't fire onStall every check
                    // interval — the next successful frame will refresh it.
                    lastHeartbeatNs.set(SystemClock.elapsedRealtimeNanos())
                }
            }
            handler?.postDelayed(this, checkIntervalMs)
        }
    }

    /**
     * Start the watchdog. Idempotent.
     */
    fun start() {
        if (running) return
        val t = HandlerThread("engine-watchdog").also { it.start() }
        thread = t
        handler = Handler(t.looper)
        running = true
        lastHeartbeatNs.set(SystemClock.elapsedRealtimeNanos())
        handler?.postDelayed(checkRunnable, checkIntervalMs)
        Logger.i(TAG, "Watchdog started (timeout=${heartbeatTimeoutMs}ms)")
    }

    /**
     * Stop the watchdog. Idempotent.
     */
    fun stop() {
        if (!running) return
        running = false
        handler?.removeCallbacks(checkRunnable)
        thread?.quitSafely()
        thread = null
        handler = null
        lastHeartbeatNs.set(0L)
        Logger.i(TAG, "Watchdog stopped")
    }

    /**
     * Called by the Engine on every successful frame to refresh the heartbeat.
     * Cheap (single atomic write).
     */
    fun heartbeat() {
        lastHeartbeatNs.set(SystemClock.elapsedRealtimeNanos())
    }

    companion object {
        private const val TAG = "EngineWatchdog"
        private const val DEFAULT_HEARTBEAT_TIMEOUT_MS = 5_000L
        private const val DEFAULT_CHECK_INTERVAL_MS = 1_000L
    }
}
