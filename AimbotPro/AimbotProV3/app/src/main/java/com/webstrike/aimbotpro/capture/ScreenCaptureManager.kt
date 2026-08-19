package com.webstrike.aimbotpro.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import com.webstrike.aimbotpro.utils.Logger
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.withLock
import java.util.concurrent.locks.ReentrantLock

/**
 * Wraps Android [MediaProjection] + [VirtualDisplay] + [ImageReader] into a
 * clean capture API that delivers mutable [Bitmap] frames on a background
 * thread.
 *
 * Threading model:
 *   - All capture callbacks (image-available, virtual-display, projection)
 *     run on a dedicated [HandlerThread] named `"screen-capture"`. This
 *     keeps the main thread free and isolates the JNI pixel-copy work.
 *   - The frame listener set via [setOnFrame] is invoked on that same
 *     capture handler thread.
 *   - [start], [stop] and [setOnFrame] are safe to call from any thread.
 *
 * Allocation profile (hot path):
 *   - One [Bitmap] instance per capture session, reused across every frame.
 *   - At most one scratch [ByteBuffer] per session, reused for the row-padded
 *     copy path. The fast (no-padding) path allocates zero objects per frame.
 *
 * The bitmap handed to the listener is **mutable** and **shared** — the
 * listener must not hold onto the reference across frames without first
 * making its own copy. [FrameBuffer] stores references without copying,
 * which is safe because [ScreenCaptureManager] guarantees that the same
 * bitmap instance is overwritten atomically per-frame and the engine's
 * pixel reads via [android.graphics.Bitmap.getPixels] are themselves atomic
 * at the JNI level (whole-frame snapshot).
 *
 * @param context    any context; the application context is captured.
 * @param projection  a live [MediaProjection] obtained from
 *                    [android.media.projection.MediaProjectionManager.getMediaProjection].
 *                    Ownership is transferred: [stop] will call
 *                    [MediaProjection.stop] on teardown.
 */
