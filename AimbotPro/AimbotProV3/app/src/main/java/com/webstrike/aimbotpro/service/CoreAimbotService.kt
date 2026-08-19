package com.webstrike.aimbotpro.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.PowerManager
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.webstrike.aimbotpro.Constants
import com.webstrike.aimbotpro.R
import com.webstrike.aimbotpro.capture.FrameBuffer
import com.webstrike.aimbotpro.capture.ScreenCaptureManager
import com.webstrike.aimbotpro.config.FeatureFlags
import com.webstrike.aimbotpro.core.Engine
import com.webstrike.aimbotpro.detection.ModelManager
import com.webstrike.aimbotpro.detection.YoloDetector
import com.webstrike.aimbotpro.input.InputInjector
import com.webstrike.aimbotpro.overlay.ModMenuController
import com.webstrike.aimbotpro.perf.EngineWatchdog
import com.webstrike.aimbotpro.perf.Telemetry
import com.webstrike.aimbotpro.utils.Logger
import com.webstrike.aimbotpro.utils.PerformanceMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Foreground service that orchestrates the whole aim-bot pipeline:
 *
 *  1. Acquires a [MediaProjection] from the parked result in
 *     [MediaProjectionHolder] (set by [com.webstrike.aimbotpro.MainActivity]
 *     after the user grants screen-capture consent).
 *  2. Calls [ServiceCompat.startForeground] with the
 *     [ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION] type so the
 *     capture session is allowed to run while the app is backgrounded.
 *  3. Boots [ModelManager] (TFLite interpreter), [YoloDetector],
 *     [ScreenCaptureManager] (MediaProjection + ImageReader + VirtualDisplay),
 *     a [FrameBuffer] bridging capture → engine, and [ModMenuController]
 *     for the floating overlay UI. ([TouchSimulator] is constructed
 *     internally by the [Engine] when needed.)
 *  4. Constructs the [Engine] and calls [Engine.start] on a service-tied
 *     [CoroutineScope].
 *
 * The service is NOT a [androidx.lifecycle.LifecycleService] — we keep deps
 * minimal and manage coroutine scope manually. The scope is created on START
 * and cancelled in [onDestroy].
 *
 * Intents (declared in [Constants.Actions]):
 *   - `START`           → boot the pipeline
 *   - `STOP`            → tear everything down and stopSelf
 *   - `TOGGLE_FEATURE`  → update a [FeatureFlags] key with a Boolean or Float value
 */
class CoreAimbotService : Service() {

