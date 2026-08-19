package com.webstrike.aimbotpro.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import com.webstrike.aimbotpro.Constants
import com.webstrike.aimbotpro.config.SettingsManager
import com.webstrike.aimbotpro.detection.Detection
import com.webstrike.aimbotpro.utils.Logger

/**
 * Public facade used by [com.webstrike.aimbotpro.service.CoreAimbotService]
 * to manage the floating overlay.
 *
 * Owns two child windows added to the [WindowManager]:
 *  1. [ModMenuView] — the draggable / collapsible / closable panel with
 *     toggles + sliders. Touchable (the user interacts with it).
 *  2. [OverlayRenderer] — a full-screen pure-drawing surface that renders
 *     ESP boxes, FOV circle, and crosshair. NOT touchable (touches pass
 *     through to the game below).
 *
 * Both windows use [WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY]
 * (requires `android.permission.SYSTEM_ALERT_WINDOW`, declared in the
 * manifest). Flags: [WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE] (so
 * the game keeps input focus) + [WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN]
 * (so we can position the menu at any screen coordinate, including under
 * the status bar). Format [PixelFormat.TRANSLUCENT] (we draw our own
 * background via the layout's `bg_mod_menu` drawable).
 *
 * The controller also exposes pass-through methods for the Engine to push
 * per-frame ESP state — the Engine holds a single reference to this
 * controller rather than to [OverlayRenderer] directly.
 *
 * @param context       host context (the service)
 * @param windowManager the system WindowManager (resolved by the service)
 */
