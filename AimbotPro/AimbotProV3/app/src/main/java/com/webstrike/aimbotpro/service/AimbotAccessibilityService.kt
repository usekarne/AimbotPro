package com.webstrike.aimbotpro.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.content.Intent
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import com.webstrike.aimbotpro.input.InputInjector
import com.webstrike.aimbotpro.utils.Logger

/**
 * Accessibility service that exists solely to inject synthetic touch events
 * via [dispatchGesture]. It deliberately does NOT process any
 * [onAccessibilityEvent] — we don't snoop on the user's UI tree. The
 * service is bound by the system after the user enables it in
 * Settings ▸ Accessibility, at which point it registers itself with
 * [InputInjector] so the aim engine can dispatch swipes / taps.
 *
 * Manifest wiring: declared in `AndroidManifest.xml` with
 * `android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"`,
 * `canPerformGestures="true"` (see `res/xml/accessibility_service_config.xml`).
 *
 * Gesture model (Android API 24+):
 *   - A [GestureDescription] is built from one or more [StrokeDescription]s.
 *   - Each stroke is a [Path] + a time window (startTime, duration).
 *   - `willContinue = false` for single-stroke gestures (no chaining).
 *   - The service's [dispatchGesture] call returns `true` synchronously iff
 *     the gesture was successfully queued; actual completion is async and
 *     reported via an optional [GestureResultCallback] (not wired — fire and
 *     forget, since aim correction is per-frame and a missed frame just
 *     delays the next one).
 */
class AimbotAccessibilityService : AccessibilityService() {

    /**
     * Reused [Path] for swipe + tap gestures — eliminates the per-frame
     * `Path()` + `StrokeDescription` allocation churn (60+ allocs/sec).
     * Accessed only on the system's gesture-dispatch thread (single-thread).
     */
    private val gesturePath: Path = Path()

    override fun onServiceConnected() {
        super.onServiceConnected()
        Logger.i(TAG, "Accessibility service connected — registering with InputInjector")
        InputInjector.attachService(this)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Logger.i(TAG, "Accessibility service unbinding — detaching from InputInjector")
        InputInjector.detachService()
        return super.onUnbind(intent)
    }

    /**
     * We do NOT process accessibility events — this service only injects
     * gestures. Implementing as a no-op keeps the accessibility framework
     * happy and avoids any privacy-invasive event reads.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Intentional no-op.
    }

    override fun onInterrupt() {
        Logger.w(TAG, "onInterrupt — accessibility service interrupted by system")
    }

    /**
     * Dispatch a swipe gesture from (startX, startY) to (endX, endY) played
     * over [durationMs] milliseconds. Coordinates are in physical screen
     * pixels relative to the default display origin (top-left).
     *
     * @return `true` if the gesture was successfully queued for dispatch,
     *         `false` otherwise (e.g. accessibility disabled, gesture
     *         malformed, or system rejected it).
     */
    fun dispatchSwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long
    ): Boolean {
        if (durationMs <= 0L) {
            Logger.w(TAG, "dispatchSwipe: duration must be > 0 (got $durationMs)")
            return false
        }
        // Reset + reuse the shared Path — much cheaper than allocating a fresh
        // Path per call (~60 allocations/sec on the hot path).
        gesturePath.reset()
        gesturePath.moveTo(startX, startY)
        gesturePath.lineTo(endX, endY)
        val stroke = StrokeDescription(
            /* path = */ gesturePath,
            /* startTime = */ 0L,
            /* duration = */ durationMs,
            /* willContinue = */ false
        )
        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /**
     * Dispatch a tap (down + up) at (x, y). Implemented as a very short
     * zero-displacement stroke — this is the canonical pattern for taps
     * via [AccessibilityService.dispatchGesture].
     *
     * The [TAP_DURATION_MS] is kept well below the platform's long-press
     * threshold (typically ~500 ms) so the system interprets it as a tap.
     */
    fun dispatchTap(x: Float, y: Float): Boolean {
        // Reset + reuse the shared Path.
        gesturePath.reset()
        gesturePath.moveTo(x, y)
        gesturePath.lineTo(x, y)
        val stroke = StrokeDescription(
            /* path = */ gesturePath,
            /* startTime = */ 0L,
            /* duration = */ TAP_DURATION_MS,
            /* willContinue = */ false
        )
        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()
        return dispatchGesture(gesture, null, null)
    }

    companion object {
        private const val TAG = "AimbotAccessibilityService"
        private const val TAP_DURATION_MS = 16L
    }
}
