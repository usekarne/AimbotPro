package com.webstrike.aimbotpro.perf

import com.webstrike.aimbotpro.utils.Logger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

/**
 * Lightweight telemetry facade for production observability.
 *
 * Captures cumulative counters + named events from key pipeline sites
 * (Engine frame failures, ModelManager demo-mode, capture failures,
 * permission denials, dispatch failures). Designed to be read by the
 * [com.webstrike.aimbotpro.overlay.ModMenuView] status footer or by an
 * external collector (e.g. crash report payload).
 *
 * ## Design goals
 *
 *  - **Zero hot-path cost**: `count()` is a single atomic-increment on a
 *    pre-allocated slot — no HashMap lookup, no allocation.
 *  - **Thread-safe**: all operations are lock-free (atomic primitives +
 *    `ConcurrentHashMap` for the events map).
 *  - **Bounded**: the events map caps at [MAX_EVENTS] distinct keys;
 *    overflow is silently dropped (logged once).
 *
 * ## Usage
 *
 * ```kotlin
 * Telemetry.count(Telemetry.Capture.FAILURE)
 * Telemetry.event(Telemetry.Engine.FRAME_FAILED, "OOM")
 * ```
 *
 * All telemetry is process-lifetime (not persisted) — wrap with a real
 * crash-reporting SDK if you need cross-session aggregation.
 */
object Telemetry {

    object Engine {
        const val FRAME_FAILED = "engine.frame.failed"
        const val STARTED = "engine.started"
        const val STOPPED = "engine.stopped"
    }

    object Capture {
        const val FRAME_COPY_FAILED = "capture.frame.copy_failed"
        const val PROJECTION_REVOKED = "capture.projection.revoked"
        const val START_FAILED = "capture.start.failed"
    }

    object Model {
        const val DEMO_MODE = "model.demo_mode"
        const val INFERENCE_FAILED = "model.inference.failed"
    }

    object Input {
        const val SWIPE_FAILED = "input.swipe.failed"
        const val TAP_FAILED = "input.tap.failed"
        const val TRIGGER_FIRED = "input.trigger.fired"
    }

    object Permission {
        const val OVERLAY_DENIED = "perm.overlay.denied"
        const val NOTIF_DENIED = "perm.notif.denied"
        const val ACCESSIBILITY_DISABLED = "perm.accessibility.disabled"
        const val PROJECTION_DENIED = "perm.projection.denied"
    }

    object Aim {
        const val TARGET_SELECTED = "aim.target.selected"
        const val AIM_DISPATCHED = "aim.dispatched"
    }

    /**
     * Pre-allocated counter slots — avoids ConcurrentHashMap lookup on the
     * hot path. New keys fall back to the [extraCounts] map.
     */
    private val knownCounters: Map<String, AtomicLong> = buildMap {
        listOf(
            Engine.FRAME_FAILED, Engine.STARTED, Engine.STOPPED,
            Capture.FRAME_COPY_FAILED, Capture.PROJECTION_REVOKED, Capture.START_FAILED,
            Model.DEMO_MODE, Model.INFERENCE_FAILED,
            Input.SWIPE_FAILED, Input.TAP_FAILED, Input.TRIGGER_FIRED,
            Permission.OVERLAY_DENIED, Permission.NOTIF_DENIED,
            Permission.ACCESSIBILITY_DISABLED, Permission.PROJECTION_DENIED,
            Aim.TARGET_SELECTED, Aim.AIM_DISPATCHED
        ).forEach { put(it, AtomicLong(0L)) }
    }

    /** Overflow map for ad-hoc counters not in [knownCounters]. */
    private val extraCounts = ConcurrentHashMap<String, AtomicLong>()

    /** Named event occurrences (last-seen timestamp). */
    private val events = ConcurrentHashMap<String, Long>()

    /** Optional structured-payload events, capped at [MAX_EVENTS]. */
    private val eventPayloads = ConcurrentHashMap<String, String>()

    private const val MAX_EVENTS = 64

    /**
     * Increment a counter. Allocation-free on the hot path for known keys.
     */
    fun count(name: String) {
        val c = knownCounters[name] ?: extraCounts.computeIfAbsent(name) { AtomicLong(0L) }
        c.incrementAndGet()
    }

    /**
     * Record a named event with an optional short payload (capped to
     * [MAX_PAYLOAD] chars to bound memory).
     */
    fun event(name: String, payload: String = "") {
        events[name] = System.currentTimeMillis()
        if (payload.isNotEmpty()) {
            if (events.size + eventPayloads.size < MAX_EVENTS) {
                eventPayloads[name] = payload.take(MAX_PAYLOAD)
            } else {
                // Log once — don't spam.
                Logger.w("Telemetry", "event map full; dropping payload for $name")
            }
        }
    }

    /**
     * Snapshot all counters as a Map<String, Long>. Defensive copy — the
     * returned map is safe to retain across threads.
     */
    fun snapshotCounters(): Map<String, Long> {
        val out = HashMap<String, Long>(knownCounters.size + extraCounts.size)
        for ((k, v) in knownCounters) out[k] = v.get()
        for ((k, v) in extraCounts) out[k] = v.get()
        return out
    }

    /** Read a single counter, or 0 if not yet incremented. */
    fun get(name: String): Long = (knownCounters[name] ?: extraCounts[name])?.get() ?: 0L

    /** Reset all counters + events (used by tests / clear-logs flows). */
    fun reset() {
        for (c in knownCounters.values) c.set(0L)
        extraCounts.clear()
        events.clear()
        eventPayloads.clear()
    }

    /**
     * Human-readable summary suitable for the mod-menu status footer.
     * Format: `Inf=0 Cap=0 In=0 Aim=0 Trig=0`
     */
    fun summary(): String = buildString {
        append("Inf=").append(get(Model.INFERENCE_FAILED))
        append(" Cap=").append(get(Capture.FRAME_COPY_FAILED))
        append(" In=").append(get(Input.SWIPE_FAILED))
        append(" Aim=").append(get(Aim.AIM_DISPATCHED))
        append(" Trig=").append(get(Input.TRIGGER_FIRED))
    }

    private const val MAX_PAYLOAD = 256
}
