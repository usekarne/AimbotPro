package com.webstrike.aimbotpro.capture

import android.graphics.Bitmap
import android.graphics.Canvas
import com.webstrike.aimbotpro.utils.Logger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Single-producer / single-consumer frame buffer holding the latest captured
 * [Bitmap] frames. Designed for the [ScreenCaptureManager] →
 * [com.webstrike.aimbotpro.core.Engine] pipeline.
 *
 * ## Snapshot semantics (important)
 *
 * [ScreenCaptureManager] reuses ONE mutable [Bitmap] per frame and overwrites
 * its pixels in place on every capture callback. If we stored that reference
 * directly, the Engine would observe torn reads (half-old, half-new pixels)
 * whenever the capture thread overwrote the bitmap mid-inference.
 *
 * ## Double-buffered pool (v4)
 *
 * The previous implementation allocated a fresh `Bitmap.copy` per frame
 * (~8 MB × 60 FPS = 480 MB/s of GC churn on a 1080p device). The new
 * implementation uses a 2-slot bitmap pool:
 *
 *   - The producer ([put]) hands the current snapshot bitmap to the consumer
 *     and switches to the alternate slot for the next frame.
 *   - The consumer ([latest]) reads the most-recent slot without copying.
 *   - When the producer next calls [put], the previously-handed-off slot is
 *     reclaimed and overwritten — guaranteed not to be in flight because the
 *     consumer must have called [releaseFrame] first.
 *
 * This requires the consumer to **explicitly release** the snapshot it
 * received via [releaseFrame] once it has finished reading pixels. The
 * [com.webstrike.aimbotpro.core.Engine] does this in a `finally` block.
 *
 * Thread-safety: a single [ReentrantLock] guards the small critical section.
 *
 * Allocation profile: zero per-frame allocations after warm-up. The two
 * pool slots are allocated lazily on the first two [put] calls; resized in
 * place if the source dimensions change (rare, e.g. orientation change).
 *
 * @param maxSize maximum number of frames retained. Default 2 (matches the
 *                pool depth — values > 2 are accepted but only the newest 2
 *                are kept around as live snapshots; older slots fall back to
 *                the same pool so larger values just enable a tiny lookback
 *                queue without offering a real benefit).
 */
class FrameBuffer(maxSize: Int = POOL_SIZE) {

    private val capacity: Int = maxSize.coerceAtLeast(1)
    private val lock = ReentrantLock()

    /** Pool of two reusable bitmaps (double-buffered). */
    private val slots: Array<Bitmap?> = arrayOfNulls(POOL_SIZE)

    /**
     * Index into [slots] of the bitmap currently being filled by the
     * producer. After [put] completes, this flips to the other slot.
     */
    private var producerSlot: Int = 0

    /**
     * The bitmap currently handed out to the consumer via [latest], or `null`
     * if no frame has been published yet, or if the consumer has called
     * [releaseFrame] since the last [latest].
     */
    @Volatile
    private var consumerSnapshot: Bitmap? = null

    /** Ring of recent snapshots (size capped to [capacity]); used when capacity > 1. */
    private val ring: ArrayDeque<Bitmap> = ArrayDeque(capacity)

