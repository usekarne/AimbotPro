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
     * Greedy non-max suppression.
     *
     * Sorts [detections] by confidence (descending), iteratively keeps the
     * top-scoring box and drops every other box whose IoU with it exceeds
     * [iouThreshold]. O(n^2) worst case, fine for typical N (<= 8400).
     *
     * Returns the kept subset in confidence-descending order.
     */
    fun nms(detections: List<Detection>, iouThreshold: Float): List<Detection> {
        if (detections.size <= 1) return detections
        val remaining = detections.sortedByDescending { it.confidence }.toMutableList()
        val kept = ArrayList<Detection>(remaining.size)
        while (remaining.isNotEmpty()) {
            val best = remaining.removeAt(0)
            kept.add(best)
            val iter = remaining.iterator()
            while (iter.hasNext()) {
                val d = iter.next()
                if (iou(best.box, d.box) >= iouThreshold) {
                    iter.remove()
                }
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
