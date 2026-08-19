package com.webstrike.aimbotpro.core

import android.content.Context
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import com.webstrike.aimbotpro.Constants
import com.webstrike.aimbotpro.aim.AimCalculator
import com.webstrike.aimbotpro.aim.AimSmoother
import com.webstrike.aimbotpro.aim.TargetSelector
import com.webstrike.aimbotpro.aim.TriggerBot
import com.webstrike.aimbotpro.capture.FrameBuffer
import com.webstrike.aimbotpro.capture.ScreenCaptureManager
import com.webstrike.aimbotpro.config.FeatureFlags
import com.webstrike.aimbotpro.detection.Detection
import com.webstrike.aimbotpro.detection.YoloDetector
import com.webstrike.aimbotpro.input.InputInjector
import com.webstrike.aimbotpro.input.TouchSimulator
import com.webstrike.aimbotpro.overlay.ModMenuController
import com.webstrike.aimbotpro.perf.Telemetry
import com.webstrike.aimbotpro.utils.Logger
import com.webstrike.aimbotpro.utils.PerformanceMonitor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/**
 * Main orchestration loop. Ties the capture → detection → aim → input → overlay
 * pipeline into a single coroutine driven from a caller-supplied
 * [CoroutineScope] (typically the service's [Dispatchers.Default] scope).
 *
 * ## Lifecycle
 *  - [start] launches the loop coroutine and resets collaborator state.
 *  - [stop]  cancels the loop coroutine **asynchronously** and clears all
 *    per-frame state. The optional [onStopped] callback is invoked exactly
 *    once when the coroutine has fully drained — use it to chain dependent
 *    teardown (e.g. recycling capture bitmaps) without blocking the caller.
 *
 * ## Threading
 *  - The loop body runs inside a single coroutine — all collaborators
 *    ([TargetSelector], [AimSmoother], [AimCalculator], [TriggerBot]) are
 *    accessed from that one coroutine, honouring their documented
 *    single-thread contract.
 *  - [TouchSimulator] is constructed with a [Handler] backed by the main
 *    [Looper]; touch dispatch is bounced through the UI thread's message
 *    queue (the underlying `AccessibilityService.dispatchGesture` is itself
 *    asynchronous and never blocks).
 *
 * ## Robustness contract
 *  - Every iteration of the loop body is wrapped in a try/catch that logs
 *    and swallows any [Exception] — the service is never crashed by an
 *    inference / aim / overlay failure.
 *  - [OutOfMemoryError] and other [Error]s are re-thrown so the JVM can
 *    die cleanly instead of death-spiralling.
 *  - A [CoroutineExceptionHandler] catches anything that escapes the
 *    loop body (e.g. dispatcher unavailable) and logs it.
 *
 * @param context            host context (the service). Used to read live
 *                           [android.util.DisplayMetrics] each frame so
 *                           orientation / display-mode changes are picked
 *                           up automatically.
 * @param detector           the per-session [YoloDetector].
 * @param capture            the per-session [ScreenCaptureManager]. The
 *                           Engine does NOT directly drive capture; the
 *                           reference is kept for future use (e.g.
 *                           dynamically re-sizing the capture surface on
 *                           orientation change).
 * @param frameBuffer        the SPSC frame buffer shared with capture.
 * @param inputInjector      the [InputInjector] singleton.
 * @param overlayController  the per-session [ModMenuController].
 */
