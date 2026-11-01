package com.anglesgirl.labelscanner.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Geometry is a focus hint, never a prerequisite for decoding or OCR. One task = one real frame. */
class LiveLabelAnalyzer(
    private val onProgress: (LiveSampleConsensus.Progress) -> Unit,
    private val onBarcode: () -> Unit,
    private val onFocusHint: (Float, Float) -> Unit,
    private val onSuccess: (BurstConsensus.Result) -> Unit,
    private val onTimeout: () -> Unit,
    private val requireBarcodeBeforeOcr: Boolean = true,
    private val useChineseTextRecognition: Boolean = false,
) : ImageAnalysis.Analyzer {
    private val worker = Executors.newSingleThreadExecutor()
    private val client = if (useChineseTextRecognition) {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    } else {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    private val busy = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private val generation = AtomicLong(0)
    private var activeGeneration = -1L
    private val consensus = LiveSampleConsensus(headerOnly = !requireBarcodeBeforeOcr)
    private var lastFrameAt = 0L
    private var startedAt = 0L
    private var hasBarcode = false
    private var focusHintSent = false

    fun reset() {
        generation.incrementAndGet()
        paused.set(false)
    }

    fun pause() { paused.set(true) }

    /** Queue expiry behind the in-flight sample: a fourth matching result always wins. */
    fun requestTimeout() {
        val token = generation.get()
        if (closed.get()) return
        runCatching {
            worker.execute {
                if (current(token) && paused.compareAndSet(false, true)) onTimeout()
            }
        }
    }

    override fun analyze(image: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (closed.get() || paused.get() || now - lastFrameAt < 180 || !busy.compareAndSet(false, true)) {
            image.close()
            return
        }
        lastFrameAt = now
        val token = generation.get()
        var owned: Bitmap? = null
        val bitmap = try {
            owned = image.toBitmap()
            val source = requireNotNull(owned)
            val crop = image.cropRect
            val cropped = Bitmap.createBitmap(source, crop.left, crop.top, crop.width(), crop.height())
            if (cropped !== source) source.recycle()
            owned = cropped
            val degrees = image.imageInfo.rotationDegrees
            val upright = if (degrees == 0) cropped else Bitmap.createBitmap(cropped, 0, 0,
                cropped.width, cropped.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
            if (upright !== cropped) cropped.recycle()
            owned = upright
            upright
        } catch (e: Exception) {
            owned?.recycle()
            busy.set(false)
            Log.w("LiveLabel", "Cannot copy preview", e)
            null
        } finally { image.close() } ?: return
        submit(bitmap, token)
    }

    /** Takes ownership of one real USB frame, using exactly the phone consensus pipeline. */
    fun analyzeBitmap(bitmap: Bitmap) {
        val now = SystemClock.elapsedRealtime()
        if (closed.get() || paused.get() || now - lastFrameAt < 180 || !busy.compareAndSet(false, true)) {
            bitmap.recycle()
            return
        }
        lastFrameAt = now
        submit(bitmap, generation.get())
    }

    private fun submit(bitmap: Bitmap, token: Long) {
        try {
            worker.execute {
                var recycleHere = true
                try {
                    if (!current(token)) return@execute
                    if (activeGeneration != token) {
                        consensus.reset()
                        startedAt = if (requireBarcodeBeforeOcr) 0L else SystemClock.elapsedRealtime()
                        hasBarcode = false
                        focusHintSent = false
                        activeGeneration = token
                    }
                    if (!focusHintSent) {
                        val scale = 480f / maxOf(bitmap.width, bitmap.height)
                        val small = Bitmap.createScaledBitmap(bitmap,
                            (bitmap.width * scale).toInt().coerceAtLeast(1),
                            (bitmap.height * scale).toInt().coerceAtLeast(1), true)
                        try {
                            val pixels = IntArray(small.width * small.height)
                            small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
                            LabelContentLocator.locateCore(pixels, small.width, small.height)?.let {
                                focusHintSent = true
                                if (current(token)) onFocusHint((it.left + it.right) / 2, (it.top + it.bottom) / 2)
                            }
                        } finally { if (small !== bitmap) small.recycle() }
                    }
                    // All symbols in the frame are retained; no first-code selection.
                    val codes = ZxingDecoder.decode(bitmap, scale = 1f)
                    Log.i("LiveLabel", "barcodes=$codes")
                    if (!current(token)) return@execute
                    if (codes.isNotEmpty() && !hasBarcode) {
                        hasBarcode = true
                        if (startedAt == 0L) startedAt = SystemClock.elapsedRealtime()
                        onBarcode()
                    }
                    // OCR starts only AFTER a real barcode decode, never after a rectangle detector.
                    if (!hasBarcode && requireBarcodeBeforeOcr) return@execute
                    if (startedAt != 0L && SystemClock.elapsedRealtime() - startedAt >= 15_000L) {
                        if (current(token) && paused.compareAndSet(false, true)) onTimeout()
                        return@execute
                    }
                    if (codes.isEmpty() && requireBarcodeBeforeOcr) return@execute
                    val task = client.process(InputImage.fromBitmap(bitmap, 0))
                    val text = try {
                        Tasks.await(task, 3, TimeUnit.SECONDS).text.orEmpty()
                    } catch (e: Exception) {
                        if (!task.isComplete) {
                            recycleHere = false
                            task.addOnCompleteListener(java.util.concurrent.Executor { it.run() }) { bitmap.recycle() }
                        }
                        Log.w("LiveLabel", "OCR sample failed", e)
                        ""
                    }
                    if (!current(token)) return@execute
                    val progress = consensus.observe(codes, text)
                    Log.i("LiveLabel", "sample=${progress.sampleCount} matches=${progress.matchingCount} text=$text")
                    onProgress(progress)
                    val result = progress.result
                    if (result != null && current(token) && paused.compareAndSet(false, true)) onSuccess(result)
                    else if (progress.sampleCount >= 32 || SystemClock.elapsedRealtime() - startedAt >= 15_000L) {
                        if (current(token) && paused.compareAndSet(false, true)) onTimeout()
                    }
                } catch (e: Exception) {
                    Log.w("LiveLabel", "Sample failed", e)
                    if (current(token) && startedAt != 0L && SystemClock.elapsedRealtime() - startedAt >= 15_000L &&
                        paused.compareAndSet(false, true)) onTimeout()
                } finally {
                    if (recycleHere) bitmap.recycle()
                    busy.set(false)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            bitmap.recycle()
            busy.set(false)
        }
    }

    private fun current(token: Long) = !closed.get() && !paused.get() && generation.get() == token

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        generation.incrementAndGet()
        worker.execute { client.close() }
        worker.shutdown()
    }
}
