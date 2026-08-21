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
 * and [isDemoMode] returns `true`.
 *
 * ## GPU acceleration
 * Tries the no-arg [GpuDelegate] constructor first. If it throws,
 * falls back to a 4-thread CPU interpreter. This is the most portable
 * approach across TFLite 2.14.x device variants.
 */
object ModelManager {

    private const val TAG = "ModelManager"

    private val lock = ReentrantLock()

    @Volatile private var interpreter: Interpreter? = null
    @Volatile private var labels: List<String> = emptyList()
    @Volatile private var demoMode: Boolean = false
    @Volatile private var initialized: Boolean = false

    fun init(context: Context) {
        lock.withLock {
            if (initialized) return@withLock
            val forceDemo = com.webstrike.aimbotpro.config.FeatureFlags.forceDemo
            val appCtx = context.applicationContext
            labels = loadLabels(appCtx)
            val loaded = loadInterpreter(appCtx)
            interpreter = loaded
            demoMode = loaded == null || forceDemo
            initialized = true
            Logger.w(
                TAG,
                "init done — demoMode=$demoMode, forceDemo=$forceDemo, labels=${labels.size}, " +
                    "hasInterpreter=${interpreter != null}"
            )
        }
    }

    fun getInterpreter(): Interpreter? = interpreter

    fun getLabels(): List<String> = labels

    fun isDemoMode(): Boolean = demoMode

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
            Logger.e(TAG, "Model file '$assetPath' not found in assets — entering demo mode.")
            return null
        } catch (t: Throwable) {
            Logger.e(TAG, "Failed to open model asset '$assetPath'", t)
            return null
        }

        Logger.w(TAG, "Model asset loaded: ${modelBuffer.capacity()} bytes")

        // Try GPU delegate — if construction fails, fall back to CPU.
        var gpuDelegate: GpuDelegate? = null
        val options: Interpreter.Options = try {
            val delegate = GpuDelegate()
            gpuDelegate = delegate
            Logger.w(TAG, "GPU delegate created")
            Interpreter.Options()
                .addDelegate(delegate)
                .setNumThreads(1)
        } catch (t: Throwable) {
            Logger.w(TAG, "GPU delegate failed: ${t.message}")
            runCatching { gpuDelegate?.close() }
            gpuDelegate = null
            Interpreter.Options().setNumThreads(4)
        }

        return try {
            val interp = Interpreter(modelBuffer, options)
            val inputShape = interp.getInputTensor(0).shape()
            val outputShape = interp.getOutputTensor(0).shape()
            Logger.w(
                TAG,
                "Interpreter OK — input=${inputShape.toList()}, output=${outputShape.toList()}"
            )
            interp
        } catch (t: Throwable) {
            Logger.e(TAG, "Interpreter construction failed for '$modelFileName'", t)
            runCatching { gpuDelegate?.close() }
            null
        }
    }

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
            Logger.w(TAG, "Labels file '$assetPath' not found — empty labels")
            emptyList()
        } catch (t: Throwable) {
            Logger.e(TAG, "Failed to load labels '$assetPath'", t)
            emptyList()
        }
    }
}
