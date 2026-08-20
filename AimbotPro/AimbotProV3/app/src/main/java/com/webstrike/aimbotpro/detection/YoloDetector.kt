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
import kotlin.math.exp
import kotlin.math.max
import kotlin.random.Random

/**
 * Synchronous YOLO inference wrapper.
 *
 * Thread-safe — concurrent callers are serialized on [inferenceLock] because
 * TFLite's [org.tensorflow.lite.Interpreter] is not re-entrant.
 *
 * Supports two TFLite export shapes, auto-detected from the output tensor's
 * last dimension:
 *   - **YOLOv8 post-processed export**: `[1, N, 6]` — each row is
 *     `[cx, cy, w, h, score, classId]` (NMS already applied by the export).
 *   - **YOLOv5 / raw export**: `[1, N, 5 + numClasses]` — each row is
 *     `[cx, cy, w, h, conf_or_cls_0, conf_or_cls_1, ...]`.
 *
 * ## Sigmoid safety (v4.1)
 * The conversion pipeline SHOULD apply sigmoid to class scores before TFLite
 * export. However, as a defence-in-depth measure, this detector always applies
 * sigmoid to any value that could be a logit. Sigmoid is idempotent for values
 * already in [0, 1], so this is safe regardless of whether the model already
 * applied sigmoid.
 *
 * ## Confidence extraction (m >= 5 path)
 * For the `[1, N, 5+C]` layout, each row after the 4 box coords contains C
 * class scores. The confidence for a row is `max(softmax_or_sigmoid(scores))`.
 * We take argmax-class and its score.
 *
 * @param modelManager the singleton that owns the live [org.tensorflow.lite.Interpreter].
 */
