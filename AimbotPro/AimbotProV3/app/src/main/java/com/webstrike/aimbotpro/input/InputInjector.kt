package com.webstrike.aimbotpro.input

import android.content.Context
import android.util.DisplayMetrics
import com.webstrike.aimbotpro.service.AimbotAccessibilityService
import com.webstrike.aimbotpro.utils.Logger

/**
 * Singleton façade over the system AccessibilityService's gesture dispatch.
 *
 * Why a singleton (instead of being part of [AimbotAccessibilityService])?
 * The aim engine and trigger logic live outside the service object graph —
 * they need a stable, easy-to-mock entry point to inject touch events. The
 * actual [android.accessibilityservice.AccessibilityService] is managed by
 * the Android system; we just keep a reference to it after it connects.
 *
 * Lifecycle:
 *   - On [AimbotAccessibilityService.onServiceConnected], the service sets
 *     [accessibilityService] = `this`.
 *   - On [AimbotAccessibilityService.onUnbind], it sets it back to `null`.
 *
 * Thread-safety:
 *   - [accessibilityService] is `@Volatile` — reads and writes are atomic.
 *   - [dispatchSwipe] / [dispatchTap] / [dispatchMove] are safe to call from
 *     any thread. They will simply return `false` if the service isn't
 *     connected (no exception, no crash — log only). This matches the
 *     "don't crash the game" robustness contract.
 *   - Cached screen dimensions are read via `@Volatile` fields.
 *
 * Coordinate space: all coordinates are in physical screen pixels relative
 * to the default display origin (top-left).
 */
object InputInjector {

    private const val TAG = "InputInjector"

    /**
     * The currently-connected [AimbotAccessibilityService], or `null` when
     * the user hasn't enabled the service in system settings. Set by the
     * service itself on connect / disconnect — do not touch from outside.
     */
    @Volatile
    var accessibilityService: AimbotAccessibilityService? = null

    /**
     * Cached screen width in pixels. Refreshed whenever a service connects
     * or whenever [dispatchMove] is invoked (cheap; pulls from
     * [DisplayMetrics] of the service's context).
     */
    @Volatile
    private var screenWidth: Int = 0

    @Volatile
    private var screenHeight: Int = 0

    /**
     * Called by [AimbotAccessibilityService.onServiceConnected] to register
     * the live service. Also refreshes the cached screen dimensions.
     */
    fun attachService(service: AimbotAccessibilityService) {
        accessibilityService = service
        refreshScreenSize(service)
        Logger.i(TAG, "Accessibility service attached; screen=${screenWidth}x${screenHeight}")
    }

    /**
     * Called by [AimbotAccessibilityService.onUnbind] to drop the reference.
     * Does not stop the service itself — the system does that on unbind.
     */
    fun detachService() {
        accessibilityService = null
        Logger.i(TAG, "Accessibility service detached")
    }

