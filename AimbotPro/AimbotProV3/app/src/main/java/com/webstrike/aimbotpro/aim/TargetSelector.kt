package com.webstrike.aimbotpro.aim

import android.graphics.PointF
import com.webstrike.aimbotpro.detection.Detection
import com.webstrike.aimbotpro.utils.Logger

/**
 * Picks the "best" detection from a frame's batch to aim at.
 *
 * ## Headshot Priority Mode
 * When enabled, scoring dramatically favours the detection whose **head
 * region** (top 15% of box) is closest to the crosshair. This ensures the
 * aimbot locks onto the nearest enemy's head, not just their body center.
 *
 * ## Normal Mode
 * 50% confidence + 50% proximity to screen centre.
 *
 * All geometry is in screen pixel coords (caller projects via Detection.mapToScreen).
 */
class TargetSelector {

    private val logTag = "TargetSelector"

    fun select(
        detections: List<Detection>,
        screenCenter: PointF,
        fovRadiusPx: Float,
        headshotMode: Boolean,
        screenHeight: Float = screenCenter.y * 2f
    ): Detection? {
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
            val box = det.box
            val cx = (box.left + box.right) * 0.5f
            val cy = (box.top + box.bottom) * 0.5f
            if (!cx.isFinite() || !cy.isFinite()) continue

            val dx = cx - screenCenter.x
            val dy = cy - screenCenter.y
            val distance = hypotSafe(dx, dy)
            if (!distance.isFinite()) continue

            // FOV filter
            if (distance >= fovRadiusPx) continue

            val confidence = det.confidence.coerceIn(0f, 1f)
            val proximity = 1f - (distance / fovRadiusPx).coerceIn(0f, 1f)

            var score: Float

            if (headshotMode) {
                // HEADSHOT PRIORITY: Score based on head proximity.
                // Head region = top 15% of the bounding box.
                val boxHeight = box.height()
                val headCenterX = cx
                val headCenterY = if (boxHeight.isFinite() && boxHeight > 0f) {
                    box.top + boxHeight * HEAD_REGION_FRACTION
                } else {
                    cy
                }

                val headDx = headCenterX - screenCenter.x
                val headDy = headCenterY - screenCenter.y
                val headDistance = hypotSafe(headDx, headDy)

                if (headDistance.isFinite() && headDistance < fovRadiusPx) {
                    val headProximity = 1f - (headDistance / fovRadiusPx).coerceIn(0f, 1f)
                    // 60% head proximity + 30% confidence + 10% body proximity
                    score = headProximity * 0.6f + confidence * 0.3f + proximity * 0.1f

                    // Bonus for taller boxes (closer targets = bigger boxes = easier headshots)
                    if (boxHeight.isFinite() && boxHeight > 0f) {
                        val sizeBonus = (boxHeight / sh).coerceIn(0f, 1f) * 0.15f
                        score += sizeBonus
                    }
                } else {
                    // Head outside FOV — fall back to body center scoring with penalty
                    score = confidence * 0.5f + proximity * 0.3f
                }
            } else {
                // Normal mode: 50% confidence + 50% proximity
                score = confidence * 0.5f + proximity * 0.5f
            }

            if (!score.isFinite()) continue
            if (score > bestScore) {
                bestScore = score
                best = det
            }
        }

        return best
    }

    private fun hypotSafe(dx: Float, dy: Float): Float {
        val r = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
        return if (r.isFinite()) r else {
            val fallback = kotlin.math.sqrt(dx * dx + dy * dy)
            if (fallback.isFinite()) fallback else Float.POSITIVE_INFINITY
        }
    }

    companion object {
        /** Head region: top 15% of bounding box. */
        private const val HEAD_REGION_FRACTION = 0.15f
    }
}
