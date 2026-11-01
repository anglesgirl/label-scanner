package com.anglesgirl.labelscanner.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.anglesgirl.labelscanner.model.TrayCodeParser
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Live pallet-code detection. A stable candidate triggers a full-resolution photo for existing recognition. */
class LiveTrayAnalyzer(
    private val onProgress: (LiveTrayConsensus.Progress) -> Unit,
    private val onSuccess: (String) -> Unit,
) : ImageAnalysis.Analyzer {
    private val worker = Executors.newSingleThreadExecutor()
    private val textRecognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    private val consensus = LiveTrayConsensus()
    private val busy = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private val lastFrameAt = AtomicLong(0)

    fun reset() {
        consensus.reset()
        paused.set(false)
    }

    fun pause() {
        paused.set(true)
    }

    override fun analyze(image: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (!acceptFrame(now)) {
            image.close()
            return
        }
        val bitmap = try {
            val source = image.toBitmap()
            val crop = image.cropRect
            val cropped = Bitmap.createBitmap(source, crop.left, crop.top, crop.width(), crop.height())
            if (cropped !== source) source.recycle()
            val rotation = image.imageInfo.rotationDegrees
            if (rotation == 0) cropped else Bitmap.createBitmap(
                cropped, 0, 0, cropped.width, cropped.height,
                Matrix().apply { postRotate(rotation.toFloat()) }, true,
            ).also { if (it !== cropped) cropped.recycle() }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot copy live preview frame", e)
            busy.set(false)
            null
        } finally {
            image.close()
        } ?: return
        submit(bitmap)
    }

    /** Takes ownership of one USB-camera bitmap. */
    fun analyzeBitmap(bitmap: Bitmap) {
        if (!acceptFrame(SystemClock.elapsedRealtime())) {
            bitmap.recycle()
            return
        }
        submit(bitmap)
    }

    private fun acceptFrame(now: Long): Boolean {
        if (closed.get() || paused.get() || now - lastFrameAt.get() < FRAME_INTERVAL_MS ||
            !busy.compareAndSet(false, true)) return false
        lastFrameAt.set(now)
        return true
    }

    private fun submit(bitmap: Bitmap) {
        try {
            worker.execute {
                var recycleHere = true
                try {
                    if (!active()) return@execute
                    // Keep every distinct decoded value; candidate selection follows the same
                    // explicit pallet parser used after a manual photo.
                    val barcodes = ZxingDecoder.decode(bitmap, scale = 1f)
                    var ocrText = ""
                    if (TrayCodeParser.preferred(TrayCodeParser.candidates(barcodes, "")) == null) {
                        val task = textRecognizer.process(InputImage.fromBitmap(bitmap, 0))
                        ocrText = try {
                            Tasks.await(task, OCR_TIMEOUT_SECONDS, TimeUnit.SECONDS).text.orEmpty()
                        } catch (e: Exception) {
                            if (!task.isComplete) {
                                recycleHere = false
                                task.addOnCompleteListener(Executor { it.run() }) { bitmap.recycle() }
                            }
                            Log.w(TAG, "Live OCR unavailable; barcode candidates remain available", e)
                            ""
                        }
                    }
                    if (!active()) return@execute
                    val progress = consensus.observe(barcodes, ocrText)
                    Log.i(TAG, "barcodes=$barcodes candidates=${progress.candidateCount} preferred=${progress.preferredCode}")
                    onProgress(progress)
                    val result = progress.result
                    if (result != null && paused.compareAndSet(false, true)) onSuccess(result)
                } catch (e: Exception) {
                    Log.w(TAG, "Live pallet recognition failed", e)
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

    private fun active() = !closed.get() && !paused.get()

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        paused.set(true)
        worker.execute { textRecognizer.close() }
        worker.shutdown()
    }

    companion object {
        private const val TAG = "LiveTray"
        private const val FRAME_INTERVAL_MS = 260L
        private const val OCR_TIMEOUT_SECONDS = 2L
    }
}