class ScreenCaptureManager(
    context: Context,
    private val projection: MediaProjection
) {
    private val appContext: Context = context.applicationContext

    private val lock = ReentrantLock()

    @Volatile private var imageReader: ImageReader? = null
    @Volatile private var virtualDisplay: VirtualDisplay? = null
    @Volatile private var captureThread: HandlerThread? = null
    @Volatile private var captureHandler: Handler? = null

    /** Reused bitmap. Recreated only when capture dimensions change. */
    @Volatile private var reusableBitmap: Bitmap? = null

    /** Reused scratch buffer for the row-padded copy path (size = rowStride). */
    @Volatile private var rowScratch: ByteBuffer? = null

    /** Atomic listener swap — settable from any thread, read on capture thread. */
    private val frameListenerRef = AtomicReference<((Bitmap) -> Unit)?>()

    @Volatile private var running: Boolean = false

    /** Projection callback — invoked when the user revokes projection permission. */
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Logger.w(TAG, "MediaProjection stopped (revoked or system-killed)")
            // Trigger internal teardown without re-entering projection.stop()
            teardown(clearListener = true)
        }
    }

    // ---------- public API ----------

    /**
     * Start screen capture at the given resolution / dpi.
     *
     * Safe to call once per instance — calling again without [stop] is a no-op
     * (logged). Throws nothing — failures are logged and surface as no frames
     * being delivered (caller can detect by missing [setOnFrame] invocations).
     *
     * @param width  capture width in pixels (e.g. 1080)
     * @param height capture height in pixels (e.g. 1920)
     * @param dpi    density of the virtual display (use
     *               [android.util.DisplayMetrics].densityDpi or
     *               [com.webstrike.aimbotpro.Constants.Capture].SCREEN_DPI_DEFAULT)
     */
    fun start(width: Int, height: Int, dpi: Int) {
        if (!lock.withLock {
                if (running) {
                    Logger.w(TAG, "start() called while already running — ignoring")
                    return@withLock false
                }
                running = true
                true
            }) return

        try {
            // 1. Background thread for image callbacks. Use Process thread
            //    priority for display-related work instead of Thread.MAX_PRIORITY
            //    (which can starve the UI thread on some devices).
            val thread = HandlerThread("screen-capture", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY).also {
                it.start()
            }
            captureThread = thread
            captureHandler = Handler(thread.looper)

            // 2. Register projection callback on the MAIN looper — NOT on
            //    captureHandler. If we registered on captureHandler, then
            //    MediaProjection.Callback.onStop() would run on the capture
            //    thread and call teardown(), which would try to quitSafely()
            //    + join() the same thread we're running on (no-op) and then
            //    close the ImageReader from inside its own callback thread —
            //    a re-entrant teardown race.
            //    Using the main looper means onStop() runs on the UI thread,
            //    which can safely shut down the capture HandlerThread.
            runCatching {
                projection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
            }.onFailure {
                Logger.w(TAG, "registerCallback failed: ${it.message}")
            }

            // 3. ImageReader (RGBA_8888, depth 2 — we only care about latest)
            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            reader.setOnImageAvailableListener(imageAvailableListener, captureHandler)
            imageReader = reader

            // 4. VirtualDisplay mirrors the default display onto the reader's surface.
            val surface: Surface = reader.surface
            val display = projection.createVirtualDisplay(
                "AimbotProCapture",
                width,
                height,
                dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                captureHandler
            )
            virtualDisplay = display

            Logger.i(TAG, "Capture started: ${width}x${height} @ ${dpi}dpi")
        } catch (t: Throwable) {
            Logger.e(TAG, "Capture start failed: ${t.message}", t)
            // Roll back partial state and re-mark not-running so caller can retry.
            teardown(clearListener = false)
            lock.withLock { running = false }
        }
    }

    /**
     * Stop capture and release all native resources:
     *   - ImageReader (closes its surface + frees buffers)
     *   - VirtualDisplay
     *   - MediaProjection callback (unregister; projection.stop is left to caller)
     *   - Capture HandlerThread
     *   - Reusable Bitmap (allow GC)
     *
     * Idempotent — calling twice is safe. Does NOT call [MediaProjection.stop]
     * so the same projection can be re-used for a subsequent [start] (though
     * Android only allows one MediaProjection per consent — see the docs on
     * [MediaProjection]).
     */
    fun stop() {
        teardown(clearListener = true)
        lock.withLock { running = false }
    }

    /**
     * Set (or replace / clear) the per-frame listener. The listener is invoked
     * on the capture handler thread — it must not block. Pass `null` to
     * detach.
     */
    fun setOnFrame(listener: ((bitmap: Bitmap) -> Unit)?) {
        frameListenerRef.set(listener)
    }

    /** Whether capture is currently active. */
    fun isRunning(): Boolean = running

    // ---------- internals ----------

    private val imageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        // acquireLatestImage() drops stale frames so we never fall behind.
        val image: Image = reader.acquireLatestImage() ?: return@OnImageAvailableListener
        try {
            if (image.planes.isEmpty()) return@OnImageAvailableListener
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val width = image.width
            val height = image.height

            // Ensure reusable bitmap matches the image dimensions.
            val bitmap = ensureBitmap(width, height) ?: return@OnImageAvailableListener

            // Copy pixel data — fast path when no row padding.
            val expectedRowBytes = width * pixelStride
            if (rowStride == expectedRowBytes) {
                buffer.rewind()
                bitmap.copyPixelsFromBuffer(buffer)
            } else {
                // Row padding present — copy row by row into a tight scratch buffer.
                val tight = ensureRowScratch(width * height * pixelStride)
                    ?: return@OnImageAvailableListener
                buffer.rewind()
                tight.clear()
                var srcPos = 0
                var dstPos = 0
                for (y in 0 until height) {
                    buffer.position(srcPos)
                    buffer.limit(srcPos + expectedRowBytes)
                    tight.position(dstPos)
                    tight.put(buffer)
                    srcPos += rowStride
                    dstPos += expectedRowBytes
                }
                // Reset buffer for next callback.
                buffer.clear()
                tight.flip()
                bitmap.copyPixelsFromBuffer(tight)
            }

            // Notify listener (on capture handler thread).
            frameListenerRef.get()?.invoke(bitmap)
        } catch (t: Throwable) {
            Logger.w(TAG, "Frame copy failed: ${t.message}")
        } finally {
            // ALWAYS close the Image — leaks are catastrophic (locks the surface).
            runCatching { image.close() }
        }
    }

    /**
     * Lazily allocate (or resize) the reused Bitmap. Returns null on
     * out-of-memory. Caller holds no lock — Bitmap allocation is on the
     * capture thread (single-threaded by design).
     */
    private fun ensureBitmap(width: Int, height: Int): Bitmap? {
        val current = reusableBitmap
        if (current != null && current.width == width && current.height == height && !current.isRecycled) {
            return current
        }
        return runCatching {
            // Explicitly mutable so copyPixelsFromBuffer works.
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                reusableBitmap = it
                rowScratch = null // invalidate scratch — size depends on dimensions
            }
        }.getOrElse {
            Logger.e(TAG, "Bitmap alloc failed ${width}x${height}: ${it.message}", it)
            null
        }
    }

    /**
     * Lazily allocate (or resize) the reused tight scratch buffer for the
     * row-padded copy path.
     */
    private fun ensureRowScratch(byteCount: Int): ByteBuffer? {
        val current = rowScratch
        if (current != null && current.capacity() >= byteCount) {
            return current
        }
        return runCatching {
            ByteBuffer.allocateDirect(byteCount).also {
                rowScratch = it
            }
        }.getOrElse {
            Logger.e(TAG, "Row scratch alloc failed ($byteCount bytes): ${it.message}", it)
            null
        }
    }

    /**
     * Internal teardown shared by [stop] and the projection-onStop callback.
     *
     * @param clearListener if true, also drop the [setOnFrame] listener so
     *                      a reviving capture session does not invoke stale
     *                      callbacks.
     */
    private fun teardown(clearListener: Boolean) {
        // IMPORTANT: do as much as possible OUTSIDE the lock first, so the
        // capture thread can finish its in-flight callback without blocking
        // on our teardown. We then synchronously wait for the capture thread
        // to drain before touching the reused bitmap (otherwise a mid-copy
        // callback could see a recycled bitmap and crash).

        // 1. Stop VirtualDisplay — no more frames pushed to the ImageReader.
        runCatching { virtualDisplay?.release() }.onFailure {
            Logger.w(TAG, "VirtualDisplay.release failed: ${it.message}")
        }

        // 2. Detach listener — prevents new onImageAvailable callbacks.
        runCatching { imageReader?.setOnImageAvailableListener(null, null) }

        // 3. Quit the capture thread (drains pending messages, then exits).
        //    quitSafely is non-blocking but join() waits for the thread to
        //    fully finish — guaranteeing no in-flight callback remains.
        captureThread?.let { thread ->
            thread.quitSafely()
            runCatching { thread.join(STOP_THREAD_JOIN_MS) }
        }

        // 4. Now safe to mutate state shared with the capture thread.
        lock.withLock {
            runCatching { projection.unregisterCallback(projectionCallback) }

            // 5. Close the ImageReader AFTER the capture thread has exited.
            runCatching { imageReader?.close() }.onFailure {
                Logger.w(TAG, "ImageReader.close failed: ${it.message}")
            }

            // 6. Recycle the reused bitmap — safe now that the capture
            //    thread is gone.
            runCatching { reusableBitmap?.recycle() }

            // 7. Drop references last so any concurrent re-entrant call to
            //    teardown() (which we do NOT expect, but defend against) sees
            //    consistent null state.
            virtualDisplay = null
            imageReader = null
            captureThread = null
            captureHandler = null
            reusableBitmap = null
            rowScratch = null

            if (clearListener) {
                frameListenerRef.set(null)
            }
        }
        Logger.i(TAG, "Capture torn down (clearListener=$clearListener)")
    }

    companion object {
        private const val TAG = "ScreenCaptureManager"
        private const val STOP_THREAD_JOIN_MS = 500L
    }
}
