package com.webstrike.aimbotpro.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.webstrike.aimbotpro.config.FeatureFlags
import com.webstrike.aimbotpro.detection.Detection
import com.webstrike.aimbotpro.utils.BitmapUtils
import com.webstrike.aimbotpro.utils.Logger
import java.util.Locale

/**
 * Detection class-id conventions (mirror [com.webstrike.aimbotpro.detection.Detection]):
 *  - 0  → COCO 'person' (the aimbot's primary target — painted as enemy)
 *  - -1 → demo-mode simulated detection (painted as ally / neutral)
 *
 * Defined locally rather than on Detection itself to keep the detection package
 * free of presentation concerns.
 */
private const val CLASS_PERSON = 0
private const val CLASS_DEMO = -1

/**
 * Pure-drawing full-screen overlay: ESP boxes, ESP lines, ESP distance text,
 * ESP labels, FOV circle, and crosshair.
 *
 * Extends [View] (NOT [android.view.ViewGroup]) — no children, no layout
 * passes, no measurement overhead. All state is pushed from the [Engine][com.webstrike.aimbotpro.core.Engine]
 * via the public setters and rendered in [onDraw].
 *
 * Thread-safety: setters may be called from the inference coroutine
 * ([kotlinx.coroutines.Dispatchers.Default]); state is bundled into an
 * immutable [FrameState] snapshot swapped atomically under [stateLock].
 * [android.view.View.invalidate] is avoided in favour of [postInvalidateOnAnimation]
 * which is safe from any thread.
 *
 * ## Allocation profile (v4)
 *
 * The old `setDetections` allocated 3 `ArrayList`s per call (~150 small
 * allocations per frame at 50+ detections). The new implementation reuses
 * the lists across calls via `clear()` + `add()`, eliminating per-frame
 * allocation in the steady state.
 */
class OverlayRenderer(context: Context) : View(context) {

    // ---------- Cached paints (allocated ONCE in init, never in onDraw) ----------

    private val density: Float = context.resources.displayMetrics.density

    private val boxStrokeWidthPx: Float = BOX_STROKE_DP * density

    private val enemyBoxPaint: Paint = ModMenuTheme.paint(ModMenuTheme.ENEMY, boxStrokeWidthPx, Paint.Style.STROKE)
    private val allyBoxPaint: Paint = ModMenuTheme.paint(ModMenuTheme.ALLY, boxStrokeWidthPx, Paint.Style.STROKE)
    private val targetBoxPaint: Paint = ModMenuTheme.paint(ModMenuTheme.TARGET, boxStrokeWidthPx, Paint.Style.STROKE)

    private val linePaint: Paint = ModMenuTheme.paint(ModMenuTheme.TEXT, 1.5f * density, Paint.Style.STROKE).apply {
        alpha = 110 // faint tracer line
    }

    private val crosshairPaint: Paint = ModMenuTheme.paint(ModMenuTheme.CROSSHAIR, 2f * density, Paint.Style.STROKE)

    private val fovCirclePaint: Paint = ModMenuTheme.paint(ModMenuTheme.FOV, 2f * density, Paint.Style.STROKE).apply {
        alpha = 200
    }

    private val fovFillPaint: Paint = ModMenuTheme.paint(ModMenuTheme.FOV, 0f, Paint.Style.FILL).apply {
        alpha = 18 // very faint tint inside the FOV circle
    }

    private val textPaint: Paint = ModMenuTheme.textPaint(ModMenuTheme.TEXT, 11f * density).apply {
        setShadowLayer(2f * density, 0f, 1f * density, 0xAA000000.toInt())
    }

    private val textBgPaint: Paint = ModMenuTheme.paint(0x80000000.toInt(), 0f, Paint.Style.FILL)

    // ---------- Render state ----------

    /**
     * Immutable snapshot of everything [onDraw] needs. Replaced atomically
     * by the setter methods. [onDraw] takes a single local reference and
     * never touches the @Volatile field more than once per draw.
     *
     * The list fields are reused across snapshots via [swapState] to avoid
     * per-frame allocation — see [setDetections].
     */
    private data class FrameState(
        val boxes: List<RectF> = emptyList(),
        val labels: List<String> = emptyList(),
        val colors: List<Int> = emptyList(),
        val aimTargetBox: RectF? = null,
        val crosshairX: Float = -1f,  // <0 → use view center
        val crosshairY: Float = -1f
    )