    /**
     * Dispatch a swipe gesture from (startX, startY) to (endX, endY) over
     * `durationMs`. Returns `true` if the gesture was dispatched to the
     * accessibility layer, `false` if the service is not connected or the
     * dispatch failed.
     *
     * The gesture is asynchronous — the system plays it over `durationMs`
     * and reports completion via a [android.accessibilityservice.AccessibilityService.GestureResultCallback]
     * (we currently don't wire the callback; the system still completes the
     * gesture regardless).
     */
    fun dispatchSwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long
    ): Boolean {
        val svc = accessibilityService
        if (svc == null) {
            Logger.w(TAG, "dispatchSwipe ignored: accessibility not connected")
            return false
        }
        return runCatching {
            svc.dispatchSwipe(startX, startY, endX, endY, durationMs)
        }.onFailure {
            Logger.w(TAG, "dispatchSwipe failed: ${it.message}")
        }.getOrDefault(false)
    }

    /**
     * Dispatch a tap (down + up) at (x, y). Delegates to the connected
     * accessibility service which builds a zero-displacement
     * [android.accessibilityservice.GestureDescription.StrokeDescription]
     * with a short duration. Returns `true` on successful dispatch,
     * `false` if the service is not connected or the dispatch failed.
     */
    fun dispatchTap(x: Float, y: Float): Boolean {
        val svc = accessibilityService
        if (svc == null) {
            Logger.w(TAG, "dispatchTap ignored: accessibility not connected")
            return false
        }
        return runCatching {
            svc.dispatchTap(x, y)
        }.onFailure {
            Logger.w(TAG, "dispatchTap failed: ${it.message}")
        }.getOrDefault(false)
    }

    /**
     * Move the camera by (dx, dy) pixels — implemented as a short swipe
     * centered on the screen. Most games interpret swipes starting at the
     * screen centre as "look" input, which is what we want for aim assist.
     *
     * @param dx         delta X in pixels (rightward positive)
     * @param dy         delta Y in pixels (downward positive)
     * @param durationMs swipe duration; default 16 ms ≈ one frame at 60 FPS
     */
    fun dispatchMove(dx: Float, dy: Float, durationMs: Long = DEFAULT_MOVE_DURATION_MS): Boolean {
        // Refresh screen size on each call — it's cheap, and handles
        // orientation / display-mode changes without explicit invalidation.
        accessibilityService?.let { refreshScreenSize(it) }
        if (screenWidth == 0 || screenHeight == 0) {
            // Fall back to a sensible centre if we somehow have no metrics.
            Logger.w(TAG, "dispatchMove: screen size unknown, using fallback center")
            return dispatchSwipe(FALLBACK_CENTER, FALLBACK_CENTER, FALLBACK_CENTER + dx, FALLBACK_CENTER + dy, durationMs)
        }
        val cx = screenWidth * 0.5f
        val cy = screenHeight * 0.5f
        return dispatchSwipe(cx, cy, cx + dx, cy + dy, durationMs)
    }

    /**
     * Read the latest [DisplayMetrics] into the cached fields. Safe to call
     * repeatedly; uses the application context's resources so it works even
     * if the service is in the process of detaching.
     */
    private fun refreshScreenSize(context: Context) {
        runCatching {
            val dm: DisplayMetrics = context.resources.displayMetrics
            if (dm.widthPixels > 0 && dm.heightPixels > 0) {
                screenWidth = dm.widthPixels
                screenHeight = dm.heightPixels
            }
        }.onFailure {
            Logger.w(TAG, "refreshScreenSize failed: ${it.message}")
        }
    }

    /** Current cached screen width (0 if never refreshed). */
    fun screenWidth(): Int = screenWidth

    /** Current cached screen height (0 if never refreshed). */
    fun screenHeight(): Int = screenHeight

    /**
     * Dispatch a scroll-up gesture (look up in-game).
     * Implemented as an upward swipe at screen centre.
     * @param amountPx scroll distance in pixels (default 200 = moderate scroll)
     */
    fun scrollUp(amountPx: Float = DEFAULT_SCROLL_AMOUNT_PX): Boolean {
        return dispatchMove(0f, -amountPx, SCROLL_DURATION_MS)
    }

    /**
     * Dispatch a scroll-down gesture (look down in-game).
     * Implemented as a downward swipe at screen centre.
     * @param amountPx scroll distance in pixels (default 200 = moderate scroll)
     */
    fun scrollDown(amountPx: Float = DEFAULT_SCROLL_AMOUNT_PX): Boolean {
        return dispatchMove(0f, amountPx, SCROLL_DURATION_MS)
    }

    private const val DEFAULT_MOVE_DURATION_MS = 16L
    private const val DEFAULT_SCROLL_AMOUNT_PX = 200f
    private const val SCROLL_DURATION_MS = 50L  // longer for smooth scroll feel
    private const val FALLBACK_CENTER = 540f // half of 1080 — works for FHD phones
}
