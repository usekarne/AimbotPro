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
 * ## Headshot Priority Mode (v5)
 * When enabled, the aim point is set to the **top 15% of the bounding box**
 * (the head region of a detected person). Combined with higher aim speed and
 * reduced smoothing, this delivers instant head-level targeting.
 *
 * ## One-Tap Headshot
 * When headshot mode is ON and the target's head is within a tight radius
 * of the crosshair (HEADSHOT_SNAP_RADIUS_PX), we skip smoothing entirely
 * and snap directly to the head — delivering a true one-tap headshot
 * experience.
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
            // HEADSHOT PRIORITY: Target the TOP of the bounding box.
            // The head of a standing person is in the top 12-18% of the box.
            // We aim at top + 12% of box height for precise head targeting.
            val boxHeight = screenBox.height()
            if (boxHeight.isFinite() && boxHeight > 0f) {
                val headOffsetY = boxHeight * HEADSHOT_TARGET_FRACTION
                targetY = screenBox.top + headOffsetY

                // Also bias X slightly toward center-top of the head region
                // for more natural head targeting
                val headWidth = screenBox.width() * 0.5f
                targetX = screenBox.left + screenBox.width() * 0.5f

                // Check if head is very close to crosshair — ONE-TAP HEADSHOT
                val headDx = targetX - centerX
                val headDy = targetY - centerY
                val headDist = Math.hypot(headDx.toDouble(), headDy.toDouble()).toFloat()

                if (headDist < HEADSHOT_SNAP_RADIUS_PX) {
                    // DIRECT SNAP — no smoothing, full speed, instant headshot
                    var dx = targetX - centerX
                    var dy = targetY - centerY

                    if (dx.isFinite() && dy.isFinite() && (dx != 0f || dy != 0f)) {
                        if (inputInjector.accessibilityService == null) {
                            Logger.w(logTag, "aim: accessibility not connected; snap skipped")
                            return false
                        }
                        // Apply at full speed — no smoothing, no cap
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

        // Apply smoothing (skip in headshot mode when close to target)
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
            // In headshot mode, much more aggressive — nearly full delta
            0.7f + 0.3f * aimSpeed
        } else {
            0.5f * (1f + aimSpeed)
        }
        dx *= speedFactor
        dy *= speedFactor

        // Per-frame delta cap — higher in headshot mode for faster snapping
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

        /** Headshot mode — much higher cap for aggressive snapping. */
        private const val MAX_DELTA_HEADSHOT_PX = 120f

        /** Head region target: top 12% of the bounding box = head center. */
        private const val HEADSHOT_TARGET_FRACTION = 0.12f

        /** If head is within this radius of crosshair, snap instantly (one-tap). */
        private const val HEADSHOT_SNAP_RADIUS_PX = 80f

        /** Minimum aim speed in headshot mode (0.85 = very aggressive). */
        private const val HEADSHOT_MIN_SPEED = 0.85f
    }
}
