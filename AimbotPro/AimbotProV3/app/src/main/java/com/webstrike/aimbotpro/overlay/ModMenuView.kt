package com.webstrike.aimbotpro.overlay

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.webstrike.aimbotpro.Constants
import com.webstrike.aimbotpro.R
import com.webstrike.aimbotpro.config.FeatureFlags
import com.webstrike.aimbotpro.config.SettingsManager
import com.webstrike.aimbotpro.utils.Logger
import java.util.Locale
import kotlin.math.abs

/**
 * The floating mod-menu panel.
 *
 * Layout: [R.layout.mod_menu_container] — a vertical [LinearLayout] with a
 * draggable header (title + collapse + close), a body containing three
 * sections (AIM toggles, VISUAL toggles, MISC sliders), and a status footer
 * with live FPS + target count.
 *
 * Drag: handled by an [View.OnTouchListener] on the header. Uses
 * [MotionEvent.getRawX]/[getRawY] so the drag is correct even though the
 * header itself is translated by [WindowManager.LayoutParams.x]/[y]. The
 * drag updates the [params] in place and calls [WindowManager.updateViewLayout].
 * On [MotionEvent.ACTION_UP] the new (x, y) is persisted via [onPositionChanged].
 *
 * External updates: registers a [FeatureFlags] listener in [init]; the listener
 * posts to the UI thread (since FeatureFlags may be mutated from any thread).
 * Unregisters in [onDetachedFromWindow].
 *
 * @param context            host context
 * @param windowManager       the WindowManager the controller added us to
 * @param params             the live LayoutParams (we mutate x/y during drag)
 * @param onClose            invoked when the user taps the close button — the
 *                           controller assigns this to its [ModMenuController.hide]
 * @param onPositionChanged  invoked on drag-end with the new (x, y) so the
 *                           controller can persist them via [SettingsManager]
 */