    // ---------- Service plumbing ----------

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Logger.i(TAG, "onStartCommand action=$action startId=$startId")
        return when (action) {
            Constants.Actions.START -> {
                val ok = startPipeline()
                if (!ok) {
                    // Failed to start — tear down and let the system kill us.
                    stopPipeline()
                    stopSelf()
                }
                START_NOT_STICKY // we need projection consent which is gone after kill
            }

            Constants.Actions.STOP -> {
                stopPipeline()
                stopSelf()
                START_NOT_STICKY
            }

            Constants.Actions.TOGGLE_FEATURE -> {
                handleToggleFeature(intent)
                START_NOT_STICKY
            }

            else -> {
                Logger.w(TAG, "Unknown action: $action — ignoring")
                START_NOT_STICKY
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Logger.i(TAG, "onDestroy")
        stopPipeline()
    }

    // ---------- Pipeline state ----------

    private var projection: MediaProjection? = null
    private var captureManager: ScreenCaptureManager? = null
    private var frameBuffer: FrameBuffer? = null
    private var detector: YoloDetector? = null
    private var overlayController: ModMenuController? = null
    private var engine: Engine? = null
    private var watchdog: EngineWatchdog? = null

    private var wakeLock: PowerManager.WakeLock? = null

    private var serviceScope: CoroutineScope? = null

    // Projection callback — fires when the user revokes projection permission.
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Logger.w(TAG, "MediaProjection stopped (revoked or system-killed) — tearing down")
            stopPipeline()
            stopSelf()
        }
    }

    // ---------- START ----------

    /**
     * Boot the full pipeline. Returns `true` on success, `false` on failure
     * (caller is responsible for calling [stopPipeline] and [stopSelf] when
     * this returns false).
     */
    private fun startPipeline(): Boolean {
        // 1. Notification channel (idempotent — created once per process).
        createNotificationChannel()

        // 2. Start foreground FIRST — required before acquiring MediaProjection
        //    on Android 10+ (we'd be killed if we delayed).
        val notification = buildNotification()
        ServiceCompat.startForeground(
            /* service = */ this,
            /* id = */ Constants.Notifications.NOTIF_ID,
            /* notification = */ notification,
            /* foregroundServiceType = */
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            else 0
        )

        // 3. Acquire MediaProjection from the holder (set by MainActivity).
        //    consume() atomically reads AND clears the holder — Android 14+
        //    requires a fresh consent grant per session.
        val (resultCode, resultData) = MediaProjectionHolder.consume()
        if (resultData == null) {
            Logger.e(TAG, "START failed — no MediaProjection result in holder. " +
                    "Did MainActivity call MediaProjectionHolder.set() before sending START?")
            return false
        }

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
            as? MediaProjectionManager
        if (projectionManager == null) {
            Logger.e(TAG, "MediaProjectionManager service unavailable")
            return false
        }

        val proj = runCatching {
            projectionManager.getMediaProjection(resultCode, resultData)
        }.getOrElse {
            Logger.e(TAG, "getMediaProjection failed: ${it.message}", it)
            return false
        } ?: run {
            Logger.e(TAG, "getMediaProjection returned null — consent revoked?")
            return false
        }

        projection = proj
        proj.registerCallback(projectionCallback, Handler(mainLooper))
        Logger.i(TAG, "MediaProjection acquired")

        // 4. Boot ModelManager + YoloDetector.
        ModelManager.init(this)
        FeatureFlags.demoMode = ModelManager.isDemoMode()
        if (FeatureFlags.demoMode) Telemetry.count(Telemetry.Model.DEMO_MODE)
        val det = YoloDetector(ModelManager)
        detector = det

        // 5. Set up FrameBuffer + capture.
        val buffer = FrameBuffer(maxSize = 2)
        frameBuffer = buffer

        val capture = ScreenCaptureManager(this, proj)
        captureManager = capture
        capture.setOnFrame { bmp ->
            buffer.put(bmp)
            // Push live frame stats to the overlay (cheap; buffer size rarely > 1)
            overlayController?.updateFps(
                PerformanceMonitor.fps().toInt(),
                buffer.size()
            )
        }

        // 6. Overlay (owned by ModMenuController).
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (windowManager == null) {
            Logger.e(TAG, "WindowManager unavailable — overlay cannot show")
            return false
        }
        val overlay = ModMenuController(this, windowManager)
        overlayController = overlay

        // 7. Engine + scope.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        serviceScope = scope

        val engine = Engine(
            context = this,
            detector = det,
            capture = capture,
            frameBuffer = buffer,
            inputInjector = InputInjector, // object singleton passed by reference
            overlayController = overlay
        )
        this.engine = engine

        // 8. Capture loop → frames fill the buffer; engine pulls from it.
        val (w, h, dpi) = resolveCaptureDimensions()
        capture.start(w, h, dpi)

        // 9. Overlay UI up.
        runCatching { overlay.show() }.onFailure {
            Logger.w(TAG, "overlay.show() failed: ${it.message}")
        }
        overlay.updateStatus(
            if (FeatureFlags.demoMode) Constants.Misc.DEMO_MODE_TEXT else "Active"
        )

        // 10. Engine coroutine loop last — it consumes from the frame buffer.
        engine.start(scope)

        // 10b. EngineWatchdog — monitors the inference loop for stalls.
        //     Calls back on the watchdog thread if no frame is processed
        //     within ~5 seconds (chronic TFLite / overlay failure).
        val wd = EngineWatchdog(onStall = { elapsedMs ->
            Logger.w(TAG, "Engine stall detected: ${elapsedMs}ms — broadcasting to UI")
            Telemetry.event(Telemetry.Engine.FRAME_FAILED, "stall ${elapsedMs}ms")
            // Update the overlay status so the user sees "STALLED" instead
            // of a stale "Active". The engine is left running so it can
            // recover on its own if/when the underlying condition clears.
            overlayController?.updateStatus("STALLED ${elapsedMs}ms")
        })
        engine.setHeartbeatCallback { wd.heartbeat() }
        wd.start()
        watchdog = wd

        // 11. Optional partial wake lock so inference keeps running with screen off.
        acquireWakeLock()

        FeatureFlags.serviceRunning = true
        Telemetry.count(Telemetry.Engine.STARTED)
        Logger.i(TAG, "Pipeline started — capture=${w}x${h}@${dpi}dpi, demo=${FeatureFlags.demoMode}")
        return true
    }

    // ---------- STOP ----------

    /**
     * Tear down the entire pipeline in reverse boot order. Idempotent — every
     * component is null-checked and skipped if already torn down.
     */
    private fun stopPipeline() {
        Logger.i(TAG, "Tearing down pipeline")

        // 0. Stop the watchdog first — we don't want it firing onStall
        //    callbacks during the engine's deliberate shutdown.
        watchdog?.stop()
        watchdog = null
        engine?.setHeartbeatCallback(null)

        // 1. Engine first — stops consuming the frame buffer. Use the
        //    non-blocking variant + invokeOnCompletion callback so the
        //    main thread doesn't ANR while TFLite finishes its in-flight call.
        val engineRef = engine
        engine = null
        engineRef?.stop(onStopped = { _ ->
            // Once the engine has fully drained, it's safe to release
            // the detector's native resources without racing inference.
            runCatching { detector?.release() }
            detector = null
        })
        // Fallback: if engine.stop didn't run (engine == null), still drop detector.
        if (engineRef == null) {
            runCatching { detector?.release() }
            detector = null
        }
        Telemetry.count(Telemetry.Engine.STOPPED)

        // 2. Capture — release VirtualDisplay / ImageReader / projection callback.
        runCatching { captureManager?.stop() }
        captureManager = null

        // 3. Overlay — remove views from WindowManager.
        runCatching { overlayController?.hide() }
        overlayController = null

        // 4. Frame buffer — drop references.
        runCatching { frameBuffer?.clear() }
        frameBuffer = null

        // 5. Projection — unregister callback, stop projection.
        projection?.let { proj ->
            runCatching { proj.unregisterCallback(projectionCallback) }
            runCatching { proj.stop() }
        }
        projection = null

        // 6. Wake lock release.
        releaseWakeLock()

        // 7. Coroutine scope cancel — propagates cancellation to the engine's
        //    inference coroutine. runCatching swallows any IllegalStateException
        //    if the scope was already cancelled.
        serviceScope?.let { scope ->
            runCatching { scope.cancel() }
        }
        serviceScope = null

        // 8. Foreground state — stop foreground (removes the notification).
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)

        // 9. Marker state.
        FeatureFlags.serviceRunning = false
        Logger.i(TAG, "Pipeline torn down")
    }

    // ---------- TOGGLE_FEATURE ----------

    /**
     * Read `key` (String), `value` (Boolean or Float) from the intent extras
     * and apply to [FeatureFlags]. The mod-menu / overlay sends these via
     * [Context.startService] with the [Constants.Actions.TOGGLE_FEATURE] action.
     */
    private fun handleToggleFeature(intent: Intent) {
        val key = intent.getStringExtra(EXTRA_KEY) ?: run {
            Logger.w(TAG, "TOGGLE_FEATURE missing 'key' extra")
            return
        }
        // Validate key against the known set — prevents a bogus key from
        // being persisted to SharedPreferences via FeatureFlags.setBool/setFloat.
        if (!FeatureFlags.isValidKey(key)) {
            Logger.w(TAG, "TOGGLE_FEATURE rejected unknown key='$key'")
            return
        }

        // Boolean first (most common — toggles)
        if (intent.hasExtra(EXTRA_VALUE_BOOL)) {
            val v = intent.getBooleanExtra(EXTRA_VALUE_BOOL, false)
            FeatureFlags.setBool(key, v)
            Logger.d(TAG, "Feature toggle: $key -> $v")
            return
        }

        // Float (sliders)
        if (intent.hasExtra(EXTRA_VALUE_FLOAT)) {
            val v = intent.getFloatExtra(EXTRA_VALUE_FLOAT, 0f)
            FeatureFlags.setFloat(key, v)
            Logger.d(TAG, "Feature slider: $key -> $v")
            return
        }

        // Long (trigger delay)
        if (intent.hasExtra(EXTRA_VALUE_LONG)) {
            val v = intent.getLongExtra(EXTRA_VALUE_LONG, 0L)
            FeatureFlags.setLong(key, v)
            Logger.d(TAG, "Feature long: $key -> $v")
            return
        }

        Logger.w(TAG, "TOGGLE_FEATURE for key='$key' had no recognized value extra")
    }

    // ---------- Helpers ----------

    /**
     * Create the notification channel. Idempotent — recreating an existing
     * channel is a no-op per Android docs.
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            Constants.Notifications.CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    /**
     * Build the foreground notification. Low priority (the service is
     * always-running; we don't want to nag the user), no actions, no
     * custom content view — just enough to satisfy the foreground-service
     * contract.
     */
    private fun buildNotification(): android.app.Notification {
        return NotificationCompat.Builder(this, Constants.Notifications.CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(R.drawable.ic_power)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    /**
     * Resolve the capture dimensions from system display metrics. Falls back
     * to [Constants.Capture] defaults if metrics are unavailable.
     */
    private fun resolveCaptureDimensions(): Triple<Int, Int, Int> {
        val dm = resources.displayMetrics
        val w = if (dm.widthPixels > 0) dm.widthPixels else Constants.Capture.SCREEN_WIDTH_DEFAULT
        val h = if (dm.heightPixels > 0) dm.heightPixels else Constants.Capture.SCREEN_HEIGHT_DEFAULT
        val dpi = if (dm.densityDpi > 0) dm.densityDpi else Constants.Capture.SCREEN_DPI_DEFAULT
        return Triple(w, h, dpi)
    }

    /**
     * Acquire a partial wake lock so the CPU stays awake while the screen
     * is off (the user may switch apps while the overlay stays active).
     * Released in [releaseWakeLock].
     */
    private fun acquireWakeLock() {
        if (wakeLock != null) return
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return@runCatching
            val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            lock.setReferenceCounted(false)
            lock.acquire(WAKE_LOCK_TIMEOUT_MS)
            wakeLock = lock
            Logger.d(TAG, "Partial wake lock acquired")
        }.onFailure {
            Logger.w(TAG, "Wake lock acquire failed: ${it.message}")
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        }
        wakeLock = null
    }

    // ---------- Companion (static holders) ----------

    companion object {
        private const val TAG = "CoreAimbotService"

        private const val EXTRA_KEY = "key"
        private const val EXTRA_VALUE_BOOL = "value.bool"
        private const val EXTRA_VALUE_FLOAT = "value.float"
        private const val EXTRA_VALUE_LONG = "value.long"

        private const val WAKE_LOCK_TIMEOUT_MS = 30L * 60L * 1000L // 30 minutes
        private const val WAKE_LOCK_TAG = "AimbotPro::CaptureWakeLock"

        /**
         * Convenience accessor that delegates to [MediaProjectionHolder]. Set
         * by [com.webstrike.aimbotpro.MainActivity] before sending the START
         * intent. Kept as a `var` on the companion for spec compatibility.
         */
        @JvmStatic
        var mediaProjectionResultCode: Int
            get() = MediaProjectionHolder.resultCode
            set(value) {
                MediaProjectionHolder.resultCode = value
            }

        /**
         * Convenience accessor that delegates to [MediaProjectionHolder].
         * See [mediaProjectionResultCode].
         */
        @JvmStatic
        var mediaProjectionResultData: Intent?
            get() = MediaProjectionHolder.data
            set(value) {
                MediaProjectionHolder.data = value
            }
    }
}
