package com.webstrike.aimbotpro.aim

import android.graphics.PointF
import com.webstrike.aimbotpro.config.FeatureFlags
import com.webstrike.aimbotpro.detection.Detection
import com.webstrike.aimbotpro.input.TouchSimulator
import com.webstrike.aimbotpro.utils.Logger

/**
 * Auto-fire controller. Fires the configured in-game weapon whenever a
 * target is sufficiently close to the crosshair and enough time has
 * elapsed since the last shot (debounce via [FeatureFlags.triggerDelayMs]).
 *
 * The fire dispatch itself is delegated to [TouchSimulator.triggerFire],
 * which posts a tap on the configured fire button to the background
 * Handler. This class therefore never blocks the inference coroutine.
 *
 * Fire geometry:
 *   - The target's centre must be within `fovRadiusPx * 0.3` of
 *     [screenCenter] (i.e. roughly inside the central third of the FOV
 *     circle) — this prevents the trigger bot from spraying at distant
 *     edge-of-screen detections where the aim hasn't fully converged.
 *
 * Debounce:
 *   - [FeatureFlags.triggerDelayMs] between consecutive fires. The first
 *     fire after [reset] (or after the service boots) is always allowed
 *     (subject to the geometry check).
 *
 * NaN safety: a target with non-finite centre coordinates is treated as
 * "out of range" — no fire, no crash.
 *
 * @param touchSimulator the per-session touch helper.
 */
class TriggerBot(private val touchSimulator: TouchSimulator) {

    private val logTag = "TriggerBot"

    /**
     * Last wall-clock fire time in ms (System.currentTimeMillis). Zero
     * sentinel means "never fired this session" — first call always passes
     * the debounce check.
     */
    @Volatile var lastFireTimeMs: Long = 0L
        private set

    /**
     * Possibly fire on [target].
     *
     * @param target       the chosen detection (may be `null` → no fire).
     * @param fovRadiusPx  FOV circle radius in screen pixels — used to
     *                      derive the inner "fire zone" radius.
     * @param screenCenter crosshair position in screen pixels.
     * @return `true` if a fire was dispatched this call; `false` otherwise
     *         (feature off, no target, too far off-centre, debounce, etc.).
     */
    fun maybeFire(target: Detection?, fovRadiusPx: Float, screenCenter: PointF): Boolean {
        // ----- Feature gate -----
        if (!FeatureFlags.triggerBotEnabled) return false

        // ----- No target? -----
        if (target == null) return false

        // ----- Sanitise geometry inputs -----
        if (!screenCenter.x.isFinite() || !screenCenter.y.isFinite()) {
            Logger.w(logTag, "maybeFire: non-finite screenCenter ($screenCenter)")
            return false
        }
        if (!fovRadiusPx.isFinite() || fovRadiusPx <= 0f) {
            Logger.w(logTag, "maybeFire: invalid fovRadiusPx=$fovRadiusPx")
            return false
        }

        // ----- Compute target centre -----
        val cx = target.centerX()
        val cy = target.centerY()
        if (!cx.isFinite() || !cy.isFinite()) {
            Logger.w(logTag, "maybeFire: non-finite target center ($cx,$cy)")
            return false
        }

        // ----- Distance from crosshair -----
        val dx = cx - screenCenter.x
        val dy = cy - screenCenter.y
        val distance = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (!distance.isFinite()) {
            Logger.w(logTag, "maybeFire: non-finite distance")
            return false
        }

        // ----- Inside fire zone? -----
        // Inner radius = 30% of the FOV circle — prevents firing at distant
        // edge-of-screen detections where the aim hasn't yet converged.
        val fireZoneRadius = fovRadiusPx * 0.3f
        if (distance > fireZoneRadius) {
            return false
        }

        // ----- Debounce -----
        // Use SystemClock.elapsedRealtimeNanos (monotonic) — wall-clock
        // (System.currentTimeMillis) can jump backwards if the user changes
        // system time, which would silently break the debounce for one cycle.
        val nowMs = android.os.SystemClock.elapsedRealtimeNanos() / 1_000_000L
        val delay = FeatureFlags.triggerDelayMs
        val effectiveDelay = if (delay > 0L) delay else 0L

        if (lastFireTimeMs != 0L) {
            val elapsed = nowMs - lastFireTimeMs
            // elapsed can't go negative on a monotonic clock — drop the
            // clock-skew check (kept the >= 0 guard defensively anyway).
            if (elapsed >= 0L && elapsed < effectiveDelay) {
                return false
            }
        }

        // ----- Fire -----
        lastFireTimeMs = nowMs
        touchSimulator.triggerFire()
        return true
    }

    /**
     * Clear state. Call when the aim pipeline is reset (e.g. target lost,
     * aimbot toggled off, service stopped) so the next fire isn't held off
     * by a stale debounce window.
     */
    fun reset() {
        lastFireTimeMs = 0L
    }
}
