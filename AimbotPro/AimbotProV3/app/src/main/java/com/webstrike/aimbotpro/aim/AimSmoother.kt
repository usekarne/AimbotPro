package com.webstrike.aimbotpro.aim

import com.webstrike.aimbotpro.config.FeatureFlags
import com.webstrike.aimbotpro.utils.Logger

/**
 * Humanised exponential-moving-average aim trajectory smoother.
 *
 * ## Headshot Mode (v5)
 * When [FeatureFlags.headshotModeEnabled] is true, jitter is **disabled** and
 * the EMA alpha is boosted for faster convergence. This ensures precise
 * head targeting without wobble.
 *
 * ## Normal Mode
 * Applies sinusoidal jitter for humanisation + frame-rate-independent EMA.
 */
class AimSmoother {

    private val logTag = "AimSmoother"

    @Volatile private var lastTargetX: Float = 0f
    @Volatile private var lastTargetY: Float = 0f
    @Volatile private var lastAppliedX: Float = 0f
    @Volatile private var lastAppliedY: Float = 0f
    private var jitterPhase: Double = 0.0
    private val jitterAmplitudePx: Float = 2f
    private val jitterFreq: Double = 2.0 * Math.PI / 7.0

    private val output: FloatArray = FloatArray(2)

    fun smooth(
        targetX: Float,
        targetY: Float,
        smoothness: Float,
        dt: Long
    ): FloatArray {
        val sTargetX = if (targetX.isFinite()) targetX else lastAppliedX
        val sTargetY = if (targetY.isFinite()) targetY else lastAppliedY

        val sSmoothness = when {
            !smoothness.isFinite() -> 0.5f
            else -> smoothness.coerceIn(0f, 1f)
        }

        val dtMs = if (dt > 0L) dt.coerceIn(4L, 50L) else 16L

        val headshotMode = FeatureFlags.headshotModeEnabled

        // In headshot mode: reduce smoothing for faster convergence
        val effectiveSmoothness = if (headshotMode) {
            sSmoothness * 0.3f  // Much less smoothing in headshot mode
        } else {
            sSmoothness
        }

        val alphaRaw = (1f - effectiveSmoothness) * (dtMs.toFloat() / 16f)
        val alpha = alphaRaw.coerceIn(0f, 1f)

        var nextX = lastAppliedX + (sTargetX - lastAppliedX) * alpha
        var nextY = lastAppliedY + (sTargetY - lastAppliedY) * alpha

        // Jitter: DISABLED in headshot mode for precision
        if (!headshotMode) {
            jitterPhase += jitterFreq
            val jitterX = (Math.cos(jitterPhase) * jitterAmplitudePx).toFloat()
            val jitterY = (Math.sin(jitterPhase * 1.3) * jitterAmplitudePx).toFloat()
            nextX += jitterX
            nextY += jitterY
        }

        if (!nextX.isFinite()) nextX = lastAppliedX
        if (!nextY.isFinite()) nextY = lastAppliedY

        lastTargetX = sTargetX
        lastTargetY = sTargetY
        lastAppliedX = nextX
        lastAppliedY = nextY

        output[0] = nextX
        output[1] = nextY
        return output
    }

    fun reset() {
        lastTargetX = 0f
        lastTargetY = 0f
        lastAppliedX = 0f
        lastAppliedY = 0f
        jitterPhase = 0.0
    }
}