class ModMenuController(
    private val context: Context,
    private val windowManager: WindowManager
) {

    private var menuView: ModMenuView? = null
    private var menuParams: WindowManager.LayoutParams? = null
    private var renderer: OverlayRenderer? = null
    private var rendererParams: WindowManager.LayoutParams? = null

    @Volatile
    private var shown: Boolean = false

    // ---------- Public API ----------

    /**
     * Inflate the menu + the ESP renderer and add both to the WindowManager.
     * Idempotent: calling [show] twice in a row is a no-op (the second call
     * is logged and returns). Safe to call from any thread — WindowManager
     * itself is thread-safe.
     */
    fun show() {
        if (shown) {
            Logger.w(TAG, "show() called but already shown — ignoring")
            return
        }
        runCatching {
            // ---- 1. ESP renderer (added first so the menu draws on top) ----
            val r = OverlayRenderer(context)
            val rParams = buildRendererParams()
            windowManager.addView(r, rParams)
            renderer = r
            rendererParams = rParams

            // ---- 2. Mod menu ----
            val mParams = buildMenuParams()
            val menu = ModMenuView(
                context = context,
                windowManager = windowManager,
                params = mParams,
                onClose = { hide() },
                onPositionChanged = { x, y -> persistMenuPosition(x, y) }
            )
            windowManager.addView(menu, mParams)
            menuView = menu
            menuParams = mParams

            shown = true
            Logger.i(TAG, "Overlay shown (menu + renderer)")
        }.onFailure {
            Logger.e(TAG, "show() failed: ${it.message}", it)
            // Best-effort partial cleanup — don't leave a half-added overlay.
            runCatching { menuView?.let { windowManager.removeView(it) } }
            runCatching { renderer?.let { windowManager.removeView(it) } }
            menuView = null
            menuParams = null
            renderer = null
            rendererParams = null
            shown = false
        }
    }

    /**
     * Remove both views from the WindowManager. Guards against double-remove
     * (the [WindowManager.removeView] call throws if the view isn't currently
     * attached, so we wrap each removal in runCatching).
     *
     * Note: this does NOT stop the host [CoreAimbotService] — the user can
     * re-show the overlay by re-starting the service, or by sending a
     * TOGGLE_FEATURE intent for the crosshair/aimbot toggles.
     */
    fun hide() {
        if (!shown) {
            Logger.d(TAG, "hide() called but not shown — ignoring")
            return
        }
        runCatching { menuView?.let { windowManager.removeView(it) } }
            .onFailure { Logger.w(TAG, "menu removeView failed: ${it.message}") }
        runCatching { renderer?.let { windowManager.removeView(it) } }
            .onFailure { Logger.w(TAG, "renderer removeView failed: ${it.message}") }
        menuView = null
        menuParams = null
        renderer = null
        rendererParams = null
        shown = false
        Logger.i(TAG, "Overlay hidden")
    }

    /** Update the status footer (single-line text). Thread-safe. */
    fun updateStatus(text: String) {
        val v = menuView ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runCatching { v.setStatusText(text) }
        } else {
            v.post { runCatching { v.setStatusText(text) } }
        }
    }

    /** Update the FPS + target-count line in the footer. Thread-safe. */
    fun updateFps(fps: Int, targets: Int) {
        val v = menuView ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runCatching { v.setFpsTargets(fps, targets) }
        } else {
            v.post { runCatching { v.setFpsTargets(fps, targets) } }
        }
    }

    fun isShown(): Boolean = shown

    // ---------- Engine pass-throughs ----------

    /**
     * Push a fresh list of detections to the ESP renderer.
     * Safe to call from the inference coroutine — defers to [OverlayRenderer]
     * which is thread-safe.
     */
    fun setDetections(detections: List<Detection>, modelSize: Int, screenW: Int, screenH: Int) {
        renderer?.setDetections(detections, modelSize, screenW, screenH)
    }

    /** Highlight the currently-selected aim target's screen box (or null to clear). */
    fun setAimTarget(box: RectF?) {
        renderer?.setAimTarget(box)
    }

    /**
     * Move the crosshair position (screen px). Negative values fall back to
     * the screen centre inside the renderer.
     */
    fun setCrosshairPos(x: Float, y: Float) {
        renderer?.setCrosshairPos(x, y)
    }

    // ---------- LayoutParams builders ----------

    /**
     * Build the [WindowManager.LayoutParams] for the mod-menu panel.
     *
     * Position is loaded from [SettingsManager] (KEY_OVERLAY_POS_X/Y); on
     * first launch the defaults place the panel at the top-left, ~100 px
     * below the status bar so it doesn't cover the game's HUD top bar.
     *
     * Width is WRAP_CONTENT — the visible panel width is determined by the
     * layout's `@dimen/mod_menu_width` (280 dp). WRAP_CONTENT is chosen over
     * MATCH_PARENT so the window tightly wraps the panel and touches outside
     * the 280 dp panel pass through to the game below. (The task spec
     * mentions MATCH_PARENT, but with MATCH_PARENT + a non-touchable empty
     * window area, touches on the empty space would be captured by the
     * overlay and block the game — WRAP_CONTENT avoids that.)
     */
    private fun buildMenuParams(): WindowManager.LayoutParams {
        val settings = SettingsManager.get()
        val x = settings.getInt(Constants.Prefs.KEY_OVERLAY_POS_X, DEFAULT_MENU_X)
        val y = settings.getInt(Constants.Prefs.KEY_OVERLAY_POS_Y, DEFAULT_MENU_Y)

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
            // Soft-input mode — irrelevant for an overlay but explicit is better.
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
        }
    }

    /**
     * Build the [WindowManager.LayoutParams] for the ESP renderer.
     *
     * Full-screen (MATCH_PARENT × MATCH_PARENT), NOT touchable (touches pass
     * through to the game). Same overlay type + flags as the menu, plus
     * [WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE].
     */
    private fun buildRendererParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
    }

    // ---------- Persistence ----------

    private fun persistMenuPosition(x: Int, y: Int) {
        runCatching {
            val settings = SettingsManager.get()
            settings.setInt(Constants.Prefs.KEY_OVERLAY_POS_X, x)
            settings.setInt(Constants.Prefs.KEY_OVERLAY_POS_Y, y)
            Logger.d(TAG, "Menu position persisted: ($x, $y)")
        }.onFailure {
            Logger.w(TAG, "persistMenuPosition failed: ${it.message}")
        }
    }

    // ---------- Companion ----------

    companion object {
        private const val TAG = "ModMenuController"

        /** Default menu position on first launch (px, screen-relative). */
        private const val DEFAULT_MENU_X = 16
        private const val DEFAULT_MENU_Y = 120
    }
}