class ModMenuView(
    context: Context,
    private val windowManager: WindowManager,
    internal var params: WindowManager.LayoutParams,
    private val onClose: () -> Unit,
    private val onPositionChanged: (Int, Int) -> Unit
) : LinearLayout(context) {

    // ---------- View refs ----------
    private val header: View
    private val body: View
    private val btnCollapse: ImageButton
    private val btnClose: ImageButton
    private val statusFooter: TextView
    private val fpsFooter: TextView
    private val sectionAim: LinearLayout
    private val sectionVisual: LinearLayout
    private val sectionMisc: LinearLayout

    // ---------- Toggle/slider bookkeeping ----------
    private val density: Float = context.resources.displayMetrics.density
    private val thumbTravelPx: Float = THUMB_TRAVEL_DP * density

    /** Map FeatureFlags key → toggle row handle (so the listener can update the UI). */
    private val toggleRows = HashMap<String, ToggleRow>(12)
    /** Map FeatureFlags key → slider row handle. */
    private val sliderRows = HashMap<String, SliderRow>(5)

    // ---------- Drag state ----------
    private var dragInitialX = 0
    private var dragInitialY = 0
    private var dragInitialTouchX = 0f
    private var dragInitialTouchY = 0f
    private var isDragging = false

    // ---------- FeatureFlags listener (kept as a field so we can remove it) ----------
    private val featureFlagsListener: (String, Any) -> Unit = { key, value ->
        // FeatureFlags may fire from any thread; bounce to the UI thread.
        post { handleFeatureFlagChange(key, value) }
    }

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.mod_menu_container, this, true)

        header = findViewById(R.id.modMenuHeader)
        body = findViewById(R.id.modMenuBody)
        btnCollapse = findViewById(R.id.btnCollapse)
        btnClose = findViewById(R.id.btnClose)
        statusFooter = findViewById(R.id.statusFooter)
        fpsFooter = findViewById(R.id.fpsFooter)
        sectionAim = findViewById(R.id.sectionAim)
        sectionVisual = findViewById(R.id.sectionVisual)
        sectionMisc = findViewById(R.id.sectionMisc)

        setupHeaderDrag()
        setupHeaderButtons()
        populateToggles()
        populateSliders()
        restoreCollapsedState()

        // Subscribe LAST — the toggle/slider maps must be populated before we
        // can react to external FeatureFlags changes.
        FeatureFlags.addListener(featureFlagsListener)
    }

    // ---------- Public API (called by ModMenuController) ----------

    fun setStatusText(text: String) {
        statusFooter.text = text
    }

    fun setFpsTargets(fps: Int, targets: Int) {
        fpsFooter.text = "FPS: $fps | Targets: $targets"
    }

    // ---------- Header ----------

    private fun setupHeaderButtons() {
        btnCollapse.setOnClickListener {
            toggleCollapsed()
        }
        btnClose.setOnClickListener {
            Logger.i(TAG, "Close button tapped — requesting controller.hide()")
            onClose()
        }
    }

    private fun toggleCollapsed() {
        val collapsed = body.visibility != View.GONE
        body.visibility = if (collapsed) View.GONE else View.VISIBLE
        SettingsManager.get().setBool(Constants.Prefs.KEY_OVERLAY_COLLAPSED, collapsed)
        // Re-clamp position so the smaller menu stays on-screen after collapse.
        clampAndApplyPosition()
    }

    private fun restoreCollapsedState() {
        val collapsed = SettingsManager.get().getBool(Constants.Prefs.KEY_OVERLAY_COLLAPSED, false)
        body.visibility = if (collapsed) View.GONE else View.VISIBLE
    }

    // ---------- Drag ----------

    private fun setupHeaderDrag() {
        // `setOnTouchListener` returns true to consume the event. Children with
        // their own click handlers (btnCollapse, btnClose) still receive their
        // own ACTION_DOWN because Android dispatches to children first.
        header.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragInitialX = params.x
                    dragInitialY = params.y
                    dragInitialTouchX = event.rawX
                    dragInitialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - dragInitialTouchX
                    val dy = event.rawY - dragInitialTouchY
                    if (!isDragging) {
                        val slop = Constants.Overlay.TOUCH_SLOP
                        if (abs(dx) > slop || abs(dy) > slop) {
                            isDragging = true
                        }
                    }
                    if (isDragging) {
                        params.x = dragInitialX + dx.toInt()
                        params.y = dragInitialY + dy.toInt()
                        clampAndApplyPosition()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isDragging) {
                        // Persist final position.
                        onPositionChanged(params.x, params.y)
                    }
                    isDragging = false
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Clamp [params.x]/[params.y] to the screen bounds (accounting for the
     * panel's measured width/height) and call [WindowManager.updateViewLayout].
     *
     * If the view hasn't been measured yet ([width]==0 / [height]==0) we fall
     * back to the dimen constants so the first drag doesn't place us off-screen.
     */
    private fun clampAndApplyPosition() {
        val (sw, sh) = resolveScreenSize()
        val menuW = if (width > 0) width else (Constants.Overlay.MENU_WIDTH_DP * density).toInt()
        val menuH = if (height > 0) height else (MENU_HEIGHT_ESTIMATE_DP * density).toInt()
        params.x = params.x.coerceIn(0, (sw - menuW).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (sh - menuH).coerceAtLeast(0))
        runCatching {
            windowManager.updateViewLayout(this, params)
        }.onFailure {
            Logger.w(TAG, "updateViewLayout failed during drag: ${it.message}")
        }
    }

    private fun resolveScreenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val metrics = windowManager.maximumWindowMetrics
                Pair(metrics.bounds.width(), metrics.bounds.height())
            }.getOrElse {
                val dm = resources.displayMetrics
                Pair(dm.widthPixels, dm.heightPixels)
            }
        } else {
            val dm = resources.displayMetrics
            Pair(dm.widthPixels, dm.heightPixels)
        }
    }

    // ---------- Toggles ----------

    private fun populateToggles() {
        // Section AIM (6 toggles)
        addToggle(sectionAim, FeatureFlags.Keys.AIMBOT,   R.string.feat_aimbot,         R.drawable.ic_aimbot,     FeatureFlags.aimbotEnabled)
        addToggle(sectionAim, FeatureFlags.Keys.TRIGGER,  R.string.feat_triggerbot,    R.drawable.ic_power,     FeatureFlags.triggerBotEnabled)
        addToggle(sectionAim, FeatureFlags.Keys.RECOIL,   R.string.feat_recoil,        R.drawable.ic_power,     FeatureFlags.recoilControlEnabled)
        addToggle(sectionAim, FeatureFlags.Keys.SMOOTH,  R.string.feat_smooth,        R.drawable.ic_power,     FeatureFlags.aimSmoothEnabled)
        addToggle(sectionAim, FeatureFlags.Keys.SILENT,  R.string.feat_silent_aim,    R.drawable.ic_aimbot,     FeatureFlags.silentAimEnabled)
        addToggle(sectionAim, FeatureFlags.Keys.HEADSHOT, R.string.feat_headshot_mode, R.drawable.ic_crosshair,  FeatureFlags.headshotModeEnabled)
        // Section VISUAL (6 toggles)
        addToggle(sectionVisual, FeatureFlags.Keys.ESP_BOXES, R.string.feat_esp,         R.drawable.ic_esp,       FeatureFlags.espBoxesEnabled)
        addToggle(sectionVisual, FeatureFlags.Keys.ESP_LINES, R.string.feat_esp_lines,   R.drawable.ic_esp,       FeatureFlags.espLinesEnabled)
        addToggle(sectionVisual, FeatureFlags.Keys.ESP_DIST,  R.string.feat_esp_distance, R.drawable.ic_esp,       FeatureFlags.espDistanceEnabled)
        addToggle(sectionVisual, FeatureFlags.Keys.ESP_NAMES, R.string.feat_esp_names,  R.drawable.ic_esp,       FeatureFlags.espNamesEnabled)
        addToggle(sectionVisual, FeatureFlags.Keys.FOV_CIRCLE, R.string.feat_fov_circle, R.drawable.ic_fov,        FeatureFlags.fovCircleEnabled)
        addToggle(sectionVisual, FeatureFlags.Keys.CROSSHAIR, R.string.feat_crosshair,   R.drawable.ic_crosshair, FeatureFlags.crosshairEnabled)
    }

    /**
     * Inflate a toggle row, bind it to its [FeatureFlags] key, and wire the
     * click handler. Stores the row in [toggleRows] so the FeatureFlags listener
     * can update the visuals when the value changes from elsewhere.
     */
    private fun addToggle(parent: LinearLayout, key: String, labelResId: Int, iconResId: Int, initial: Boolean) {
        val row = LayoutInflater.from(context).inflate(R.layout.mod_menu_item_toggle, parent, false)
        val icon = row.findViewById<ImageView>(R.id.itemIcon)
        val label = row.findViewById<TextView>(R.id.itemLabel)
        val bg = row.findViewById<View>(R.id.toggleBg)
        val thumb = row.findViewById<View>(R.id.toggleThumb)

        icon.setImageResource(iconResId)
        label.setText(labelResId)

        val handle = ToggleRow(row, bg, thumb, initial)
        applyToggleVisual(handle, initial, animate = false)
        toggleRows[key] = handle

        row.setOnClickListener {
            val next = !handle.current
            // FeatureFlags.setBool persists + fires the listener (which will
            // re-apply the visual). Setting handle.current here as well makes
            // the UI feel instant even before the listener round-trip posts.
            handle.current = next
            applyToggleVisual(handle, next, animate = true)
            FeatureFlags.setBool(key, next)
        }
        parent.addView(row)
    }

    private fun applyToggleVisual(handle: ToggleRow, on: Boolean, animate: Boolean) {
        val bgDrawable: Drawable? = ContextCompat.getDrawable(
            context,
            if (on) R.drawable.bg_toggle_on else R.drawable.bg_toggle_off
        )
        handle.bg.background = bgDrawable
        if (animate) {
            handle.thumb.animate()
                .translationX(if (on) thumbTravelPx else 0f)
                .setDuration(TOGGLE_ANIM_MS)
                .start()
        } else {
            handle.thumb.translationX = if (on) thumbTravelPx else 0f
        }
    }

    // ---------- Sliders ----------

    private fun populateSliders() {
        // aimSpeed: 0..1 → 0..100
        addSlider(
            parent = sectionMisc,
            key = FeatureFlags.Keys.AIM_SPEED,
            labelResId = R.string.slider_aim_speed,
            initial = FeatureFlags.aimSpeed,
            min = 0f, max = 1f,
            format = "%.0f%%"
        ) { v -> FeatureFlags.setFloat(FeatureFlags.Keys.AIM_SPEED, v) }

        // aimFov: 50..400 dp → 0..100
        addSlider(
            parent = sectionMisc,
            key = FeatureFlags.Keys.AIM_FOV,
            labelResId = R.string.slider_aim_fov,
            initial = FeatureFlags.aimFov,
            min = 50f, max = 400f,
            format = "%.0f dp"
        ) { v -> FeatureFlags.setFloat(FeatureFlags.Keys.AIM_FOV, v) }

        // aimSmoothness: 0..1 → 0..100
        addSlider(
            parent = sectionMisc,
            key = FeatureFlags.Keys.AIM_SMOOTH,
            labelResId = R.string.slider_aim_smooth,
            initial = FeatureFlags.aimSmoothness,
            min = 0f, max = 1f,
            format = "%.0f%%"
        ) { v -> FeatureFlags.setFloat(FeatureFlags.Keys.AIM_SMOOTH, v) }

        // triggerDelayMs: 0..300 ms → 0..100 (Long in FeatureFlags)
        addSlider(
            parent = sectionMisc,
            key = FeatureFlags.Keys.TRIGGER_DELAY,
            labelResId = R.string.slider_trigger_delay,
            initial = FeatureFlags.triggerDelayMs.toFloat(),
            min = 0f, max = 300f,
            format = "%.0f ms"
        ) { v -> FeatureFlags.setLong(FeatureFlags.Keys.TRIGGER_DELAY, v.toLong()) }

        // minConfidence: 0.3..0.95 → 0..100
        addSlider(
            parent = sectionMisc,
            key = FeatureFlags.Keys.MIN_CONF,
            labelResId = R.string.slider_confidence,
            initial = FeatureFlags.minConfidence,
            min = 0.3f, max = 0.95f,
            format = "%.2f"
        ) { v -> FeatureFlags.setFloat(FeatureFlags.Keys.MIN_CONF, v) }
    }

    /**
     * Inflate a slider row, bind it to its [FeatureFlags] key, and wire the
     * SeekBar listener. The SeekBar's `max` is fixed at 100 by the layout;
     * we map progress 0..100 ↔ real value [min]..[max] here.
     */
    private fun addSlider(
        parent: LinearLayout,
        key: String,
        labelResId: Int,
        initial: Float,
        min: Float,
        max: Float,
        format: String,
        onChange: (Float) -> Unit
    ) {
        val row = LayoutInflater.from(context).inflate(R.layout.mod_menu_item_slider, parent, false)
        val label = row.findViewById<TextView>(R.id.sliderLabel)
        val valueText = row.findViewById<TextView>(R.id.sliderValue)
        val seek = row.findViewById<SeekBar>(R.id.sliderSeek)

        label.setText(labelResId)

        val initialProgress = ((initial - min) / (max - min) * 100f)
            .toInt()
            .coerceIn(0, 100)
        seek.progress = initialProgress
        // Locale.US — non-English locales could produce non-ASCII separators
        // in the value text (e.g. comma decimal point) which would look broken
        // in the slider value column.
        valueText.text = String.format(Locale.US, format, initial)

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val actual = min + (max - min) * progress / 100f
                valueText.text = String.format(Locale.US, format, actual)
                if (fromUser) onChange(actual)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        sliderRows[key] = SliderRow(seek, valueText, min, max, format)
        parent.addView(row)
    }

    // ---------- FeatureFlags listener ----------

    private fun handleFeatureFlagChange(key: String, value: Any) {
        toggleRows[key]?.let { row ->
            val on = (value as? Boolean) ?: return@let
            // Skip the visual update if we already reflect this state — avoids
            // a redundant re-animation when the click handler already applied
            // the visual before the FeatureFlags listener round-tripped.
            if (row.current != on) {
                row.current = on
                applyToggleVisual(row, on, animate = true)
            }
            return
        }
        sliderRows[key]?.let { row ->
            // Float or Long (triggerDelay) — normalize to Float.
            val v = when (value) {
                is Float -> value
                is Long  -> value.toFloat()
                is Int   -> value.toFloat()
                else     -> return@let
            }
            val progress = ((v - row.min) / (row.max - row.min) * 100f)
                .toInt()
                .coerceIn(0, 100)
            if (row.seek.progress != progress) {
                row.seek.progress = progress  // triggers onProgressChanged(fromUser=false) → updates valueText
            } else {
                row.valueText.text = String.format(Locale.US, row.format, v)
            }
        }
    }

    // ---------- Lifecycle ----------

    override fun onDetachedFromWindow() {
        // Crucial: remove the listener or we'd leak the view (FeatureFlags is
        // a singleton that outlives the overlay window).
        runCatching { FeatureFlags.removeListener(featureFlagsListener) }
        super.onDetachedFromWindow()
    }

    // ---------- Inner data holders ----------

    private class ToggleRow(
        val row: View,
        val bg: View,
        val thumb: View,
        @Volatile var current: Boolean
    )

    private class SliderRow(
        val seek: SeekBar,
        val valueText: TextView,
        val min: Float,
        val max: Float,
        val format: String
    )

    companion object {
        private const val TAG = "ModMenuView"

        /** Horizontal distance the toggle thumb travels (dp). */
        private const val THUMB_TRAVEL_DP = 22f

        /** Toggle-thumb animation duration (ms). */
        private const val TOGGLE_ANIM_MS = 120L

        /** Better fallback for the panel's full height before first measure. */
        private const val MENU_HEIGHT_ESTIMATE_DP = 600f
    }
}
