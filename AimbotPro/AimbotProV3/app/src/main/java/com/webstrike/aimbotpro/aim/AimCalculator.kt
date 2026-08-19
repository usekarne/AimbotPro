package com.webstrike.aimbotpro.aim

import android.graphics.RectF
import com.webstrike.aimbotpro.Constants
import com.webstrike.aimbotpro.config.FeatureFlags
import com.webstrike.aimbotpro.detection.Detection
import com.webstrike.aimbotpro.input.InputInjector
import com.webstrike.aimbotpro.input.TouchSimulator
import com.webstrike.aimbotpro.utils.Logger

/**
 * Aim math + dispatch. Takes a chosen [Detection], projects it to screen
 * coords, applies smoothing / headshot bias / aim-speed shaping, and
 * finally hands the delta off to [TouchSimulator.applyAimDelta] for actual
 * gesture injection.
 *
 * All tunable behaviour is read live from [FeatureFlags] on every call, so
 * the user can flip toggles mid-game and feel the change on the next frame
 * without re-constructing this object.
 *
 * The object is stateful only via its [smoother] — it does not keep its
 * own per-target history. [reset] clears the smoother (e.g. when the
 * pipeline is restarted or the target is lost for a noticeable period).
 *
 * @param inputInjector  singleton input dispatch façade — currently unused
 *                        on the hot path (TouchSimulator already routes
 *                        through it), but injected to keep the door open
 *                        for raw swipe fallback if TouchSimulator is
 *                        unavailable.
 * @param touchSimulator  the per-session touch helper that owns the
 *                        background dispatch Handler.
 * @param smoother        the [AimSmoother] instance to drive (typically
 *                        owned by the engine and shared with this class).
 */
