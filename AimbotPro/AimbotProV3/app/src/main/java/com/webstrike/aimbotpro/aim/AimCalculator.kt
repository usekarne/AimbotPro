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
 * coords, applies headshot priority targeting, smoothing / aim-speed shaping,
 * and hands the delta off to [TouchSimulator.applyAimDelta].
 *
 * ## Headshot Priority Mode (v6)
 * When enabled, the aim point is set to the **top 10% of the bounding box**
 * (the head region of a detected person). Combined with:
 *   - Much higher per-frame delta cap (250 px) for instant snapping
 *   - Smoothing completely disabled
 *   - Full-speed factor (0.95+)
 *   - Large density-independent snap radius (60 dp)
 * this delivers a TRUE one-tap headshot: if any enemy is visible on
 * screen, the crosshair locks onto their head instantly.
 *
 * ## One-Tap Headshot
 * When headshot mode is ON and the target's head is within the snap radius
 * of the crosshair, we skip ALL smoothing and apply the FULL delta in a
 * single frame — delivering an instant headshot lock.
 */
class AimCalculator(
    private val inputInjector: InputInjector,
    private val touchSimulator: TouchSimulator,
    private val smoother: AimSmoother
) {

    private val logTag = "AimCalculator"

    fun aim(target: Detection, modelSize: Int, screenW: Int, screenH: Int, dt: Long): Boolean {
        if (screenW <= 0 || screenH <= 0) {
            Logger.w(logTag, "aim: invalid screen dims ${screenW}x${screenH}")
            return false
        }
        if (modelSize <= 0) {
            Logger.w(logTag, "aim: invalid modelSize=$modelSize")
            return false
        }

        val screenBox = target.mapToScreen(modelSize, screenW, screenH)
        if (!rectIsFinite(screenBox)) {
            Logger.w(logTag, "aim: non-finite screenBox from $target")
            return false
        }

        val centerX = screenW * 0.5f
        val centerY = screenH * 0.5f

        // Default: aim at center of box
        var targetX = (screenBox.left + screenBox.right) * 0.5f
        var targetY = (screenBox.top + screenBox.bottom) * 0.5f

        val headshotMode = FeatureFlags.headshotModeEnabled

        if (headshotMode) {
            // HEADSHOT PRIORITY: Target the TOP 10% of the bounding box.
            // The head of a standing person is in the top 8-15% of the box.
            // We aim at top + 10% of box height for precise head targeting.
            val boxHeight = screenBox.height()
            if (boxHeight.isFinite() && boxHeight > 0f) {
                val headOffsetY = boxHeight * HEADSHOT_TARGET_FRACTION
                targetY = screenBox.top + headOffsetY

                // Keep X at box center (head center horizontally)
                targetX = (screenBox.left + screenBox.right) * 0.5f

                // Check if head is close to crosshair — ONE-TAP HEADSHOT
                val headDx = targetX - centerX
                val headDy = targetY - centerY
                val headDist = Math.hypot(headDx.toDouble(), headDy.toDouble()).toFloat()

                if (headDist.isFinite() && headDist < HEADSHOT_SNAP_RADIUS_PX) {
                    // DIRECT SNAP — no smoothing, no cap, instant headshot
                    var dx = targetX - centerX
                    var dy = targetY - centerY

                    if (dx.isFinite() && dy.isFinite() && (kotlin.math.abs(dx) > 0.5f || kotlin.math.abs(dy) > 0.5f)) {
                        if (inputInjector.accessibilityService == null) {
                            Logger.w(logTag, "aim: accessibility not connected; snap skipped")
                            return false
                        }
                        // Apply FULL delta — no smoothing, no speed cap, no delta cap.
                        // This is the ONE-TAP HEADSHOT: instant lock.
                        touchSimulator.applyAimDelta(dx, dy)
                        return true
                    }
                }
            }
        }

        // Final NaN guard
        if (!targetX.isFinite() || !targetY.isFinite()) {
            Logger.w(logTag, "aim: non-finite target centre ($targetX,$targetY)")
            return false
        }

        // Apply smoothing ONLY in non-headshot mode (headshot mode never smooths)
        if (FeatureFlags.aimSmoothEnabled && !headshotMode) {
            val smoothed = smoother.smooth(
                targetX,
                targetY,
                FeatureFlags.aimSmoothness,
                dt
            )
            targetX = smoothed[0]
            targetY = smoothed[1]
            if (!targetX.isFinite() || !targetY.isFinite()) {
                return false
            }
        }

        var dx = targetX - centerX
        var dy = targetY - centerY

        if (FeatureFlags.silentAimEnabled) {
            return true
        }

        // Aim-speed shaping
        val aimSpeed = when {
            headshotMode -> FeatureFlags.aimSpeed.coerceIn(0f, 1f).coerceAtLeast(HEADSHOT_MIN_SPEED)
            !FeatureFlags.aimSpeed.isFinite() -> Constants.Aim.DEFAULT_AIM_SPEED
            else -> FeatureFlags.aimSpeed.coerceIn(0f, 1f)
        }

        val speedFactor = if (headshotMode) {
            // In headshot mode: EXTREMELY aggressive — nearly full delta
            0.85f + 0.15f * aimSpeed
        } else {
            0.5f * (1f + aimSpeed)
        }
        dx *= speedFactor
        dy *= speedFactor

        // Per-frame delta cap — MUCH higher in headshot mode for aggressive snapping
        val maxDelta = if (headshotMode) MAX_DELTA_HEADSHOT_PX else MAX_DELTA_PER_FRAME_PX
        dx = clampDelta(dx, maxDelta)
        dy = clampDelta(dy, maxDelta)

        if (!dx.isFinite() || !dy.isFinite()) {
            return false
        }
        if (dx == 0f && dy == 0f) {
            return true
        }

        if (inputInjector.accessibilityService == null) {
            Logger.w(logTag, "aim: accessibility not connected; dispatch skipped")
            return false
        }

        touchSimulator.applyAimDelta(dx, dy)
        return true
    }

    fun reset() {
        smoother.reset()
    }

    private fun rectIsFinite(r: RectF): Boolean =
        r.left.isFinite() && r.top.isFinite() &&
            r.right.isFinite() && r.bottom.isFinite()

    private fun clampDelta(delta: Float, maxAbs: Float): Float {
        if (!delta.isFinite()) return 0f
        if (delta > maxAbs) return maxAbs
        if (delta < -maxAbs) return -maxAbs
        return delta
    }

    companion object {
        /** Normal mode max per-frame camera delta in pixels. */
        private const val MAX_DELTA_PER_FRAME_PX = 40f

        /** Headshot mode — VERY high cap for instant head snapping. */
        private const val MAX_DELTA_HEADSHOT_PX = 250f

        /** Head region target: top 10% of the bounding box = head center. */
        private const val HEADSHOT_TARGET_FRACTION = 0.10f

        /**
         * One-tap snap radius in pixels.
         * 60dp * 3.0 (typical density) = 180px — large enough that any
         * target on screen gets snapped to instantly in headshot mode.
         * This is a density-independent value calibrated for the
         * typical phone screen (1080x2400, ~3x density).
         */
        private const val HEADSHOT_SNAP_RADIUS_PX = 180f

        /** Minimum aim speed in headshot mode (0.90 = extremely aggressive). */
        private const val HEADSHOT_MIN_SPEED = 0.90f
    }
}
