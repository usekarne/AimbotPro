package com.webstrike.aimbotpro.detection

import android.content.Context
import com.webstrike.aimbotpro.Constants
import com.webstrike.aimbotpro.utils.Logger
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Singleton managing the TFLite [Interpreter] and COCO label set lifecycle.
 *
 * Thread-safe. On [init], loads the model file from `assets/models/` and the
 * labels file from `assets/labels/`. If the model file is missing or cannot be
 * loaded, the manager enters **demo mode**: [getInterpreter] returns `null`
 * and [isDemoMode] returns `true`. The rest of the app can keep running on
 * simulated detections so the UI / overlay / services pipeline is fully
 * exercisable without a bundled `.tflite`.
 *
 * GPU acceleration: tries [GpuDelegate] with device-tuned options from
 * [CompatibilityList]; falls back to a 2-thread CPU interpreter on any failure.
 */
object ModelManager {

    private const val TAG = "ModelManager"

    private val lock = ReentrantLock()

    @Volatile private var interpreter: Interpreter? = null
    @Volatile private var labels: List<String> = emptyList()
    @Volatile private var demoMode: Boolean = false
    @Volatile private var initialized: Boolean = false

    /**
     * Must be called once at app startup (idempotent — repeat calls are no-ops).
     * Uses [context] only to reach the [android.content.res.AssetManager]; the
     * application context is captured so the caller may safely pass an Activity.
     */
    fun init(context: Context) {
        lock.withLock {
            if (initialized) return@withLock
            val appCtx = context.applicationContext
            labels = loadLabels(appCtx)
            val loaded = loadInterpreter(appCtx)
            interpreter = loaded
            demoMode = loaded == null
            initialized = true
            Logger.i(
                TAG,
                "init done — demoMode=$demoMode, labels=${labels.size}, " +
                    "hasInterpreter=${interpreter != null}"
            )
        }
    }

    /** Returns the live interpreter, or `null` when in demo mode. */
    fun getInterpreter(): Interpreter? = interpreter

    /** Returns the parsed label list (may be empty if labels file was missing). */
    fun getLabels(): List<String> = labels

    /** True when no real model is loaded — callers should serve simulated data. */
    fun isDemoMode(): Boolean = demoMode

    /**
     * Release the interpreter and reset all state. Safe to call repeatedly;
     * allows re-[init] afterwards (used by tests / hot-reload flows).
     */
    fun release() {
        lock.withLock {
            val interp = interpreter
            interpreter = null
            labels = emptyList()
            demoMode = false
            initialized = false
            runCatching { interp?.close() }
        }
    }

    // ---------- internals ----------

    private fun loadInterpreter(context: Context): Interpreter? {
        val modelFileName = Constants.Detection.DEFAULT_MODEL_NAME
        val assetPath = "models/$modelFileName"

        val modelBuffer: MappedByteBuffer = try {
            loadModelAsset(context, assetPath)
        } catch (e: java.io.FileNotFoundException) {
            Logger.w(TAG, "Model file '$assetPath' not found in assets — entering demo mode.")
            return null
        } catch (t: Throwable) {
            Logger.e(TAG, "Failed to open model asset '$assetPath'", t)
            return null
        }

        var gpuDelegate: GpuDelegate? = null
        val options: Interpreter.Options = try {
            // Use the no-arg GpuDelegate constructor which works across all
            // TFLite GPU delegate versions. CompatibilityList.bestOptionsForThisDevice
            // returns a GpuDelegateFactory.Options that is not on the runtime
            // classpath in some TFLite 2.14 packaging configurations.
            val delegate = GpuDelegate()
            gpuDelegate = delegate
            Logger.i(TAG, "GPU delegate attached for '$modelFileName'.")
            Interpreter.Options()
                .addDelegate(delegate)
                .setNumThreads(1)
        } catch (t: Throwable) {
            Logger.w(TAG, "GPU delegate setup threw — using CPU: ${t.message}")
            val delegate = gpuDelegate
            gpuDelegate = null
            runCatching { delegate?.close() }
            Interpreter.Options().setNumThreads(2)
        }

        return try {
            Interpreter(modelBuffer, options)
        } catch (t: Throwable) {
            Logger.e(TAG, "Interpreter construction failed for '$modelFileName'", t)
            val delegate = gpuDelegate
            runCatching { delegate?.close() }
            null
        }
    }

    /**
     * Memory-map the TFLite model file from the assets directory. Required
     * because [Interpreter] needs a [MappedByteBuffer] (random access) — it
     * will not accept a streamed InputStream.
     */
    private fun loadModelAsset(context: Context, assetPath: String): MappedByteBuffer {
        val fd = context.assets.openFd(assetPath)
        FileInputStream(fd.fileDescriptor).use { fis ->
            val channel = fis.channel
            return channel.map(
                FileChannel.MapMode.READ_ONLY,
                fd.startOffset,
                fd.declaredLength
            )
        }
    }

    private fun loadLabels(context: Context): List<String> {
        val labelsFileName = Constants.Detection.DEFAULT_LABELS_NAME
        val assetPath = "labels/$labelsFileName"
        return try {
            context.assets.open(assetPath).bufferedReader().useLines { seq ->
                seq.map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .toList()
            }
        } catch (e: java.io.FileNotFoundException) {
            Logger.w(TAG, "Labels file '$assetPath' not found — using empty label set.")
            emptyList()
        } catch (t: Throwable) {
            Logger.e(TAG, "Failed to load labels '$assetPath'", t)
            emptyList()
        }
    }
}
