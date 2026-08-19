package com.webstrike.aimbotpro.aim

import com.webstrike.aimbotpro.utils.Logger

/**
 * Humanised exponential-moving-average aim trajectory smoother.
 *
 * Goal: avoid the "snap to target" tell that gives aimbots away. Instead of
 * jumping the camera to the target every frame, we lerp the applied aim
 * position toward the latest target by a frame-rate-independent factor
 * derived from [smoothness]. We then add a small sinusoidal jitter so the
 * trajectory looks hand-driven, not algorithmically straight.
 *
 * State:
 *   - [lastTargetX] / [lastTargetY]   — last desired target (post-jitter),
 *      kept so we can resume smoothly if the smoother is paused (e.g. lost
 *      target for a few frames) — currently informational, used to seed
 *      the EMA when [reset] hasn't been called yet.
 *   - [lastAppliedX] / [lastAppliedY] — last value we actually returned;
 *      the EMA anchor.
 *
 * Frame-rate independence: the lerp factor `alpha` is scaled by the
 * inter-frame `dt` clamped to [4, 50] ms, so the same `smoothness` value
 * feels the same on a 30 FPS device as on a 144 FPS one.
 *
 * NaN safety: any non-finite input is clamped to the last applied value
 * (or 0f if reset), so a corrupted target never propagates a NaN into the
 * touch dispatch pipeline.
 *
 * Thread-safety: not thread-safe — the aim engine drives the smoother from
 * a single inference coroutine, so concurrent access is not expected.
 */
class AimSmoother {

    private val logTag = "AimSmoother"

    /** Last desired target X (post-jitter). */
    @Volatile private var lastTargetX: Float = 0f

    /** Last desired target Y (post-jitter). */
    @Volatile private var lastTargetY: Float = 0f

    /** Last applied (returned) X. EMA anchor. */
    @Volatile private var lastAppliedX: Float = 0f

    /** Last applied (returned) Y. EMA anchor. */
    @Volatile private var lastAppliedY: Float = 0f

    /** Phase counter for the sinusoidal jitter — increments per [smooth] call. */
    private var jitterPhase: Double = 0.0

    /** Jitter amplitude in pixels (± this value). */
    private val jitterAmplitudePx: Float = 2f

    /** Jitter angular frequency per call (≈ one cycle every ~7 frames). */
    private val jitterFreq: Double = 2.0 * Math.PI / 7.0

    /**
     * Reused output buffer — eliminates the per-frame `FloatArray(2)` allocation
     * that was happening on every inference frame in v3.
     *
     * **Caller contract**: do NOT retain the returned reference across frames;
     * the buffer is overwritten on the next call.
     */
    private val output: FloatArray = FloatArray(2)

    /**
     * Smoothly advance the applied aim position toward (targetX, targetY).
     *
     * @param targetX    desired target X (screen pixels).
     * @param targetY    desired target Y (screen pixels).
     * @param smoothness 0..1, higher = slower (more smoothing).
     * @param dt         inter-frame delta in milliseconds.
     * @return `[x, y]` to actually aim at this frame (post-EMA + jitter).
     *         **Same `FloatArray` reference every call** — do not retain.
     */
    fun smooth(
        targetX: Float,
        targetY: Float,
        smoothness: Float,
        dt: Long
    ): FloatArray {
        // Sanitise inputs — non-finite values degrade to last-applied (no NaN
        // ever leaks downstream).
        val sTargetX = if (targetX.isFinite()) targetX else lastAppliedX
        val sTargetY = if (targetY.isFinite()) targetY else lastAppliedY

        val sSmoothness = when {
            !smoothness.isFinite() -> 0.5f
            else -> smoothness.coerceIn(0f, 1f)
        }

        // Frame-rate-independent lerp factor.
        // dt coerced to [4, 50] ms — protects against weirdly large gaps
        // (e.g. paused pipeline) and tiny bursts (e.g. measurement noise).
        val dtMs = if (dt > 0L) dt.coerceIn(4L, 50L) else 16L
        val alphaRaw = (1f - sSmoothness) * (dtMs.toFloat() / 16f)
        // alpha should stay in (0, ~3] in practice; clamp to [0, 1] so the
        // EMA is genuinely a low-pass filter (never overshoots).
        val alpha = alphaRaw.coerceIn(0f, 1f)

        // EMA step.
        var nextX = lastAppliedX + (sTargetX - lastAppliedX) * alpha
        var nextY = lastAppliedY + (sTargetY - lastAppliedY) * alpha

        // Sinusoidal jitter for humanisation — orthogonal-ish axes (cos
        // on X, sin on Y) so the wobble doesn't look one-dimensional.
        jitterPhase += jitterFreq
        val jitterX = (Math.cos(jitterPhase) * jitterAmplitudePx).toFloat()
        val jitterY = (Math.sin(jitterPhase * 1.3) * jitterAmplitudePx).toFloat()
        nextX += jitterX
        nextY += jitterY

        // Final NaN guard — belt & braces.
        if (!nextX.isFinite()) {
            Logger.w(logTag, "smooth: nextX NaN; reverting to lastAppliedX=$lastAppliedX")
            nextX = lastAppliedX
        }
        if (!nextY.isFinite()) {
            Logger.w(logTag, "smooth: nextY NaN; reverting to lastAppliedY=$lastAppliedY")
            nextY = lastAppliedY
        }

        // Commit state.
        lastTargetX = sTargetX
        lastTargetY = sTargetY
        lastAppliedX = nextX
        lastAppliedY = nextY

        output[0] = nextX
        output[1] = nextY
        return output
    }

    /**
     * Clear all state. Call when the aim pipeline is reset (e.g. target lost,
     * user toggled aimbot off, or service stopped) so the next [smooth] call
     * starts from scratch rather than lerping from a stale anchor.
     */
    fun reset() {
        lastTargetX = 0f
        lastTargetY = 0f
        lastAppliedX = 0f
        lastAppliedY = 0f
        jitterPhase = 0.0
    }
}
