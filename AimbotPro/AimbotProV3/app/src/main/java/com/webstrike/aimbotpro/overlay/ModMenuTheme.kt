package com.webstrike.aimbotpro.overlay

import android.graphics.Color
import android.graphics.Paint

/**
 * Static colour palette + paint factory for the overlay renderer.
 *
 * The hex values mirror [com.webstrike.aimbotpro.R.color] entries (esp_enemy,
 * esp_ally, esp_target, esp_text, fov_circle, crosshair) so the XML resources
 * and the canvas code stay in sync without requiring a [android.content.Context]
 * lookup at draw time. Hard-coding is intentional — the palette is fixed and
 * resolving from resources per-frame would allocate.
 */
object ModMenuTheme {

    // ESP box stroke colors (mirror colors.xml)
    const val ENEMY: Int = 0xFFFF3D3D.toInt()   // R.color.esp_enemy
    const val ALLY: Int = 0xFF3D7BFF.toInt()    // R.color.esp_ally
    const val TARGET: Int = 0xFFFFEB3B.toInt()  // R.color.esp_target
    const val TEXT: Int = 0xFFFFFFFF.toInt()    // R.color.esp_text
    const val FOV: Int = 0xFF00FF88.toInt()     // R.color.fov_circle
    const val CROSSHAIR: Int = 0xFFFFFFFF.toInt() // R.color.crosshair

    // Convenience aliases for the full palette (used by OverlayRenderer).
    val enemy: Int get() = ENEMY
    val ally: Int get() = ALLY
    val target: Int get() = TARGET
    val text: Int get() = TEXT
    val fov: Int get() = FOV
    val crosshair: Int get() = CROSSHAIR

    /**
     * Build a [Paint] pre-configured with the given [color], [width] (in px),
     * and [style]. Anti-aliasing is always on (cheap and looks much better
     * for diagonal box edges + circles).
     *
     * Callers cache the result — never allocate paints in [android.graphics.Canvas]
     * draw loops.
     */
    fun paint(color: Int, width: Float, style: Paint.Style): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        this.strokeWidth = width
        this.style = style
        this.isAntiAlias = true
        this.isDither = true
    }

    /**
     * Convenience text-paint factory — same as [paint] but with text-specific
     * tweaks (stroke width 0, set stroke/fill).
     */
    fun textPaint(color: Int, textSizePx: Float, style: Paint.Style = Paint.Style.FILL): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            this.textSize = textSizePx
            this.style = style
            this.isAntiAlias = true
            this.isSubpixelText = true
        }

    /** Same colour with a forced alpha (0..255). Returns a new Int colour. */
    fun withAlpha(color: Int, alpha: Int): Int = (alpha.coerceIn(0, 255) shl 24) or (color and 0x00FFFFFF)
}
