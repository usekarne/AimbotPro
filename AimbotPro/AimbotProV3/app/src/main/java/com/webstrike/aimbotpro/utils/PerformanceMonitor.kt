package com.webstrike.aimbotpro.utils

/**
 * Lightweight performance monitor — captures rolling FPS + per-stage latency.
 *
 * ## Threading contract
 *
 *  - `recordFrame` is called from a **single writer** (the Engine's inference
 *    coroutine). The `@Synchronized` on it was an unnecessary monitor
 *    enter/exit on every frame (60+/sec); it's been dropped in favour of
 *    documenting the single-writer contract.
 *  - `fps` / `averageLatencyMs` / `reset` may be called from any thread and
 *    remain `@Synchronized` to give a consistent snapshot of the ring buffer.
 *
 * If you ever need to call `recordFrame` from multiple writers (e.g. multiple
 * Engine instances), re-add `@Synchronized` — the ring buffer is NOT
 * thread-safe under concurrent writes.
 *
 * Allocation profile: zero per-frame (uses primitive arrays).
 */
object PerformanceMonitor {

    private const val RING_SIZE = 60

    private val frameTimesNs = LongArray(RING_SIZE)
    private val stageDetect = LongArray(RING_SIZE)
    private val stageAim = LongArray(RING_SIZE)
    private val stageInject = LongArray(RING_SIZE)
    private var ringIndex = 0
    @Volatile private var frameCount = 0L

    /**
     * Record one frame's stage timings. **Single-writer contract** — see
     * class KDoc. NOT thread-safe under concurrent writes.
     */
    fun recordFrame(detectNs: Long, aimNs: Long, injectNs: Long) {
        val now = System.nanoTime()
        val idx = (ringIndex + 1) % RING_SIZE
        frameTimesNs[idx] = now
        stageDetect[idx] = detectNs
        stageAim[idx] = aimNs
        stageInject[idx] = injectNs
        ringIndex = idx
        frameCount++
    }

    @Synchronized
    fun fps(): Float {
        if (frameCount < 2) return 0f
        // Walk newest → oldest; compute interval between consecutive samples.
        // ringIndex points at the most-recently written slot, so i=0 is newest.
        var prev = 0L
        var count = 0
        var sum = 0L
        val limit = RING_SIZE.coerceAtMost(frameCount.toInt())
        for (i in 0 until limit) {
            val idx = (ringIndex - i + RING_SIZE) % RING_SIZE
            val ts = frameTimesNs[idx]
            if (ts == 0L) continue
            if (prev != 0L) {
                // prev is the NEWER timestamp, ts is OLDER → positive delta.
                val delta = prev - ts
                if (delta > 0) {
                    sum += delta
                    count++
                }
            }
            prev = ts
        }
        if (count == 0) return 0f
        val avgMs = (sum.toDouble() / count) / 1_000_000.0
        return if (avgMs > 0) (1000.0 / avgMs).toFloat() else 0f
    }

    @Synchronized
    fun averageLatencyMs(): Float {
        if (frameCount == 0L) return 0f
        var sum = 0L
        var count = 0
        val limit = RING_SIZE.coerceAtMost(frameCount.toInt())
        for (i in 0 until limit) {
            val idx = (ringIndex - i + RING_SIZE) % RING_SIZE
            if (stageDetect[idx] == 0L) continue
            sum += stageDetect[idx] + stageAim[idx] + stageInject[idx]
            count++
        }
        if (count == 0) return 0f
        return (sum.toFloat() / count) / 1_000_000f
    }

    @Synchronized
    fun reset() {
        for (i in 0 until RING_SIZE) {
            frameTimesNs[i] = 0L
            stageDetect[i] = 0L
            stageAim[i] = 0L
            stageInject[i] = 0L
        }
        ringIndex = 0
        frameCount = 0L
    }

    /** Snapshot frame count (lifetime-cumulative; safe from any thread). */
    fun frameCount(): Long = frameCount
}
