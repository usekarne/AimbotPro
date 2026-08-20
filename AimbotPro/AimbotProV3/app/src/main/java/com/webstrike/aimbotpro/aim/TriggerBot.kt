package com.webstrike.aimbotpro.aim

import android.graphics.PointF
import com.webstrike.aimbotpro.config.FeatureFlags
import com.webstrike.aimbotpro.detection.Detection
import com.webstrike.aimbotpro.input.TouchSimulator
import com.webstrike.aimbotpro.utils.Logger

/**
 * Auto-fire controller. Fires the configured in-game weapon whenever a
 * target is sufficiently close to the crosshair and enough time has
 * elapsed since the last shot.
 *
 * ## Headshot Mode Integration (v5)
 * In headshot mode, the fire zone is MUCH tighter (15% of FOV instead of 30%)
 * and the minimum delay is reduced. Combined with the AimCalculator's
 * instant-snap behavior, this delivers the "one-tap headshot" experience:
 * as soon as a target enters the aim zone and the crosshair is near the head,
 * the bot fires immediately.
 */
class TriggerBot(private val touchSimulator: TouchSimulator) {

    private val logTag = "TriggerBot"

    @Volatile var lastFireTimeMs: Long = 0L
        private set

    fun maybeFire(target: Detection?, fovRadiusPx: Float, screenCenter: PointF): Boolean {
        if (!FeatureFlags.triggerBotEnabled) return false
        if (target == null) return false

        if (!screenCenter.x.isFinite() || !screenCenter.y.isFinite()) return false
        if (!fovRadiusPx.isFinite() || fovRadiusPx <= 0f) return false

        val cx = target.centerX()
        val cy = target.centerY()
        if (!cx.isFinite() || !cy.isFinite()) return false

        val dx = cx - screenCenter.x
        val dy = cy - screenCenter.y
        val distance = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (!distance.isFinite()) return false

        // In headshot mode, check HEAD proximity (top of box) for fire decision
        val headshotMode = FeatureFlags.headshotModeEnabled
        val effectiveDistance = if (headshotMode) {
            // Use head region distance instead of body center
            val headY = target.box.top + target.box.height() * 0.12f
            val headDx = cx - screenCenter.x
            val headDy = headY - screenCenter.y
            Math.hypot(headDx.toDouble(), headDy.toDouble()).toFloat()
        } else {
            distance
        }

        if (!effectiveDistance.isFinite()) return false

        // Fire zone: tighter in headshot mode (15% vs 30%)
        val fireFraction = if (headshotMode) HEADSHOT_FIRE_ZONE_FRACTION else NORMAL_FIRE_ZONE_FRACTION
        val fireZoneRadius = fovRadiusPx * fireFraction
        if (effectiveDistance > fireZoneRadius) return false

        // Debounce — shorter in headshot mode for rapid fire
        val nowMs = android.os.SystemClock.elapsedRealtimeNanos() / 1_000_000L
        val delay = if (headshotMode) {
            (FeatureFlags.triggerDelayMs * 0.5f).toLong().coerceAtLeast(30L)
        } else {
            FeatureFlags.triggerDelayMs
        }

        if (lastFireTimeMs != 0L) {
            val elapsed = nowMs - lastFireTimeMs
            if (elapsed >= 0L && elapsed < delay) return false
        }

        lastFireTimeMs = nowMs
        touchSimulator.triggerFire()
        return true
    }

    fun reset() {
        lastFireTimeMs = 0L
    }

    companion object {
        /** Normal fire zone = 30% of FOV circle radius. */
        private const val NORMAL_FIRE_ZONE_FRACTION = 0.3f

        /** Headshot fire zone = 15% of FOV (tighter for precise headshots). */
        private const val HEADSHOT_FIRE_ZONE_FRACTION = 0.15f
    }
}
