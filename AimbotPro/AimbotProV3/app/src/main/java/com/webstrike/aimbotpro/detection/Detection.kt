package com.webstrike.aimbotpro.detection

import android.graphics.PointF
import android.graphics.RectF
import com.webstrike.aimbotpro.utils.BitmapUtils

/**
 * Single YOLO detection.
 *
 * The bounding [box] is expressed in model-input pixel coords (i.e. the
 * square 0..INPUT_SIZE coordinate space produced by [BitmapUtils.resizeLetterbox]).
 * Down-stream consumers (aim, overlay) call [mapToScreen] to project it back
 * into the actual screen pixel space.
 *
 * @property classId COCO class index (0 = person). Demo-mode simulated
 *                   detections use -1 so callers can identify and skip them.
 * @property confidence in [0, 1].
 * @property box      rectangle in model-input pixel coords.
 * @property label    human-readable class name; "demo" for simulated detections.
 */
data class Detection(
    val classId: Int,
    val confidence: Float,
    val box: RectF,
    val label: String
) {

    /**
     * Center of the bounding box, in model-input coords. Allocates a [PointF]
     * per call — prefer [centerX] / [centerY] on the hot path to avoid
     * per-detection-per-frame allocation churn.
     */
    val center: PointF
        get() = PointF(centerX(), centerY())

    /** Center X of the bounding box (allocation-free). */
    fun centerX(): Float = (box.left + box.right) * 0.5f

    /** Center Y of the bounding box (allocation-free). */
    fun centerY(): Float = (box.top + box.bottom) * 0.5f

    /**
     * Project this detection's box from model-input pixel coords back to
     * screen pixel coords. Reverses the letter-box transform applied to the
     * source bitmap before inference.
     */
    fun mapToScreen(modelSize: Int, screenW: Int, screenH: Int): RectF =
        BitmapUtils.mapBoxToScreen(box, modelSize, screenW, screenH)

    companion object {
        /** Convenience for "no detections" — keeps callers terse. */
        fun empty(): List<Detection> = emptyList()
    }
}
