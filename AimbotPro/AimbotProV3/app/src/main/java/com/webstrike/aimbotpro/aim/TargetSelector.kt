package com.webstrike.aimbotpro.aim

import android.graphics.PointF
import com.webstrike.aimbotpro.detection.Detection
import com.webstrike.aimbotpro.utils.Logger

/**
 * Picks the "best" detection from a frame's batch to aim at.
 *
 * Pure function — no internal state, no side effects. Caller (the engine)
 * invokes [select] once per inference frame with the screen-space detections
 * (i.e. Detection instances whose [Detection.box] has already been projected
 * to screen pixel coords — see [Detection.mapToScreen]) so the distance check
 * against [screenCenter] is meaningful.
 *
 * Scoring heuristic:
 *   - Confidence contributes 50% of the score (higher = better).
 *   - Proximity to screen centre contributes 50% (closer = better).
 *   - In headshot mode, taller boxes (i.e. head-like aspect) get a small
 *     bonus — `box.height / screenHeight * 0.2` — biasing selection toward
 *     headshots.
 *
 * NaN safety: any detection whose centre or box is non-finite is skipped
 * silently — never crashes the aim pipeline on a corrupted detection.
 *
 * @see Detection.mapToScreen
 */
class TargetSelector {

    private val logTag = "TargetSelector"

    /**
     * Select the best detection to aim at, or `null` if none is inside the
     * FOV circle (or all candidates have non-finite geometry).
     *
     * @param detections    candidate detections; [Detection.box] should be in
     *                       the same coordinate space as [screenCenter]
     *                       (typically physical screen pixels).
     * @param screenCenter  crosshair position in screen pixels.
     * @param fovRadiusPx   FOV-circle radius in screen pixels; candidates
     *                       whose centre is further than this from
     *                       [screenCenter] are filtered out.
     * @param headshotMode  when `true`, taller boxes get a score bonus.
     * @param screenHeight  physical screen height in pixels — used to
     *                       normalise the headshot bonus. Caller (Engine)
     *                       passes the live [android.util.DisplayMetrics]
     *                       height; passing 0 disables the bonus.
     * @return the highest-scoring detection, or `null`.
     */
    fun select(
        detections: List<Detection>,
        screenCenter: PointF,
        fovRadiusPx: Float,
        headshotMode: Boolean,
        screenHeight: Float = screenCenter.y * 2f
    ): Detection? {
        // Guard inputs — bad params mean "no target" rather than a crash.
        if (detections.isEmpty()) return null
        if (!screenCenter.x.isFinite() || !screenCenter.y.isFinite()) {
            Logger.w(logTag, "select: non-finite screenCenter ($screenCenter); aborting")
            return null
        }
        if (!fovRadiusPx.isFinite() || fovRadiusPx <= 0f) {
            Logger.w(logTag, "select: invalid fovRadiusPx=$fovRadiusPx; aborting")
            return null
        }

        val sh = if (screenHeight > 0f) screenHeight else (screenCenter.y * 2f).coerceAtLeast(1f)

        var best: Detection? = null
        var bestScore = Float.NEGATIVE_INFINITY

        for (det in detections) {
            val cx = det.centerX()
            val cy = det.centerY()
            if (!cx.isFinite() || !cy.isFinite()) {
                Logger.w(logTag, "select: skipping non-finite center for $det")
                continue
            }

            val dx = cx - screenCenter.x
            val dy = cy - screenCenter.y
            // hypot is NaN-safe (returns NaN only if BOTH inputs are NaN).
            val distance = hypotSafe(dx, dy)
            if (!distance.isFinite()) continue

            // Filter: outside FOV circle → skip.
            if (distance >= fovRadiusPx) continue

            // Score: 50% confidence + 50% proximity (closer = higher).
            val confidence = det.confidence.coerceIn(0f, 1f)
            val proximity = 1f - (distance / fovRadiusPx).coerceIn(0f, 1f)
            var score = confidence * 0.5f + proximity * 0.5f

            // Headshot bonus: taller boxes (head region) get up to +0.2.
            if (headshotMode) {
                val boxHeight = det.box.height()
                if (boxHeight.isFinite() && boxHeight > 0f) {
                    val bonus = (boxHeight / sh).coerceIn(0f, 1f) * 0.2f
                    score += bonus
                }
            }

            if (!score.isFinite()) continue
            if (score > bestScore) {
                bestScore = score
                best = det
            }
        }

        return best
    }

    /** NaN-safe hypot — falls back to direct math if Math.hypot returns NaN. */
    private fun hypotSafe(dx: Float, dy: Float): Float {
        val r = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
        return if (r.isFinite()) r else {
            val fallback = kotlin.math.sqrt(dx * dx + dy * dy)
            if (fallback.isFinite()) fallback else Float.POSITIVE_INFINITY
        }
    }
}