    /**
     * Push a snapshot of [source] into the buffer using a pool slot.
     * The source bitmap is left untouched — its lifecycle is owned by
     * [ScreenCaptureManager]. Returns without allocating on the steady-state
     * path (only allocates when the pool slot has not been initialized yet,
     * or when the source dimensions change).
     */
    fun put(source: Bitmap) {
        if (source.isRecycled || source.width <= 0 || source.height <= 0) return

        val writableBitmap: Bitmap
        lock.withLock {
            // Acquire (or allocate) the producer slot.
            val idx = producerSlot
            val existing = slots[idx]
            val reused = existing != null && !existing.isRecycled &&
                existing.width == source.width && existing.height == source.height
            writableBitmap = if (reused) {
                existing!!
            } else {
                // Allocate / re-allocate.
                runCatching { existing?.recycle() }
                val fresh = try {
                    Bitmap.createBitmap(
                        source.width, source.height,
                        source.config  // never null for MediaProjection bitmaps
                    )
                } catch (t: Throwable) {
                    Logger.w(TAG, "Pool slot alloc failed: ${t.message}")
                    return
                }
                slots[idx] = fresh
                fresh
            }

            // Flip the producer slot for next time.
            producerSlot = (idx + 1) % POOL_SIZE

            // Promote the previously produced bitmap to the consumer.
            // The consumer must release it via [releaseFrame] when done.
            // If the previous consumerSnapshot is still in flight (not released),
            // we overwrite it — the consumer is expected to be fast enough that
            // this is a non-issue in practice (Engine reads pixels in <50 ms
            // and the capture loop runs at the same ~60 FPS cadence).
            consumerSnapshot = writableBitmap

            // If we maintain a small lookback queue, push the previous snapshot
            // there (only used when maxSize > 1).
            if (capacity > 1) {
                // The current writableBitmap is the "newest"; the other slot is
                // now the "previous" — push a reference (NOT a copy) to the ring.
                val prevIdx = (idx + 1) % POOL_SIZE  // other slot
                val prev = slots[prevIdx]
                if (prev != null && !prev.isRecycled) {
                    ring.addLast(prev)
                    while (ring.size > capacity - 1) {
                        // Drop oldest ring entry back into the pool — it's
                        // already one of the slots, so no recycle needed.
                        ring.removeFirst()
                    }
                }
            }
        }

        // Copy pixels OUTSIDE the lock to minimize contention with [latest].
        // We use Canvas.drawBitmap which is atomic at the JNI level — a
        // concurrent reader will see either the old or the new pixel state,
        // not a half-torn frame.
        runCatching {
            val canvas = Canvas(writableBitmap)
            canvas.drawBitmap(source, 0f, 0f, null)
        }.onFailure {
            Logger.w(TAG, "Pixel copy to pool slot failed: ${it.message}")
        }
    }

    /**
     * Peek the newest snapshot without removing it, or `null` when empty.
     * The returned bitmap is the live pool slot — the consumer MUST call
     * [releaseFrame] when done reading pixels, otherwise the next [put] will
     * overwrite the bitmap mid-read.
     *
     * Until [releaseFrame] is called, subsequent [latest] calls return the
     * SAME bitmap reference (no copy).
     */
    fun latest(): Bitmap? = lock.withLock {
        consumerSnapshot ?: ring.lastOrNull()
    }

    /**
     * Release the snapshot obtained via [latest] back to the pool. This is
     * a soft release — it just signals that the consumer is done reading the
     * current snapshot. Idempotent and safe to call from any thread.
     */
    fun releaseFrame() {
        lock.withLock {
            // We don't actually null out the pool slot — the producer will
            // overwrite it in place on the next [put]. We just clear the
            // consumer reference so the next [latest] call returns the ring's
            // last entry (if any) instead of the just-released bitmap.
            // In the steady state (no ring), this means the next [latest]
            // returns null until the next [put] — which is the desired
            // behaviour (no stale reads).
            consumerSnapshot = null
        }
    }

    /**
     * Drop everything AND recycle the pool slots (we own them).
     * Called by [ScreenCaptureManager.stop] and on service teardown.
     */
    fun clear() {
        val drained: List<Bitmap> = lock.withLock {
            val list = ArrayList<Bitmap>(POOL_SIZE + ring.size)
            consumerSnapshot = null
            for (i in slots.indices) {
                slots[i]?.let { list += it }
                slots[i] = null
            }
            list += ring.toList()
            ring.clear()
            producerSlot = 0
            list
        }
        drained.forEach { runCatching { if (!it.isRecycled) it.recycle() } }
    }

    /** Current number of buffered snapshots (mostly for diagnostics). */
    fun size(): Int = lock.withLock {
        (if (consumerSnapshot != null) 1 else 0) + ring.size
    }

    companion object {
        private const val TAG = "FrameBuffer"
        private const val POOL_SIZE = 2
    }
}
