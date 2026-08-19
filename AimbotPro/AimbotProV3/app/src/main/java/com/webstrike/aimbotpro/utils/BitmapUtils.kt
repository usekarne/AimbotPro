package com.webstrike.aimbotpro.utils

import android.graphics.RectF

/**
 * Helpers for bitmap transformation: ARGB → RGB float buffers, model → screen
 * box projection. Pure utilities — no allocations on the hot path.
 *
 * Note: the old `resizeLetterbox` and `makeDebugPaint` methods were removed
 * (v4 cleanup) — the letterbox is now inlined inside [com.webstrike.aimbotpro.detection.YoloDetector]
 * which reuses its destination bitmap across frames, and the paint factory
 * is duplicated (more efficiently) by [com.webstrike.aimbotpro.overlay.ModMenuTheme].
 */
object BitmapUtils {

    /**
     * Convert a YOLO detection rect (in model input coords) back to screen coords.
     *
     * Reverses the letter-box transform applied to the source bitmap before
     * inference: the model receives a square `modelInputSize × modelInputSize`
     * image where the source screen crop is centered with black bars on two
     * sides. The longest screen axis becomes the model axis; the other axis
     * gets the letterbox offset.
     *
     * ```
     * Screen (portrait, e.g. 1080×1920)        Model input (square, e.g. 640×640)
     * ┌───────────────────┐                    ┌───────────────────┐  ─┬─
     * │                   │                    ▓ black bar (top)   ▓  │ offY
     * │   screen crop     │  ──── scale ──►    ├───────────────────┤   ─┴─
     * │  (1080 × ~1707    │                    │                   │
     * │   after scaling)  │                    │   screen region   │  ← height = 640
     * │                   │                    │   (640 × ~1013)   │
     * │                   │                    │                   │
     * │                   │                    ├───────────────────┤   ─┬─
     * │                   │                    ▓ black bar (bot)   ▓  │ offY
     * └───────────────────┘                    └───────────────────┘  ─┴─
     *                                          ← width = 640  →
     * ```
     *
     * Inverse transform: `screenMax = max(screenW, screenH)`,
     * `scale = screenMax / modelInputSize`, edges offset by `(screenMax -
     * screenW/2) / 2`. The clamp keeps the box within the screen rect.
     */
    fun mapBoxToScreen(
        box: RectF,
        modelInputSize: Int,
        screenWidth: Int,
        screenHeight: Int
    ): RectF {
        val screenMax = kotlin.math.max(screenWidth, screenHeight).toFloat()
        if (modelInputSize <= 0 || screenMax <= 0f) {
            return RectF(0f, 0f, 0f, 0f)
        }
        val scale = screenMax / modelInputSize
        val offX = (screenMax - screenWidth) / 2f
        val offY = (screenMax - screenHeight) / 2f
        val sw = screenWidth.toFloat()
        val sh = screenHeight.toFloat()
        return RectF(
            (box.left * scale - offX).coerceIn(0f, sw),
            (box.top * scale - offY).coerceIn(0f, sh),
            (box.right * scale - offX).coerceIn(0f, sw),
            (box.bottom * scale - offY).coerceIn(0f, sh)
        )
    }
}
