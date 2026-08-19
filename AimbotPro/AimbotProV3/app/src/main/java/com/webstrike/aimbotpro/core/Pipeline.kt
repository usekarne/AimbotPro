package com.webstrike.aimbotpro.core

/**
 * Lightweight per-session statistics + state holder used by services for
 * status reporting (e.g. surfaced via the foreground notification or the
 * mod menu's status footer).
 *
 * Distinct from [com.webstrike.aimbotpro.utils.PerformanceMonitor] — that
 * object tracks rolling FPS + per-stage latency for the inference hot path.
 * This class tracks cumulative counters for the lifetime of an Engine
 * session, intended for human-readable summaries (e.g. "Frames: 1234,
 * Detections: 5678, Aim: 90, Triggers: 12").
 *
 * Thread-safety: all four counters are `@Volatile`. The read-modify-write
 * in [recordFrame] is guarded by `synchronized(this)` so concurrent
 * callers (e.g. the Engine inference coroutine + a service binder thread
 * reading [summary]) observe a consistent snapshot.
 *
 * Lifecycle: a single instance is typically owned by
 * [com.webstrike.aimbotpro.service.CoreAimbotService] and re-created on
 * each START / STOP cycle. Call [reset] when re-using the instance across
 * sessions.
 */
class Pipeline {

    @Volatile
    var totalFrames: Long = 0
        private set

    @Volatile
    var totalDetections: Long = 0
        private set

    @Volatile
    var totalAimAdjustments: Long = 0
        private set

    @Volatile
    var totalTriggers: Long = 0
        private set

    /**
     * Record one frame's worth of activity. Each argument is added atomically
     * to its respective counter.
     *
     * @param detections      number of detections observed this frame
     *                        (post-NMS, post-class-filter).
     * @param aimAdjustments  number of aim-correction dispatches issued
     *                        this frame (typically 0 or 1).
     * @param triggers        number of trigger-bot fires dispatched this
     *                        frame (typically 0 or 1).
     */
    fun recordFrame(detections: Int, aimAdjustments: Int, triggers: Int) {
        synchronized(this) {
            totalFrames += 1L
            totalDetections += detections.toLong()
            totalAimAdjustments += aimAdjustments.toLong()
            totalTriggers += triggers.toLong()
        }
    }

    /**
     * Zero all counters. Safe to call from any thread.
     */
    fun reset() {
        synchronized(this) {
            totalFrames = 0L
            totalDetections = 0L
            totalAimAdjustments = 0L
            totalTriggers = 0L
        }
    }

    /**
     * Human-readable one-line summary suitable for logcat / notification /
     * mod-menu status footer.
     *
     * Example: `Frames=1234 Detections=5678 Aim=90 Triggers=12`
     */
    fun summary(): String =
        "Frames=$totalFrames Detections=$totalDetections Aim=$totalAimAdjustments Triggers=$totalTriggers"
}
