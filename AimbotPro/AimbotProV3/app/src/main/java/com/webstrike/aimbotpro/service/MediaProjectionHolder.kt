package com.webstrike.aimbotpro.service

import android.content.Intent
import com.webstrike.aimbotpro.utils.Logger

/**
 * Process-wide holder for the MediaProjection consent result captured by
 * [com.webstrike.aimbotpro.MainActivity] (or any other activity that launches
 * [android.media.projection.MediaProjectionManager.createScreenCaptureIntent]).
 *
 * The foreground service [CoreAimbotService] cannot receive the ActivityResult
 * directly, so the launching activity parks the result here right before
 * dispatching the START intent. The service then consumes it.
 *
 * The data Intent cannot be reused after [android.media.projection.MediaProjectionManager.getMediaProjection]
 * is called once with it — so we clear the holder after the service reads it.
 *
 * Thread-safe: the activity typically writes from the main thread and the
 * service reads on a binder thread.
 */
object MediaProjectionHolder {

    private const val TAG = "MediaProjectionHolder"

    @Volatile var resultCode: Int = 0
    @Volatile var data: Intent? = null

    /** True iff [resultCode] / [data] are populated and the result is non-null. */
    val hasResult: Boolean
        get() = data != null

    /**
     * Park a result. `resultCode` should be the value returned by the
     * `ActivityResultContracts.StartActivityForResult` callback (typically
     * `Activity.RESULT_OK = -1` on user consent).
     */
    fun set(resultCode: Int, data: Intent?) {
        synchronized(this) {
            this.resultCode = resultCode
            this.data = data
        }
        Logger.d(TAG, "Projection result parked (code=$resultCode, hasData=${data != null})")
    }

    /**
     * Atomically read and consume the stored result. After this returns, the
     * holder is empty — the caller must keep the returned Intent alive for
     * the lifetime of the resulting [android.media.projection.MediaProjection].
     *
     * **Why clear on consume** (v4): Android 14+ (API 34) requires a fresh
     * user-consent grant for each new MediaProjection session. Even on older
     * Android versions, re-using a cached Intent after [getMediaProjection]
     * was called once with it leads to silent failures on the second start
     * (the system returns null from `getMediaProjection`). Clearing the holder
     * here forces the next [startPipeline] to re-request consent, which is
     * the documented Android 14+ contract.
     */
    fun consume(): Pair<Int, Intent?> = synchronized(this) {
        val pair = resultCode to data
        resultCode = 0
        data = null
        Logger.d(TAG, "Projection result consumed (code=${pair.first}); holder cleared")
        pair
    }

    /** Drop any parked result without consuming. Used on STOP / cleanup. */
    fun clear() {
        synchronized(this) {
            resultCode = 0
            data = null
        }
    }
}