class Engine(
    private val context: Context,
    private val detector: YoloDetector,
    @Suppress("unused")
    private val capture: ScreenCaptureManager,
    private val frameBuffer: FrameBuffer,
    private val inputInjector: InputInjector,
    private val overlayController: ModMenuController
) {

    /** Synchronizes [start] / [stop] against each other. */
    private val lifecycleLock = Any()

    @Volatile
    var running: Boolean = false
        private set

    /** Cumulative session stats — exposed for the service / notification. */
    val pipeline: Pipeline = Pipeline()

    /**
     * Active loop coroutine — cancelled in [stop]. Reads are guarded by
     * [lifecycleLock]; the field itself is `@Volatile` so the cancel path
     * can do a cheap null-check before acquiring the lock.
     */
    @Volatile
    private var loopJob: Job? = null

    /**
     * Optional one-shot callback fired after [stop] completes. Set under
     * [lifecycleLock] in [stop]; cleared after invocation so a subsequent
     * [start] / [stop] cycle can install a new one.
     */
    private val stoppedCallback = AtomicReference<((Boolean) -> Unit)?>()

    // ---------- Collaborators (lazy-built in [start], torn down in [stop]) ----------

    private var targetSelector: TargetSelector? = null
    private var smoother: AimSmoother? = null
    private var aimCalculator: AimCalculator? = null
    private var triggerBot: TriggerBot? = null
    private var touchHandler: Handler? = null
    private var touchSimulator: TouchSimulator? = null

    /**
     * Optional heartbeat callback — invoked on every successful frame.
     * Used by [com.webstrike.aimbotpro.perf.EngineWatchdog] to monitor
     * inference-loop liveness. Set via [setHeartbeatCallback]; cleared in
     * [performPostLoopTeardown].
     */
    @Volatile
    private var heartbeatCallback: (() -> Unit)? = null

    /** Register a heartbeat callback (used by the watchdog). */
    fun setHeartbeatCallback(cb: (() -> Unit)?) {
        heartbeatCallback = cb
    }

    /**
     * Last frame's monotonic time, used to derive the inter-frame `dt`.
     * Uses [android.os.SystemClock.elapsedRealtimeNanos] for monotonicity.
     */
    @Volatile
    private var lastFrameTimeNs: Long = 0L

    /**
     * Cached screen metrics — read once per frame but stored so a missed
     * read doesn't trigger an NPE in the aim hot path. Updated in [runOnce].
     */
    @Volatile
    private var cachedScreenW: Int = 0
    @Volatile
    private var cachedScreenH: Int = 0

    /** Reused PointF to avoid per-frame allocation of [screenCenter]. */
    private val screenCenter: PointF = PointF()

    /** Reused output buffer for [AimSmoother.smooth] to avoid per-frame FloatArray. */
    private val smootherOutput: FloatArray = FloatArray(2)

    /** Reused list for screen-coord detections to avoid per-frame ArrayList alloc. */
    private val screenDetections: MutableList<Detection> = ArrayList(16)

    // ---------- Public API ----------

    /**
     * Launch the main loop in [scope]. Idempotent — a second call while
     * already running is a logged no-op. Returns `true` if the loop was
     * started, `false` if it was already running or construction failed.
     */
    fun start(scope: CoroutineScope): Boolean {
        synchronized(lifecycleLock) {
            if (running) {
                Logger.w(TAG, "start() called while already running — ignoring")
                return false
            }

            // Build collaborators (wrapped so a failure here doesn't leave the
            // Engine in a half-initialised state).
            try {
                val handler = Handler(Looper.getMainLooper())
                touchHandler = handler
                val sim = TouchSimulator(handler)
                touchSimulator = sim
                val sm = AimSmoother()
                smoother = sm
                targetSelector = TargetSelector()
                aimCalculator = AimCalculator(inputInjector, sim, sm)
                triggerBot = TriggerBot(sim)
            } catch (t: Throwable) {
                Logger.e(TAG, "Collaborator construction failed: ${t.message}", t)
                touchSimulator = null
                touchHandler = null
                smoother = null
                targetSelector = null
                aimCalculator = null
                triggerBot = null
                return false
            }

            lastFrameTimeNs = 0L

            // Install the loop job BEFORE flipping `running` so [stop] cannot
            // observe running=true with loopJob=null (which previously caused
            // an un-cancellable orphaned coroutine on a fast START→STOP).
            val handler = CoroutineExceptionHandler { _, t ->
                if (t is CancellationException) {
                    // Cooperative cancellation — expected during stop(). Don't log.
                    return@CoroutineExceptionHandler
                }
                Logger.e(TAG, "Engine coroutine crashed: ${t.message}", t)
            }
            loopJob = scope.launch(Dispatchers.Default + handler) {
                Logger.i(TAG, "Engine loop started")
                try {
                    while (isActive) {
                        try {
                            runOnce()
                        } catch (ce: CancellationException) {
                            // Cooperative cancellation — propagate, do NOT swallow.
                            throw ce
                        } catch (oom: OutOfMemoryError) {
                            // OOM must NOT be swallowed — the JVM is in an
                            // unrecoverable state and continuing would death-spiral.
                            Logger.e(TAG, "OOM in runOnce — aborting loop", oom)
                            Telemetry.event(Telemetry.Engine.FRAME_FAILED, "OOM")
                            throw oom
                        } catch (e: Exception) {
                            // Any other Exception is logged and the loop continues.
                            Logger.w(TAG, "Frame failed: ${e.message}", e)
                            Telemetry.count(Telemetry.Engine.FRAME_FAILED)
                        }
                        // Sleep to maintain the target frame rate.
                        val frameDelayMs = (1000L / Constants.Capture.TARGET_FPS).coerceAtLeast(8L)
                        try {
                            delay(frameDelayMs)
                        } catch (ce: CancellationException) {
                            throw ce
                        }
                    }
                } finally {
                    Logger.i(TAG, "Engine loop ended")
                }
            }

            running = true
            Logger.i(TAG, "Engine started")
            return true
        }
    }

    /**
     * Cancel the loop coroutine and reset all per-frame state. Idempotent —
     * safe to call from any thread.
     *
     * ## Non-blocking
     * This method does NOT block waiting for the loop coroutine to drain.
     * The previous implementation called `runBlocking { job.join() }` from
     * the service binder / main thread, which would ANR if a TFLite inference
     * was mid-flight (~50–200 ms). The new implementation:
     *   1. Atomically flips [running] to false (no more new iterations enter
     *      the body past the `running` check elsewhere).
     *   2. Cancels the loopJob.
     *   3. Installs an [Job.invokeOnCompletion] that performs the remaining
     *      teardown (smoother.reset, overlay clear, etc.) on the dispatcher
     *      thread — NOT on the caller's thread.
     *   4. Optionally invokes [onStopped] (once) after teardown is fully done.
     *
     * @param onStopped optional callback invoked exactly once after the
     *                 loop has fully drained and per-frame state is cleared.
     *                 Receives `true` if the loop was previously running,
     *                 `false` if it was already stopped.
     */
    fun stop(onStopped: ((wasRunning: Boolean) -> Unit)? = null) {
        val job: Job?
        val wasRunning: Boolean
        synchronized(lifecycleLock) {
            wasRunning = running
            if (!wasRunning) {
                Logger.d(TAG, "stop() called while not running — ignoring")
                onStopped?.invoke(false)
                return
            }
            running = false
            job = loopJob
            loopJob = null
            stoppedCallback.set(onStopped)
        }

        if (job == null) {
            // No active job — perform immediate cleanup.
            performPostLoopTeardown()
            stoppedCallback.getAndSet(null)?.invoke(true)
            return
        }

        // Cancel the loop coroutine. Non-blocking — `cancel` just sets the
        // cancellation flag and returns immediately.
        runCatching { job.cancel() }
            .onFailure { Logger.w(TAG, "loopJob.cancel failed: ${it.message}") }

        // Install the completion callback to do the heavy cleanup once the
        // coroutine has actually drained (no more in-flight runOnce).
        job.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                Logger.d(TAG, "Loop cancelled cooperatively")
            } else if (cause != null) {
                Logger.w(TAG, "Loop completed with error: ${cause.message}")
            }
            performPostLoopTeardown()
            stoppedCallback.getAndSet(null)?.invoke(true)
        }
    }

    /**
     * Block the caller until the loop has fully drained. **Use only from
     * a background thread** — calling from the main thread will ANR.
     *
     * This exists for the rare case where the caller needs deterministic
     * cleanup completion (e.g. before recycling bitmaps the loop is reading).
     * Prefer [stop] with an [onStopped] callback instead.
     */
    fun stopBlocking(timeoutMs: Long = 500L) {
        val job: Job?
        synchronized(lifecycleLock) {
            if (!running) return
            running = false
            job = loopJob
            loopJob = null
        }
        if (job != null) {
            runCatching { job.cancel() }
            runCatching {
                runBlocking {
                    kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
                        job.join()
                    }
                }
            }.onFailure { Logger.w(TAG, "stopBlocking join failed: ${it.message}") }
        }
        performPostLoopTeardown()
    }

    /**
     * Perform the per-frame state reset that must happen AFTER the loop has
     * drained — i.e. state that the loop body would otherwise race against.
     */
    private fun performPostLoopTeardown() {
        runCatching { smoother?.reset() }
            .onFailure { Logger.w(TAG, "smoother.reset failed: ${it.message}") }
        runCatching { aimCalculator?.reset() }
            .onFailure { Logger.w(TAG, "aimCalculator.reset failed: ${it.message}") }
        runCatching { triggerBot?.reset() }
            .onFailure { Logger.w(TAG, "triggerBot.reset failed: ${it.message}") }
        runCatching { pipeline.reset() }
            .onFailure { Logger.w(TAG, "pipeline.reset failed: ${it.message}") }

        // Clear the overlay's per-frame state — the menu itself stays put.
        runCatching {
            overlayController.setDetections(emptyList(), Constants.Detection.INPUT_SIZE, 0, 0)
        }
        runCatching { overlayController.setAimTarget(null) }

        synchronized(screenDetections) {
            screenDetections.clear()
        }

        targetSelector = null
        smoother = null
        aimCalculator = null
        triggerBot = null
        touchSimulator = null
        touchHandler = null
        lastFrameTimeNs = 0L
        cachedScreenW = 0
        cachedScreenH = 0

        Logger.i(TAG, "Engine stopped")
    }

    // ---------- Main loop ----------

    /**
     * Execute one inference + aim + overlay cycle. All failures are caught
     * by the caller in [start]'s loop wrapper; this method may throw.
     */
    private suspend fun runOnce() {
        // 1. Read latest frame from the buffer.
        val bitmap = frameBuffer.latest()
        if (bitmap == null || bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return

        // 2. Record frame start time (monotonic).
        val frameStartNs = android.os.SystemClock.elapsedRealtimeNanos()
        val nowMs = frameStartNs / 1_000_000L
        val dtMs = computeDt(nowMs)

        // 3. Run detection.
        val detectStartNs = frameStartNs
        val detections: List<Detection> = try {
            detector.detect(bitmap)
        } catch (t: Throwable) {
            Logger.w(TAG, "detector.detect failed: ${t.message}", t)
            return
        }
        val detectNs = android.os.SystemClock.elapsedRealtimeNanos() - detectStartNs

        // 4. Resolve screen metrics + FOV.
        val dm = context.resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        if (screenW <= 0 || screenH <= 0) {
            Logger.w(TAG, "screen dims invalid ${screenW}x${screenH}; skipping frame")
            return
        }
        cachedScreenW = screenW
        cachedScreenH = screenH
        val density = dm.density

        // Update reused screen-center in-place — no per-frame PointF alloc.
        screenCenter.set(screenW * 0.5f, screenH * 0.5f)

        val fovRadiusPx = (FeatureFlags.aimFov * density).coerceAtLeast(0f)
        val modelSize = Constants.Detection.INPUT_SIZE

        // 5. Branch on detections + aimbot enabled.
        val aimbotOn = FeatureFlags.aimbotEnabled
        if (detections.isEmpty() || !aimbotOn) {
            runCatching {
                overlayController.setDetections(emptyList(), modelSize, screenW, screenH)
            }.onFailure { Logger.w(TAG, "setDetections(empty) failed: ${it.message}") }

            runCatching { overlayController.setAimTarget(null) }
                .onFailure { Logger.w(TAG, "setAimTarget(null) failed: ${it.message}") }

            runCatching {
                overlayController.updateFps(PerformanceMonitor.fps().toInt(), 0)
            }.onFailure { Logger.w(TAG, "updateFps(0) failed: ${it.message}") }

            PerformanceMonitor.recordFrame(detectNs, 0L, 0L)
            return
        }

        // 6. Convert detections to SCREEN-COORD boxes for the TargetSelector.
        //
        // The TargetSelector requires screen-coord Detection instances because
        // it compares `det.center` against `screenCenter` (which is in screen
        // pixels). The raw detections from the detector are in model-input
        // coords (0..INPUT_SIZE), so we mirror them into screen coords.
        //
        // We retain a back-reference to the original (model-coord) Detection
        // via the parallel index because AimCalculator.aim() expects a
        // model-coord target (it projects internally via target.mapToScreen).
        synchronized(screenDetections) {
            screenDetections.clear()
            for (d in detections) {
                screenDetections += d.copy(box = d.mapToScreen(modelSize, screenW, screenH))
            }
        }

        val selector = targetSelector
        val targetScreen: Detection? = if (selector != null) {
            runCatching {
                selector.select(
                    detections = screenDetections,
                    screenCenter = screenCenter,
                    fovRadiusPx = fovRadiusPx,
                    headshotMode = FeatureFlags.headshotModeEnabled,
                    screenHeight = screenH.toFloat()
                )
            }.onFailure {
                Logger.w(TAG, "targetSelector.select failed: ${it.message}", it)
            }.getOrNull()
        } else null

        // Find the original (model-coord) Detection that corresponds to the
        // selected target — used for AimCalculator (which expects model coords).
        // We use index-based lookup to avoid the data-class equals edge case
        // where two detections have identical fields.
        val targetModel: Detection? = if (targetScreen != null) {
            val idx = screenDetections.indexOf(targetScreen)
            if (idx in detections.indices) detections[idx] else null
        } else null

        // 7. Push detections + aim target to the overlay.
        runCatching {
            overlayController.setDetections(detections, modelSize, screenW, screenH)
        }.onFailure { Logger.w(TAG, "setDetections failed: ${it.message}") }

        runCatching { overlayController.setAimTarget(targetScreen?.box) }
            .onFailure { Logger.w(TAG, "setAimTarget failed: ${it.message}") }

        // 8. Aim + trigger (only when a target was selected).
        var aimNs = 0L
        var injectNs = 0L
        var aimDispatched = false
        var triggerDispatched = false
        if (targetScreen != null) {
            val aimStartNs = android.os.SystemClock.elapsedRealtimeNanos()
            if (!FeatureFlags.silentAimEnabled) {
                val calc = aimCalculator
                val tm = targetModel
                if (calc != null && tm != null) {
                    val ok = runCatching {
                        calc.aim(tm, modelSize, screenW, screenH, dtMs)
                    }.onFailure {
                        Logger.w(TAG, "aimCalculator.aim failed: ${it.message}", it)
                    }.getOrDefault(false)
                    aimDispatched = ok
                }
            }
            aimNs = android.os.SystemClock.elapsedRealtimeNanos() - aimStartNs

            val injectStartNs = android.os.SystemClock.elapsedRealtimeNanos()
            val tb = triggerBot
            if (tb != null) {
                val fired = runCatching {
                    tb.maybeFire(targetScreen, fovRadiusPx, screenCenter)
                }.onFailure {
                    Logger.w(TAG, "triggerBot.maybeFire failed: ${it.message}", it)
                }.getOrDefault(false)
                triggerDispatched = fired
            }
            injectNs = android.os.SystemClock.elapsedRealtimeNanos() - injectStartNs
        }

        // 9. Update overlay FPS + target count.
        val targetsShown = if (targetScreen != null) detections.size else 0
        runCatching {
            overlayController.updateFps(PerformanceMonitor.fps().toInt(), targetsShown)
        }.onFailure { Logger.w(TAG, "updateFps failed: ${it.message}") }

        // 10. Record per-frame stage timings (detect / aim / inject).
        PerformanceMonitor.recordFrame(detectNs, aimNs, injectNs)

        // 11. Update cumulative session stats.
        val aimCount = if (aimDispatched) 1 else 0
        val triggerCount = if (triggerDispatched) 1 else 0
        pipeline.recordFrame(detections.size, aimCount, triggerCount)

        // 12. Heartbeat the watchdog so it knows the loop is alive.
        //     Cheap (single atomic write); only fires if the callback is set.
        try { heartbeatCallback?.invoke() } catch (_: Throwable) { /* swallow — never let the watchdog callback crash the loop */ }
    }

    /**
     * Compute the inter-frame delta in ms and update [lastFrameTimeNs].
     * Clamped to `[1, 200]` ms — protects against huge gaps (e.g. paused
     * pipeline) and tiny bursts (e.g. measurement noise). First call returns
     * the EMA-friendly default of 16 ms.
     */
    private fun computeDt(nowMs: Long): Long {
        val last = lastFrameTimeNs / 1_000_000L
        lastFrameTimeNs = nowMs * 1_000_000L
        return if (last > 0L) (nowMs - last).coerceIn(1L, MAX_DT_MS) else DEFAULT_DT_MS
    }

    companion object {
        private const val TAG = "Engine"

        /** Default `dt` (ms) on the very first frame, before we have a baseline. */
        private const val DEFAULT_DT_MS = 16L

        /** Upper clamp on inter-frame `dt` — guards against paused-pipeline spikes. */
        private const val MAX_DT_MS = 200L
    }
}
