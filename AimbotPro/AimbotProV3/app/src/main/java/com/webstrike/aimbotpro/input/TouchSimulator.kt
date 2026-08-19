package com.webstrike.aimbotpro.input

import android.os.Handler
import com.webstrike.aimbotpro.utils.Logger

/**
 * Higher-level touch helpers used by the aim engine. Wraps [InputInjector]
 * with intent-specific methods (aim delta, fire trigger, generic tap) and
 * executes every dispatch on a caller-supplied background [Handler] so the
 * inference coroutine never blocks on the accessibility layer.
 *
 * Lifecycle: constructed once by [com.webstrike.aimbotpro.service.CoreAimbotService]
 * on START, using a background handler thread. Disposed implicitly when the
 * service's [android.os.HandlerThread] is quit on STOP. The handler must
 * outlive any in-flight posted dispatches — see [CoreAimbotService] where we
 * quit the handler thread only after [com.webstrike.aimbotpro.core.Engine.stop]
 * has joined.
 *
 * Thread-safety: [fireButtonX] / [fireButtonY] are `@Volatile` — safe to
 * update at runtime from the mod menu / overlay touch handler.
 *
 * @param handler background [Handler] used to schedule every accessibility
 *                dispatch. Caller owns the handler's lifecycle.
 */
class TouchSimulator(private val handler: Handler) {

    private val logTag = "TouchSimulator"

    /**
     * Configured fire-button position in physical screen pixels. Defaults to
     * `-1f` meaning "unconfigured — compute top-right corner from cached
     * screen dims at trigger time". Update via [setFireButtonPosition].
     */
    @Volatile var fireButtonX: Float = -1f
        private set
    @Volatile var fireButtonY: Float = -1f
        private set

    /**
     * Update the configured fire-button location. Called by the overlay /
     * mod-menu "fire button calibration" flow (a future task may add a
     * draggable crosshair overlay). Until then, defaults to top-right corner.
     */
    fun setFireButtonPosition(x: Float, y: Float) {
        fireButtonX = x
        fireButtonY = y
        Logger.d(logTag, "Fire button position set to ($x, $y)")
    }

    /**
     * Apply an aim-correction delta to the camera by issuing a short swipe
     * at the screen centre. The swipe's duration is one frame (16 ms) so the
     * aim correction completes before the next inference frame.
     *
     * @param dx delta X in pixels (rightward positive)
     * @param dy delta Y in pixels (downward positive)
     */
    fun applyAimDelta(dx: Float, dy: Float) {
        if (dx == 0f && dy == 0f) return // nothing to do
        handler.post {
            InputInjector.dispatchMove(dx, dy, AIM_DELTA_DURATION_MS)
        }
    }

    /**
     * Simulate a tap on the fire button. Falls back to a top-right corner
     * default when no fire-button position has been configured via
     * [setFireButtonPosition]. If screen dimensions are unknown and no
     * explicit position is set, the tap is skipped (logged) to avoid
     * misfiring into the wrong UI region.
     */
    fun triggerFire() {
        handler.post {
            val (fx, fy) = resolveFireButtonPosition()
            if (fx <= 0f || fy <= 0f) {
                Logger.w(logTag, "triggerFire skipped — fire button position unresolved ($fx,$fy)")
                return@post
            }
            InputInjector.dispatchTap(fx, fy)
        }
    }

    /**
     * Generic tap at any screen coordinate. Async — always returns immediately.
     */
    fun tapAt(x: Float, y: Float) {
        handler.post {
            InputInjector.dispatchTap(x, y)
        }
    }

    // ---------- internals ----------

    /**
     * Resolve the fire button position to actual screen coords. If
     * [fireButtonX] / [fireButtonY] are configured (< 0 means unset),
     * use them directly; otherwise compute a top-right default from the
     * cached screen dimensions.
     */
    private fun resolveFireButtonPosition(): Pair<Float, Float> {
        if (fireButtonX >= 0f && fireButtonY >= 0f) {
            return fireButtonX to fireButtonY
        }
        val sw = InputInjector.screenWidth()
        val sh = InputInjector.screenHeight()
        if (sw <= 0 || sh <= 0) {
            return -1f to -1f // unknown — caller must skip
        }
        val defaultX = sw - (sw * (1f - DEFAULT_FIRE_BUTTON_X_FRACTION))
        val defaultY = sh * DEFAULT_FIRE_BUTTON_Y_FRACTION
        return defaultX to defaultY
    }

    companion object {
        /** Per-frame aim swipe duration (≈ one 60 FPS frame). */
        private const val AIM_DELTA_DURATION_MS = 16L

        /** Default fire-button X position as a fraction of screen width (1.0 = right edge). */
        private const val DEFAULT_FIRE_BUTTON_X_FRACTION = 0.86f

        /** Default fire-button Y position as a fraction of screen height (0.0 = top edge). */
        private const val DEFAULT_FIRE_BUTTON_Y_FRACTION = 0.18f
    }
}
