package com.webstrike.aimbotpro.detection

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import com.webstrike.aimbotpro.Constants
import com.webstrike.aimbotpro.config.FeatureFlags
import com.webstrike.aimbotpro.utils.Logger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.random.Random

/**
 * Synchronous YOLO inference wrapper.
 *
 * Caller is responsible for running [detect] off the main thread (e.g.
 * inside `Dispatchers.Default`). The class is thread-safe — concurrent
 * callers are serialized on [inferenceLock] because TFLite's [Interpreter]
 * is not re-entrant.
 *
 * Supports two common TFLite export shapes, auto-detected from the output
 * tensor's last dimension:
 *   - **YOLOv8 export**: `[1, N, 6]` — each row is `[cx, cy, w, h, score, classId]`
 *     (NMS already applied by the export).
 *   - **YOLOv5 export**: `[1, N, 5 + numClasses]` — each row is
 *     `[cx, cy, w, h, conf_class_0, conf_class_1, ...]`. We pick the argmax
 *     class. (For the degenerate single-class case `m == 5`, the loop still
 *     picks index 0.)
 *
 * Coordinates from the model are converted to pixel-space [RectF]s in
 * model-input coords (0..[inputSize]); callers use [Detection.mapToScreen]
 * to project back to the actual screen.
 *
 * Demo mode (no model loaded): returns 1–3 simulated detections positioned
 * near horizontal center at 1/4, 1/2, 3/4 input height with ±5 % random
 * jitter and confidence in [0.6, 0.9]. Simulated detections have
 * [Detection.classId] = 0 (the COCO 'person' index) so they pass the
 * downstream class filter — but [Detection.label] = "demo" so the overlay
 * can render them as a distinct colour.
 *
 * @param modelManager the singleton that owns the live [Interpreter].
 */
