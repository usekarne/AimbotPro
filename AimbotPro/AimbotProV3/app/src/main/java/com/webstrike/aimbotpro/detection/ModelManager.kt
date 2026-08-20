package com.webstrike.aimbotpro.detection

import android.content.Context
import com.webstrike.aimbotpro.Constants
import com.webstrike.aimbotpro.utils.Logger
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
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
 * ## GPU acceleration (v4.1 fix)
 * Uses [CompatibilityList] to check device compatibility BEFORE attempting
 * to create a [GpuDelegate]. On incompatible devices, falls back directly to
 * a 4-thread CPU interpreter. This avoids the silent-failure pattern where
 * the old no-arg GpuDelegate() constructor would create a delegate that
 * then fails during the first inference call.
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
            val appCtx = context.applicationContext
            labels = loadLabels(appCtx)
            val loaded = loadInterpreter(appCtx)
            interpreter = loaded
            demoMode = loaded == null
            initialized = true
            Logger.w(
                TAG,
                "init done — demoMode=$demoMode, labels=${labels.size}, " +
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

        // Try GPU delegate on compatible devices.
        val gpuDelegate: GpuDelegate? = try {
            val compatList = CompatibilityList()
            if (compatList.isDelegateSupportedOnThisDevice) {
                val options = compatList.bestOptionsForThisDevice
                val delegate = GpuDelegate(options)
                Logger.w(TAG, "GPU delegate created (bestOptions)")
                delegate
            } else {
                Logger.w(TAG, "GPU not supported — using CPU")
                null
            }
        } catch (t: Throwable) {
            Logger.w(TAG, "GPU delegate failed: ${t.message}")
            null
        }

        val options: Interpreter.Options = Interpreter.Options().apply {
            if (gpuDelegate != null) {
                addDelegate(gpuDelegate)
                setNumThreads(1)
            } else {
                setNumThreads(4)
            }
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
