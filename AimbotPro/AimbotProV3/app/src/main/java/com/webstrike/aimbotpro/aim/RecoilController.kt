package com.webstrike.aimbotpro.aim

import android.os.Handler
import com.webstrike.aimbotpro.config.FeatureFlags
import com.webstrike.aimbotpro.input.InputInjector
import com.webstrike.aimbotpro.input.TouchSimulator
import com.webstrike.aimbotpro.utils.Logger

/**
 * Recoil control system — compensates for weapon recoil by applying
 * continuous downward swipe gestures while the trigger bot is active.
 *
 * ## How it works
 * When recoil control is enabled AND the trigger bot fires, this controller
 * starts a rapid loop that applies small downward deltas. This pulls the
 * camera down, counteracting the upward recoil kick of automatic weapons.
 *
 * ## Scrolling Up/Down
 * The recoil compensation direction is **downward** (negative Y in screen
 * coords) by default. The strength is derived from [FeatureFlags.aimSpeed]
 * — higher speed = stronger recoil pull.
 *
 * The system monitors trigger bot fires and automatically engages/disengages
 * recoil compensation. When no target is being fired at, compensation stops
 * to avoid unwanted camera movement.
 *
 * @param touchSimulator the per-session touch helper for gesture dispatch
 * @param handler the background handler for posting gestures
 */
class RecoilController(
    private val touchSimulator: TouchSimulator,
    private val handler: Handler
) {
    private val logTag = "RecoilController"

    @Volatile private var active: Boolean = false
    @Volatile private var compensating: Boolean = false

    /** Track consecutive fire events to know when to engage/disengage. */
    @Volatile private var lastFireTimeMs: Long = 0L

    /** Running state for the compensation tick. */
    private var compensationJob: java.util.concurrent.ScheduledFuture<*>? = null
    private val compensationExecutor = java.util.concurrent.ScheduledThreadPoolExecutor(1).apply {
        removeOnCancelPolicy = true
    }

    /**
     * Notify the controller that the trigger bot fired.
     * Starts recoil compensation if enabled.
     */
    fun onTriggerFired() {
        lastFireTimeMs = android.os.SystemClock.elapsedRealtimeNanos() / 1_000_000L
        if (FeatureFlags.recoilControlEnabled && !compensating) {
            startCompensation()
        }
    }

    /**
     * Called each frame from the engine loop. If we haven't seen a fire
     * event recently, stop compensation.
     */
    fun tick() {
        if (!compensating) return
        val now = android.os.SystemClock.elapsedRealtimeNanos() / 1_000_000L
        // If no fire in the last 200ms, stop compensating
        if (now - lastFireTimeMs > 200L) {
            stopCompensation()
        }
    }

    private fun startCompensation() {
        if (compensating) return
        compensating = true
        Logger.d(logTag, "Recoil compensation STARTED")

        // Apply a small downward pull every 33ms (~30 times/sec)
        // This is fast enough to counteract even rapid-fire recoil
        compensationJob = compensationExecutor.scheduleAtFixedRate({
            if (!compensating || !FeatureFlags.recoilControlEnabled) {
                stopCompensation()
                return@scheduleAtFixedRate
            }
            if (InputInjector.accessibilityService == null) return@scheduleAtFixedRate

            // Compute recoil pull strength based on aim speed
            val strength = when {
                FeatureFlags.aimSpeed.isFinite() -> FeatureFlags.aimSpeed.coerceIn(0.2f, 1f)
                else -> 0.5f
            }

            // Downward delta: negative Y in screen coords = pull camera down
            val dy = -(RECOIL_BASE_PULL * strength)
            // Slight horizontal wobble to feel more natural
            val dx = ((-1..1).random() * RECOIL_WOBBLE * strength)

            if (dx != 0f || dy != 0f) {
                handler.post {
                    InputInjector.dispatchMove(dx, dy, RECOIL_SWIPE_DURATION_MS)
                }
            }
        }, 0L, 33L, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    private fun stopCompensation() {
        if (!compensating) return
        compensating = false
        compensationJob?.cancel(false)
        compensationJob = null
        Logger.d(logTag, "Recoil compensation STOPPED")
    }

    fun reset() {
        stopCompensation()
        lastFireTimeMs = 0L
    }

    fun release() {
        reset()
        compensationExecutor.shutdown()
    }

    companion object {
        /** Base downward pull per tick in pixels. */
        private const val RECOIL_BASE_PULL = 3.5f

        /** Horizontal wobble range in pixels. */
        private const val RECOIL_WOBBLE = 1.5f

        /** Duration of each recoil compensation swipe. */
        private const val RECOIL_SWIPE_DURATION_MS = 16L
    }
}
