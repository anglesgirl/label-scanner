package com.anglesgirl.labelscanner.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Scans every live frame while the operator moves down one printed code page. */
internal class LiveCodePageAnalyzer(
    private val onProgress: (LiveCodePageConsensus.Progress) -> Unit,
) : ImageAnalysis.Analyzer {
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "live-code-page").apply { isDaemon = true }
    }
    private val closed = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private val busy = AtomicBoolean(false)
    private val lastFrameAt = AtomicLong(0L)
    private val consensus = LiveCodePageConsensus()

    override fun analyze(image: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (closed.get() || paused.get() || now - lastFrameAt.get() < FRAME_INTERVAL_MS ||
            !busy.compareAndSet(false, true)) {
            image.close()
            return
        }
        lastFrameAt.set(now)
        var owned: Bitmap? = null
        val bitmap = try {
            owned = image.toBitmap()
            val source = requireNotNull(owned)
            val crop = image.cropRect
            val cropped = Bitmap.createBitmap(source, crop.left, crop.top, crop.width(), crop.height())
            if (cropped !== source) source.recycle()
            owned = cropped
            val degrees = image.imageInfo.rotationDegrees
            val upright = if (degrees == 0) cropped else Bitmap.createBitmap(
                cropped, 0, 0, cropped.width, cropped.height,
                Matrix().apply { postRotate(degrees.toFloat()) }, true,
            )
            if (upright !== cropped) cropped.recycle()
            owned = upright
            upright
        } catch (e: Exception) {
            owned?.recycle()
            busy.set(false)
            Log.w(TAG, "Cannot copy preview frame", e)
            null
        } finally {
            image.close()
        } ?: return
        submit(bitmap)
    }

    /** USB frames transfer bitmap ownership to this analyzer. */
    fun analyzeBitmap(bitmap: Bitmap) {
        val now = SystemClock.elapsedRealtime()
        if (closed.get() || paused.get() || now - lastFrameAt.get() < FRAME_INTERVAL_MS ||
            !busy.compareAndSet(false, true)) {
            bitmap.recycle()
            return
        }
        lastFrameAt.set(now)
        submit(bitmap)
    }

    private fun submit(bitmap: Bitmap) {
        try {
            worker.execute {
                try {
                    if (closed.get() || paused.get()) return@execute
                    // Preview frames are decoded at 1x to keep the live sweep responsive. Dense
                    // codes that need optical detail still have the full-resolution photo path.
                    val codes = ZxingDecoder.decode(bitmap, scale = 1f)
                    if (!closed.get() && !paused.get()) onProgress(consensus.observe(codes))
                } catch (e: Exception) {
                    // A blurry, moving, or unreadable frame is a miss, not a reset of prior votes.
                    Log.w(TAG, "Live code frame failed", e)
                } finally {
                    bitmap.recycle()
                    busy.set(false)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            bitmap.recycle()
            busy.set(false)
        }
    }

    /** Pause input, then snapshot after the in-flight decode finishes. */
    fun finish(onResult: (List<String>) -> Unit) {
        if (closed.get()) return
        paused.set(true)
        runCatching {
            worker.execute {
                if (!closed.get()) onResult(consensus.confirmedCodes())
            }
        }
    }

    fun reset() {
        if (closed.get()) return
        paused.set(true)
        runCatching {
            worker.execute {
                consensus.reset()
                paused.set(false)
            }
        }
    }

    fun pause() { paused.set(true) }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        paused.set(true)
        worker.shutdown()
    }

    private companion object {
        const val TAG = "LiveCodePage"
        const val FRAME_INTERVAL_MS = 240L
    }
}