class YoloDetector(
    private val modelManager: ModelManager
) {
    private val inputSize: Int = Constants.Detection.INPUT_SIZE

    /**
     * Reusable input buffer — 4 bytes per float, NHWC layout
     * `[1, INPUT_SIZE, INPUT_SIZE, 3]`. Avoids per-frame allocation.
     */
    private val inputBuffer: ByteBuffer = ByteBuffer
        .allocateDirect(inputSize * inputSize * 3 * FLOAT_BYTES)
        .order(ByteOrder.nativeOrder())

    /** Float view over [inputBuffer] — used to write normalized pixels directly. */
    private val inputFloats: FloatBuffer = inputBuffer.asFloatBuffer()

    /**
     * Reusable pixel scratch — `IntArray(size*size)` of ARGB pixels read via
     * [Bitmap.getPixels]. Eliminates the per-frame 1.6 MB IntArray allocation
     * that the old `BitmapUtils.bitmapToRgbFloats` caused.
     */
    private val pixelScratch: IntArray = IntArray(inputSize * inputSize)

    /**
     * Reusable resized bitmap — eliminates per-frame allocation in the hot
     * path. Only re-created when the source dimensions change (rare, e.g.
     * orientation change). Accessed under [inferenceLock] so no further
     * synchronization needed.
     */
    @Volatile
    private var resizedBitmap: Bitmap? = null

    /**
     * Reusable output array (only re-allocated when the model's output
     * shape changes — e.g. a hot-swapped model).
     */
    private var cachedOutputArray: Array<Array<FloatArray>>? = null
    private var cachedOutputShape: IntArray? = null

    /** Reused single-element input array — was allocating per frame in v3. */
    private val inputsArray: Array<Any> = arrayOf(inputBuffer)

    /** Reused outputs map — was allocating a fresh HashMap per frame in v3. */
    private val outputsMap: MutableMap<Int, Any> = HashMap(1)

    /** Serializes inference — TFLite Interpreter is not thread-safe. */
    private val inferenceLock = ReentrantLock()

    /**
     * Run YOLO inference on [bitmap] and return the post-NMS detections.
     * Always returns a list (possibly empty). In demo mode returns simulated
     * detections.
     */
    fun detect(bitmap: Bitmap): List<Detection> = inferenceLock.withLock {
        if (modelManager.isDemoMode()) {
            return@withLock generateDemoDetections()
        }

        val interpreter = modelManager.getInterpreter()
            ?: return@withLock generateDemoDetections()
        val labels = modelManager.getLabels()

        // 1. Resize input bitmap to model input size (letter-boxed to black).
        //    Reuse a single destination bitmap across frames to avoid GC churn.
        val resized = acquireResizedBitmap()

        // Draw source into the letterboxed canvas — clears to black first.
        val canvas = Canvas(resized)
        canvas.drawColor(Color.BLACK)
        val srcW = bitmap.width.toFloat()
        val srcH = bitmap.height.toFloat()
        val scale = inputSize.toFloat() / kotlin.math.max(srcW, srcH)
        val scaledW = (srcW * scale).toInt().coerceAtLeast(1)
        val scaledH = (srcH * scale).toInt().coerceAtLeast(1)
        val dx = (inputSize - scaledW) / 2f
        val dy = (inputSize - scaledH) / 2f
        val dst = RectF(dx, dy, dx + scaledW, dy + scaledH)
        canvas.drawBitmap(bitmap, null, dst, null)

        // 2. Convert directly into inputBuffer — no intermediate FloatArray.
        //    bitmap.getPixels -> pixelScratch, then write normalized RGB
        //    straight into inputFloats. Saves ~6.5 MB/frame of allocation.
        resized.getPixels(pixelScratch, 0, inputSize, 0, 0, inputSize, inputSize)
        inputFloats.rewind()
        val n = pixelScratch.size
        var fi = 0
        for (i in 0 until n) {
            val px = pixelScratch[i]
            inputFloats.put(fi, ((px shr 16) and 0xFF) / 255f)
            inputFloats.put(fi + 1, ((px shr 8) and 0xFF) / 255f)
            inputFloats.put(fi + 2, (px and 0xFF) / 255f)
            fi += 3
        }
        inputBuffer.rewind()  // Position back to 0 for the TFLite read.

        // 3. Read output shape & (re)allocate the output array if needed.
        val outputShape = interpreter.getOutputTensor(0).shape()
        if (outputShape.size < 3 || outputShape[1] <= 0 || outputShape[2] <= 0) {
            Logger.w(TAG, "Unexpected output tensor shape: ${outputShape.toList()}")
            return@withLock Detection.empty()
        }
        val nDet = outputShape[1]
        val m = outputShape[2]
        val outputArray = acquireOutputArray(outputShape, nDet, m)

        // 4. Run inference — reuse the cached inputs/outputs containers.
        outputsMap.clear()
        outputsMap[0] = outputArray
        try {
            interpreter.runForMultipleInputsOutputs(inputsArray, outputsMap)
        } catch (t: Throwable) {
            Logger.e(TAG, "Inference failed", t)
            return@withLock Detection.empty()
        }

        // 5. Parse output, dispatching on last-dim (v8 vs v5 export shape).
        val raw = parseOutput(outputArray[0], nDet, m, labels)
        if (raw.isEmpty()) return@withLock Detection.empty()

        // 6. Filter by confidence & person class, then NMS, then cap.
        //    Demo mode is handled above (returns early), so we always filter
        //    to the human class here. Demo detections now use classId=0 so
        //    they would pass too — but we never reach this code path for demos.
        val confThreshold = FeatureFlags.minConfidence
        val iouThreshold = Constants.Detection.DEFAULT_IOU_THRESHOLD
        val targetClass = Constants.Detection.HUMAN_CLASS_INDEX // COCO 'person'

        val filtered = ArrayList<Detection>(raw.size)
        for (d in raw) {
            if (d.confidence < confThreshold) continue
            if (d.classId != targetClass) continue
            filtered += d
        }

        val suppressed = DetectionProcessor.nms(filtered, iouThreshold)
        if (suppressed.size > Constants.Detection.DEFAULT_MAX_DETECTIONS) {
            suppressed.subList(0, Constants.Detection.DEFAULT_MAX_DETECTIONS)
        }
        suppressed
    }

    /**
     * Returns the cached output array when [shape] matches, else allocates
     * a fresh `[1][n][m]` FloatArray and caches it.
     */
    private fun acquireOutputArray(
        shape: IntArray,
        n: Int,
        m: Int
    ): Array<Array<FloatArray>> {
        val cachedShape = cachedOutputShape
        val cached = cachedOutputArray
        if (cached != null && cachedShape != null && cachedShape.contentEquals(shape)) {
            return cached
        }
        val fresh = Array(1) { Array(n) { FloatArray(m) } }
        cachedOutputArray = fresh
        cachedOutputShape = shape
        return fresh
    }

    /**
     * Convert raw output rows into [Detection]s. Dispatches on last-dim size:
     *   - `m == 6` → YOLOv8 export: row is `[cx, cy, w, h, score, classId]`
     *   - `m >= 5` → YOLOv5 export: row is `[cx, cy, w, h, conf_per_class...]`
     *               (single-class v5 with `m == 5` is handled by the same path)
     *   - else   → row skipped.
     *
     * Box coords are clamped to `[0, inputSize]`.
     */
    private fun parseOutput(
        rows: Array<FloatArray>,
        n: Int,
        m: Int,
        labels: List<String>
    ): List<Detection> {
        val out = ArrayList<Detection>(n)
        for (i in 0 until n) {
            val row = rows[i]

            val cx = row[0]
            val cy = row[1]
            val w = row[2]
            val h = row[3]
            if (w.isNaN() || h.isNaN() || w <= 0f || h <= 0f) continue

            when {
                m == 6 -> {
                    // YOLOv8 export: score at idx 4, classId at idx 5
                    val score = row[4]
                    if (score.isNaN()) continue
                    val rawClassId = row[5]
                    if (rawClassId.isNaN()) continue
                    val classId = rawClassId.toInt()
                    out.add(buildDetection(cx, cy, w, h, score, classId, labels))
                }
                m >= 5 -> {
                    // YOLOv5 export: pick best class score among idx 4..m-1
                    var bestScore = 0f
                    var bestClass = -1
                    for (c in 4 until m) {
                        val s = row[c]
                        if (s.isNaN()) continue
                        if (s > bestScore) {
                            bestScore = s
                            bestClass = c - 4
                        }
                    }
                    if (bestClass < 0) continue
                    out.add(buildDetection(cx, cy, w, h, bestScore, bestClass, labels))
                }
                else -> continue
            }
        }
        return out
    }

    private fun buildDetection(
        cx: Float, cy: Float, w: Float, h: Float,
        score: Float, classId: Int, labels: List<String>
    ): Detection {
        val left = (cx - w * 0.5f).coerceAtLeast(0f)
        val top = (cy - h * 0.5f).coerceAtLeast(0f)
        val right = (cx + w * 0.5f).coerceAtMost(inputSize.toFloat())
        val bottom = (cy + h * 0.5f).coerceAtMost(inputSize.toFloat())
        val label = labels.getOrNull(classId) ?: "class_$classId"
        return Detection(
            classId = classId,
            confidence = score,
            box = RectF(left, top, right, bottom),
            label = label
        )
    }

    /**
     * Generate 1–3 simulated detections centered horizontally at 1/4, 1/2, 3/4
     * of input height. Boxes get ±5 % jitter and confidence in [0.6, 0.9].
     *
     * Demo detections use `classId = 0` (COCO 'person') so the downstream
     * class filter passes them through — this fixes a v3 bug where demo mode
     * silently produced zero visible detections. The `label = "demo"` marker
     * lets the overlay render them in a distinct colour so the user can tell
     * they are simulated.
     */
    private fun generateDemoDetections(): List<Detection> {
        val count = Random.nextInt(1, 4) // 1..3 inclusive
        val out = ArrayList<Detection>(count)
        val size = inputSize.toFloat()
        val yAnchors = floatArrayOf(0.25f, 0.5f, 0.75f)
        for (i in 0 until count) {
            val yBase = yAnchors[i]
            val jitterX = (Random.nextFloat() - 0.5f) * 0.1f * size // ±5 %
            val jitterY = (Random.nextFloat() - 0.5f) * 0.1f * size // ±5 %
            val cx = size * 0.5f + jitterX
            val cy = size * yBase + jitterY
            val boxW = size * 0.18f
            val boxH = size * 0.28f
            val left = (cx - boxW * 0.5f).coerceAtLeast(0f)
            val top = (cy - boxH * 0.5f).coerceAtLeast(0f)
            val right = (cx + boxW * 0.5f).coerceAtMost(size)
            val bottom = (cy + boxH * 0.5f).coerceAtMost(size)
            val conf = 0.6f + Random.nextFloat() * 0.3f // 0.6..0.9
            out.add(
                Detection(
                    classId = 0,  // COCO 'person' — passes the downstream filter
                    confidence = conf,
                    box = RectF(left, top, right, bottom),
                    label = "demo"  // overlay renders demo detections as ally/neutral
                )
            )
        }
        return out
    }

    /**
     * Lazily allocate the reused resized bitmap. Caller holds [inferenceLock]
     * so no extra synchronization needed.
     */
    private fun acquireResizedBitmap(): Bitmap {
        val current = resizedBitmap
        if (current != null && !current.isRecycled &&
            current.width == inputSize && current.height == inputSize
        ) {
            return current
        }
        val fresh = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
        resizedBitmap = fresh
        return fresh
    }

    /**
     * Release native resources held by this detector. Call when the engine
     * is being torn down. Safe to call multiple times.
     */
    fun release() {
        inferenceLock.withLock {
            runCatching {
                resizedBitmap?.let { if (!it.isRecycled) it.recycle() }
            }
            resizedBitmap = null
            cachedOutputArray = null
            cachedOutputShape = null
            outputsMap.clear()
        }
    }

    companion object {
        private const val TAG = "YoloDetector"
        private const val FLOAT_BYTES = 4
    }
}