    private val stateLock = Any()
    @Volatile
    private var state: FrameState = FrameState()

    /**
     * Reused per-snapshot lists — `setDetections` clears + repopulates these
     * instead of allocating fresh ArrayLists every frame. Guarded by [stateLock].
     */
    private val boxScratch: ArrayList<RectF> = ArrayList(16)
    private val labelScratch: ArrayList<String> = ArrayList(16)
    private val colorScratch: ArrayList<Int> = ArrayList(16)

    // ---------- Public state setters (called from the Engine) ----------

    /**
     * Push a fresh list of detections. Each [Detection.box] is in model-input
     * coords; we project to screen coords via [BitmapUtils.mapBoxToScreen]
     * here so [onDraw] never touches [Detection] (keeps the hot path allocation-free).
     *
     * The colour per detection is pre-computed here (enemy = person classId==0,
     * ally = anything else / demo classId==-1) — the aim-target highlight is
     * resolved in [onDraw] by comparing the latest [setAimTarget] box against
     * the list of screen boxes.
     */
    fun setDetections(detections: List<Detection>, modelSize: Int, screenW: Int, screenH: Int) {
        if (detections.isEmpty()) {
            swapState { it.copy(boxes = emptyList(), labels = emptyList(), colors = emptyList()) }
            scheduleRedraw()
            return
        }
        synchronized(stateLock) {
            boxScratch.clear()
            labelScratch.clear()
            colorScratch.clear()
            for (d in detections) {
                val screenBox = BitmapUtils.mapBoxToScreen(d.box, modelSize, screenW, screenH)
                boxScratch += screenBox
                labelScratch += if (d.label.isNotEmpty()) d.label else "cls#${d.classId}"
                colorScratch += when (d.classId) {
                    CLASS_PERSON -> ModMenuTheme.ENEMY
                    CLASS_DEMO   -> ModMenuTheme.ALLY
                    else         -> ModMenuTheme.ALLY
                }
            }
        }
        // Pass the live scratch lists into the new state. The lists are now
        // owned by the snapshot — the next setDetections call will clear
        // and reuse the same instances via the swapState callback below.
        swapState { it.copy(boxes = boxScratch, labels = labelScratch, colors = colorScratch) }
        scheduleRedraw()
    }

    /**
     * Set the currently-selected aim target's screen-coords box. Pass null
     * to clear the highlight. Comparison against detection boxes in [onDraw]
     * is by value (RectF.equals compares all four edges) — this works
     * because the Engine passes the same [BitmapUtils.mapBoxToScreen]
     * result that was used to build the snapshot.
     */
    fun setAimTarget(box: RectF?) {
        swapState { it.copy(aimTargetBox = box) }
        scheduleRedraw()
    }

    /**
     * Set the crosshair position in screen px. Negative values fall back to
     * the view centre (set lazily in [onDraw] since we need [getWidth]/[getHeight]).
     */
    fun setCrosshairPos(x: Float, y: Float) {
        swapState { it.copy(crosshairX = x, crosshairY = y) }
        scheduleRedraw()
    }

    // ---------- onDraw ----------

