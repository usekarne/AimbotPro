package com.webstrike.aimbotpro.detection

import android.graphics.RectF

/**
 * Pure post-processing helpers for [Detection] lists.
 *
 * Stateless — every function is a pure transformation that returns a new list.
 * Safe to call from any thread.
 */
object DetectionProcessor {

    /**
     * Greedy non-max suppression with pre-sort and early termination.
     *
     1. Pre-sorts detections by confidence descending (once) so the
     *    greedy scan naturally picks the best box first.
     * 2. Iteratively keeps the top-scoring box and drops every other box
     *    whose IoU with it exceeds [iouThreshold].
     * 3. Early termination: once the kept count reaches [maxDetections],
     *    the remaining rows are skipped (they would be dropped anyway).
     *
     O(n log n) for sort + O(n * maxDetections) for NMS sweep.
     * For typical inputs (< 100 filtered detections), this is near-instant.
     *
     Returns the kept subset in confidence-descending order.
     */
    fun nms(
        detections: List<Detection>,
        iouThreshold: Float,
        maxDetections: Int = 100
    ): List<Detection> {
        if (detections.size <= 1) return detections

        // Pre-sort by confidence descending — enables the greedy scan to
        // be the optimal solution and allows early termination.
        val sorted = detections.sortedByDescending { it.confidence }

        val kept = ArrayList<Detection>(sorted.size.coerceAtMost(maxDetections))
        val suppressed = BooleanArray(sorted.size) // O(1) contains check


        var keptCount = 0
        for (i in sorted.indices) {
            // Early termination: already have enough kept detections.
            if (keptCount >= maxDetections) break
            val d = sorted[i]
            // Skip already-suppressed boxes.
            if (suppressed[i]) continue

            var shouldSuppress = false
            for (j in 0 until keptCount) {
                if (iou(d.box, kept[j].box) >= iouThreshold) {
                    shouldSuppress = true
                    break
                }
            }
            if (shouldSuppress) {
                suppressed[i] = true
            } else {
                suppressed[i] = false
                kept += d
                keptCount++
            }
        }
        return kept
    }

    /** Sort detections by confidence, descending. Stable. */
    fun sortByConfidence(detections: List<Detection>): List<Detection> =
        detections.sortedByDescending { it.confidence }

    /** Keep only detections with [Detection.confidence] >= [threshold]. */
    fun filterByConfidence(detections: List<Detection>, threshold: Float): List<Detection> =
        detections.filter { it.confidence >= threshold }

    /** Keep only detections whose [Detection.classId] equals [classId]. */
    fun filterByClass(detections: List<Detection>, classId: Int): List<Detection> =
        detections.filter { it.classId == classId }

    /**
     * Intersection-over-Union of two rects. Returns 0 for non-overlapping
     * or zero-area inputs.
     */
    private fun iou(a: RectF, b: RectF): Float {
        val xL = maxOf(a.left, b.left)
        val yT = maxOf(a.top, b.top)
        val xR = minOf(a.right, b.right)
        val yB = minOf(a.bottom, b.bottom)
        val interW = (xR - xL).coerceAtLeast(0f)
        val interH = (yB - yT).coerceAtLeast(0f)
        val interArea = interW * interH
        if (interArea <= 0f) return 0f
        val areaA = a.width() * a.height()
        val areaB = b.width() * b.height()
        val union = areaA + areaB - interArea
        return if (union <= 0f) 0f else interArea / union
    }
}