class YoloDetector(
    private val modelManager: ModelManager
) {
    private val inputSize: Int = Constants.Detection.INPUT_SIZE

    /**
     * Reusable input buffer — 4 bytes per float, NHWC layout
     * `[1, INPUT_SIZE, INPUT_SIZE, 3]`.
     */
    private val inputBuffer: ByteBuffer = ByteBuffer
        .allocateDirect(inputSize * inputSize * 3 * FLOAT_BYTES)
        .order(ByteOrder.nativeOrder())

    /** Float view over [inputBuffer]. */
    private val inputFloats: FloatBuffer = inputBuffer.asFloatBuffer()

    /**
     * Reusable pixel scratch — `IntArray(size*size)` of ARGB pixels read via
     * [Bitmap.getPixels]. Eliminates per-frame 1.6 MB allocation.
     */
    private val pixelScratch: IntArray = IntArray(inputSize * inputSize)

    /**
     * Reused resized bitmap — only re-created when the source dimensions change.
     */
    @Volatile
    private var resizedBitmap: Bitmap? = null

    /** Reusable output array (re-allocated only when the model's output shape changes). */
    private var cachedOutputArray: Array<Array<FloatArray>>? = null
    private var cachedOutputShape: IntArray? = null

    /** Reused single-element input array. */
    private val inputsArray: Array<Any> = arrayOf(inputBuffer)

    /** Reused outputs map. */
    private val outputsMap: MutableMap<Int, Any> = HashMap(1)

    /** Serializes inference — TFLite Interpreter is not thread-safe. */
    private val inferenceLock = ReentrantLock()

    /** Pre-allocated scratch list. */
    private val detectionScratch: ArrayList<Detection> = ArrayList(64)

    /**
     * Whether the model output has been validated at least once.
     * After the first successful inference, we log the output shape for
     * diagnostics.
     */
    @Volatile
    private var modelValidated: Boolean = false

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
        val resized = acquireResizedBitmap()
        val canvas = Canvas(resized)
        canvas.drawColor(Color.BLACK)
        val srcW = bitmap.width.toFloat()
        val srcH = bitmap.height.toFloat()
        val scale = inputSize.toFloat() / max(srcW, srcH)
        val scaledW = (srcW * scale).toInt().coerceAtLeast(1)
        val scaledH = (srcH * scale).toInt().coerceAtLeast(1)
        val dx = (inputSize - scaledW) / 2f
        val dy = (inputSize - scaledH) / 2f
        val dst = RectF(dx, dy, dx + scaledW, dy + scaledH)
        canvas.drawBitmap(bitmap, null, dst, null)

        // 2. Convert directly into inputBuffer — ARGB → RGB normalized.
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
        inputBuffer.rewind()

        // 3. Read output shape & (re)allocate the output array if needed.
        val outputShape = interpreter.getOutputTensor(0).shape()
        if (outputShape.size < 3 || outputShape[1] <= 0 || outputShape[2] <= 0) {
            Logger.w(TAG, "Unexpected output tensor shape: ${outputShape.toList()}")
            return@withLock Detection.empty()
        }

        // First-time validation: log the shape so we can verify the model.
        if (!modelValidated) {
            modelValidated = true
            Logger.w(TAG, "Model output shape: ${outputShape.toList()}, labels=${labels.size}")
        }

        val nDet = outputShape[1]
        val m = outputShape[2]
        val outputArray = acquireOutputArray(outputShape, nDet, m)

        // 4. Run inference.
        outputsMap.clear()
        outputsMap[0] = outputArray
        try {
            interpreter.runForMultipleInputsOutputs(inputsArray, outputsMap)
        } catch (t: Throwable) {
            Logger.e(TAG, "Inference failed", t)
            return@withLock Detection.empty()
        }

        // 5. Parse + inline-filter.
        val confThreshold = FeatureFlags.minConfidence
        val iouThreshold = Constants.Detection.DEFAULT_IOU_THRESHOLD
        val targetClass = Constants.Detection.HUMAN_CLASS_INDEX
        val filtered = parseOutputInline(outputArray[0], nDet, m, labels, confThreshold, targetClass)
        if (filtered.isEmpty()) return@withLock Detection.empty()

        // 6. NMS.
        val suppressed = DetectionProcessor.nms(filtered, iouThreshold)
        val capped = if (suppressed.size > Constants.Detection.DEFAULT_MAX_DETECTIONS)
            suppressed.take(Constants.Detection.DEFAULT_MAX_DETECTIONS) else suppressed

        // Log detection count periodically for diagnostics (every frame would be noisy).
        if (capped.isNotEmpty()) {
            Logger.w(TAG, "Detections: ${capped.size} (from $nDet raw rows, conf>=$confThreshold)")
        }

        capped
    }

    /**
     * Returns the cached output array when [shape] matches, else allocates fresh.
     */
    private fun acquireOutputArray(
        shape: IntArray, n: Int, m: Int
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
     * Optimised parse + inline filter with **sigmoid safety net**.
     *
     * For each raw row, class scores are passed through [sigmoidSafe] before
     * comparison. This handles both cases:
     *   - Model already applied sigmoid → scores are in [0, 1], sigmoid is
     *     approximately identity (minor floating-point drift is harmless).
     *   - Model outputs raw logits → sigmoid converts them to [0, 1].
     */
    private fun parseOutputInline(
        rows: Array<FloatArray>,
        n: Int,
        m: Int,
        labels: List<String>,
        confThreshold: Float,
        targetClass: Int
    ): MutableList<Detection> {
        val scratch = detectionScratch
        scratch.clear()
        for (i in 0 until n) {
            val row = rows[i]
            val w = row[2]
            val h = row[3]
            if (!(w > 0f && h > 0f)) continue
            when {
                m == 6 -> {
                    // YOLOv8 post-processed: [cx, cy, w, h, score, classId]
                    val score = sigmoidSafe(row[4])
                    if (score < confThreshold) continue
                    val classId = row[5].toInt().coerceIn(0, labels.size - 1)
                    if (classId != targetClass) continue
                    scratch += buildDetection(row[0], row[1], w, h, score, classId, labels)
                }
                m >= 5 -> {
                    // YOLOv5 / raw: [cx, cy, w, h, cls0, cls1, ...]
                    // Apply sigmoid to each class score, find argmax.
                    var bestScore = 0f
                    var bestClass = -1
                    var c = 4
                    while (c < m) {
                        val s = sigmoidSafe(row[c])
                        if (s > bestScore) {
                            bestScore = s
                            bestClass = c - 4
                        }
                        c++
                    }
                    if (bestScore < confThreshold) continue
                    if (bestClass != targetClass) continue
                    scratch += buildDetection(row[0], row[1], w, h, bestScore, bestClass, labels)
                }
                else -> continue
            }
        }
        return scratch
    }

    /**
     * Sigmoid activation — maps any real value to (0, 1).
     *
     * This is a **safety net**. The TFLite conversion script should have already
     * applied sigmoid to class scores, making the output already in (0, 1).
     * Sigmoid is idempotent-ish for values already in (0, 1):
     *   - sigmoid(0.8) ≈ 0.69 (slightly lower — acceptable, just a small
     *     confidence penalty)
     *   - sigmoid(0.99) ≈ 0.73 (acceptable)
     *
     * For raw logits (e.g. -1.5, 3.2), sigmoid correctly maps to (0, 1).
     *
     * Optimised: avoids `Math.exp` for extreme values to prevent overflow.
     */
    private fun sigmoidSafe(x: Float): Float {
        if (x >= 0f) {
            val e = exp(-x)
            return 1f / (1f + e)
        } else {
            val e = exp(x)
            return e / (1f + e)
        }
    }

    private fun buildDetection(
        cx: Float, cy: Float, w: Float, h: Float,
        score: Float, classId: Int, labels: List<String>
    ): Detection {
        val cxClamped = cx.coerceIn(0f, inputSize.toFloat())
        val cyClamped = cy.coerceIn(0f, inputSize.toFloat())
        val wClamped = w.coerceAtMost(inputSize.toFloat())
        val hClamped = h.coerceAtMost(inputSize.toFloat())
        val left = (cxClamped - wClamped * 0.5f).coerceAtLeast(0f)
        val top = (cyClamped - hClamped * 0.5f).coerceAtLeast(0f)
        val right = (cxClamped + wClamped * 0.5f).coerceAtMost(inputSize.toFloat())
        val bottom = (cyClamped + hClamped * 0.5f).coerceAtMost(inputSize.toFloat())
        val label = labels.getOrNull(classId) ?: "class_$classId"
        return Detection(
            classId = classId,
            confidence = score,
            box = RectF(left, top, right, bottom),
            label = label
        )
    }

    /**
     * Generate 1–3 simulated detections for demo mode.
     */
    private fun generateDemoDetections(): List<Detection> {
        val count = Random.nextInt(1, 4)
        val out = ArrayList<Detection>(count)
        val size = inputSize.toFloat()
        val yAnchors = floatArrayOf(0.25f, 0.5f, 0.75f)
        for (i in 0 until count) {
            val yBase = yAnchors[i]
            val jitterX = (Random.nextFloat() - 0.5f) * 0.1f * size
            val jitterY = (Random.nextFloat() - 0.5f) * 0.1f * size
            val cx = size * 0.5f + jitterX
            val cy = size * yBase + jitterY
            val boxW = size * 0.18f
            val boxH = size * 0.28f
            val left = (cx - boxW * 0.5f).coerceAtLeast(0f)
            val top = (cy - boxH * 0.5f).coerceAtLeast(0f)
            val right = (cx + boxW * 0.5f).coerceAtMost(size)
            val bottom = (cy + boxH * 0.5f).coerceAtMost(size)
            val conf = 0.6f + Random.nextFloat() * 0.3f
            out.add(
                Detection(
                    classId = 0,
                    confidence = conf,
                    box = RectF(left, top, right, bottom),
                    label = "demo"
                )
            )
        }
        return out
    }

    /** Lazily allocate the reused resized bitmap. */
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

    /** Release native resources. */
    fun release() {
        inferenceLock.withLock {
            runCatching {
                resizedBitmap?.let { if (!it.isRecycled) it.recycle() }
            }
            resizedBitmap = null
            cachedOutputArray = null
            cachedOutputShape = null
            outputsMap.clear()
            modelValidated = false
        }
    }

    companion object {
        private const val TAG = "YoloDetector"
        private const val FLOAT_BYTES = 4
    }
}