    override fun onDraw(canvas: Canvas) {
        try {
            val s = state  // single read of the @Volatile field

            // ---- Crosshair ----
            if (FeatureFlags.crosshairEnabled) {
                val cx = if (s.crosshairX < 0f) width * 0.5f else s.crosshairX
                val cy = if (s.crosshairY < 0f) height * 0.5f else s.crosshairY
                val arm = CROSSHAIR_ARM_DP * density
                canvas.drawLine(cx - arm, cy, cx + arm, cy, crosshairPaint)
                canvas.drawLine(cx, cy - arm, cx, cy + arm, crosshairPaint)
                canvas.drawPoint(cx, cy, crosshairPaint)
            }

            // ---- FOV circle ----
            if (FeatureFlags.fovCircleEnabled) {
                val cx = if (s.crosshairX < 0f) width * 0.5f else s.crosshairX
                val cy = if (s.crosshairY < 0f) height * 0.5f else s.crosshairY
                val r = FeatureFlags.aimFov * density
                if (r > 0f) {
                    canvas.drawCircle(cx, cy, r, fovFillPaint)
                    canvas.drawCircle(cx, cy, r, fovCirclePaint)
                }
            }

            // ---- Detections ----
            val targetBox = s.aimTargetBox
            val n = s.boxes.size
            if (n == 0) return

            for (i in 0 until n) {
                val box = s.boxes[i]
                val isTarget = targetBox != null && targetBox == box

                // ESP box
                if (FeatureFlags.espBoxesEnabled) {
                    val p = if (isTarget) targetBoxPaint
                            else when (s.colors[i]) {
                                ModMenuTheme.ENEMY -> enemyBoxPaint
                                else               -> allyBoxPaint
                            }
                    canvas.drawRect(box, p)
                }

                // ESP line from screen centre to box centre
                if (FeatureFlags.espLinesEnabled) {
                    val cx = if (s.crosshairX < 0f) width * 0.5f else s.crosshairX
                    val cy = if (s.crosshairY < 0f) height * 0.5f else s.crosshairY
                    val bx = (box.left + box.right) * 0.5f
                    val by = (box.top + box.bottom) * 0.5f
                    canvas.drawLine(cx, cy, bx, by, linePaint)
                }

                // ESP distance (heuristic — box width inverse-proportional to range)
                if (FeatureFlags.espDistanceEnabled) {
                    val boxW = box.width()
                    if (boxW > 1f) {
                        val distM = (DISTANCE_K / boxW).coerceIn(DISTANCE_MIN_M, DISTANCE_MAX_M)
                        val label = String.format(Locale.US, "~%dm", distM.toInt())
                        drawLabel(canvas, label, box.left, box.bottom + LABEL_GAP_DP * density)
                    }
                }

                // ESP name (class label)
                if (FeatureFlags.espNamesEnabled) {
                    val label = s.labels[i]
                    if (label.isNotEmpty()) {
                        drawLabel(canvas, label, box.left, box.top - LABEL_GAP_DP * density)
                    }
                }
            }
        } catch (t: Throwable) {
            // Drawing failures must NEVER crash the overlay (would brick the game UI).
            Logger.w(TAG, "onDraw failed: ${t.message}", t)
        }
    }

    // ---------- Helpers ----------

    /**
     * Mutate the [FrameState] atomically. The lambda receives the current
     * snapshot and returns a new one — we never expose a partially-mutated
     * snapshot to the UI thread.
     */
    private inline fun swapState(mutator: (FrameState) -> FrameState) {
        synchronized(stateLock) {
            state = mutator(state)
        }
    }

    /**
     * Schedule a redraw from any thread. [View.invalidate] is UI-thread-only;
     * [postInvalidateOnAnimation] is thread-safe and batches with the next
     * vsync (cheaper than [View.postInvalidate] for the 30–60 FPS render loop).
     */
    private fun scheduleRedraw() {
        if (isAttachedToWindow) {
            postInvalidateOnAnimation()
        }
    }

    /**
     * Draw [text] at ([x], [y]) with a subtle dark backdrop for legibility
     * against any game background. The backdrop is sized to the text width +
     * a small inset.
     */
    private fun drawLabel(canvas: Canvas, text: String, x: Float, y: Float) {
        val baseline = y.coerceAtLeast(textPaint.textSize)
        val w = textPaint.measureText(text)
        val h = textPaint.textSize
        val pad = LABEL_PAD_DP * density
        // backdrop
        canvas.drawRect(x - pad, baseline - h, x + w + pad, baseline + pad * 0.5f, textBgPaint)
        // text
        canvas.drawText(text, x, baseline, textPaint)
    }

    // ---------- Companion ----------

    companion object {
        private const val TAG = "OverlayRenderer"

        private const val BOX_STROKE_DP = 3f          // spec: 3dp stroke for boxes
        private const val CROSSHAIR_ARM_DP = 10f
        private const val LABEL_GAP_DP = 4f
        private const val LABEL_PAD_DP = 3f

        // Distance-estimation heuristic constants (purely visual; not real-world).
        private const val DISTANCE_K = 1000f
        private const val DISTANCE_MIN_M = 2f
        private const val DISTANCE_MAX_M = 99f
    }
}