class AimCalculator(
    private val inputInjector: InputInjector,
    private val touchSimulator: TouchSimulator,
    private val smoother: AimSmoother
) {

    private val logTag = "AimCalculator"

    /**
     * Aim at [target] for the current frame.
     *
     * @param target    the detection chosen by [TargetSelector]. Must not be
     *                   null — the caller is expected to short-circuit when
     *                   no target is available.
     * @param modelSize the square YOLO model input size (e.g. 640) used to
     *                   project [Detection.box] back to screen coords via
     *                   [Detection.mapToScreen].
     * @param screenW   physical screen width in pixels.
     * @param screenH   physical screen height in pixels.
     * @param dt        inter-frame delta in milliseconds (passed through to
     *                   the smoother).
     * @return `true` if a target was successfully processed (and, unless
     *         silent-aim is on, an aim delta was dispatched). `false` only
     *         on a hard guard failure (bad target geometry, etc.).
     */
    fun aim(target: Detection, modelSize: Int, screenW: Int, screenH: Int, dt: Long): Boolean {
        // ----- Guard inputs -----
        if (screenW <= 0 || screenH <= 0) {
            Logger.w(logTag, "aim: invalid screen dims ${screenW}x${screenH}")
            return false
        }
        if (modelSize <= 0) {
            Logger.w(logTag, "aim: invalid modelSize=$modelSize")
            return false
        }

        // ----- Project target box to screen coords -----
        val screenBox = target.mapToScreen(modelSize, screenW, screenH)
        if (!rectIsFinite(screenBox)) {
            Logger.w(logTag, "aim: non-finite screenBox from $target")
            return false
        }

        // ----- Compute target centre -----
        var targetX = (screenBox.left + screenBox.right) * 0.5f
        var targetY = (screenBox.top + screenBox.bottom) * 0.5f

        // ----- Headshot bias (if enabled) -----
        // Bias Y upward (smaller Y) by HEADSHOT_BIAS * boxHeight — the head
        // sits in the upper portion of a person box.
        if (FeatureFlags.headshotModeEnabled) {
            val boxHeight = screenBox.height()
            if (boxHeight.isFinite() && boxHeight > 0f) {
                val bias = boxHeight * Constants.Aim.HEADSHOT_BIAS
                targetY -= bias
            }
        }

        // Final NaN guard on target coords.
        if (!targetX.isFinite() || !targetY.isFinite()) {
            Logger.w(logTag, "aim: non-finite target centre ($targetX,$targetY)")
            return false
        }

        // ----- Apply smoothing (if enabled) -----
        if (FeatureFlags.aimSmoothEnabled) {
            val smoothed = smoother.smooth(
                targetX,
                targetY,
                FeatureFlags.aimSmoothness,
                dt
            )
            targetX = smoothed[0]
            targetY = smoothed[1]
            if (!targetX.isFinite() || !targetY.isFinite()) {
                Logger.w(logTag, "aim: smoother returned non-finite ($targetX,$targetY)")
                return false
            }
        }

        // ----- Compute raw delta from screen centre -----
        val centerX = screenW * 0.5f
        val centerY = screenH * 0.5f
        var dx = targetX - centerX
        var dy = targetY - centerY

        // ----- Silent aim: lock target without moving the camera -----
        if (FeatureFlags.silentAimEnabled) {
            // Target is locked — engine/trigger bot may act on it; we don't
            // dispatch a visible aim delta.
            return true
        }

        // ----- Aim-speed shaping -----
        // Spec: (dx,dy) * (1 - aimSpeed) * 0.5 + (dx,dy) * aimSpeed * 1.0
        // Simplifies to: dx * (0.5 + 0.5 * aimSpeed) = dx * 0.5 * (1 + aimSpeed)
        // Higher aimSpeed → factor closer to 1.0 (full delta, snappy).
        // Lower aimSpeed  → factor closer to 0.5 (half delta, controlled).
        val aimSpeed = when {
            !FeatureFlags.aimSpeed.isFinite() -> Constants.Aim.DEFAULT_AIM_SPEED
            else -> FeatureFlags.aimSpeed.coerceIn(0f, 1f)
        }
        val speedFactor = 0.5f * (1f + aimSpeed)
        dx *= speedFactor
        dy *= speedFactor

        // ----- Cap per-frame delta to ±40px (anti-snap) -----
        dx = clampDelta(dx, MAX_DELTA_PER_FRAME_PX)
        dy = clampDelta(dy, MAX_DELTA_PER_FRAME_PX)

        // ----- Final NaN guard before dispatch -----
        if (!dx.isFinite() || !dy.isFinite()) {
            Logger.w(logTag, "aim: non-finite delta ($dx,$dy) — skipping dispatch")
            return false
        }
        // Skip zero deltas — TouchSimulator.applyAimDelta also short-circuits
        // these, but checking here avoids the Handler.post round-trip.
        if (dx == 0f && dy == 0f) {
            return true
        }

        // ----- Accessibility readiness check -----
        // Cheap optimisation: if the accessibility service isn't connected,
        // the underlying InputInjector.dispatchMove will return false anyway,
        // but we still avoid the Handler.post round-trip into TouchSimulator
        // — saves a Runnable allocation on every inference frame where
        // the user hasn't granted accessibility yet.
        if (inputInjector.accessibilityService == null) {
            Logger.w(logTag, "aim: accessibility not connected; dispatch skipped")
            return false
        }

        // ----- Dispatch -----
        touchSimulator.applyAimDelta(dx, dy)
        return true
    }

    /**
     * Reset internal state — delegates to [AimSmoother.reset]. Call when
     * the aim pipeline is being torn down or the target has been lost for
     * a noticeable period (avoids stale-EMA drift on the next target).
     */
    fun reset() {
        smoother.reset()
    }

    // ----- internals -----

    /** True iff all four edges of [r] are finite floats. */
    private fun rectIsFinite(r: RectF): Boolean =
        r.left.isFinite() && r.top.isFinite() &&
            r.right.isFinite() && r.bottom.isFinite()

    /**
     * Clamp [delta] to `[-maxAbs, +maxAbs]`. NaN/Infinity → 0 (no dispatch
     * for that axis) — caller is expected to bail out separately if both
     * axes are 0.
     */
    private fun clampDelta(delta: Float, maxAbs: Float): Float {
        if (!delta.isFinite()) return 0f
        if (delta > maxAbs) return maxAbs
        if (delta < -maxAbs) return -maxAbs
        return delta
    }

    companion object {
        /** Max per-frame camera delta in pixels — prevents obvious snapping. */
        private const val MAX_DELTA_PER_FRAME_PX = 40f
    }
}
