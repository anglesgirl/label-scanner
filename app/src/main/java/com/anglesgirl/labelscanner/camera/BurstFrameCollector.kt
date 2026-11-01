package com.anglesgirl.labelscanner.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import java.io.File
import java.io.FileOutputStream
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Synchronously samples distinct CameraX frames on the ImageAnalysis executor and writes them to
 * [directory]. The caller retains ownership of each ImageProxy and must close it after offer returns.
 */
class BurstFrameCollector(
    private val directory: File,
    private val onProgress: (Int) -> Unit,
    private val onComplete: (List<File>) -> Unit,
) {
    companion object {
        const val TARGET_FRAMES = 15
        private const val MIN_SAMPLE_INTERVAL_NS = 100_000_000L
        private const val MAX_EDGE = 1920
        private const val JPEG_QUALITY = 90
    }

    private val lock = Any()
    private val files = ArrayList<File>(TARGET_FRAMES)
    private var started = false
    private var closed = false
    private var completionSent = false
    private var filesDelivered = false
    private var lastSampleAtNs = Long.MIN_VALUE
    private var fileSerial = 0L

    /** Starts a fresh sampling round. Calling start while a round is active has no effect. */
    fun start() {
        synchronized(lock) {
            if (closed || started) return
            files.clear()
            started = true
            completionSent = false
            filesDelivered = false
            lastSampleAtNs = Long.MIN_VALUE
        }
    }

    /**
     * Samples the current frame if at least 100 ms have elapsed since the previous saved frame.
     * This method intentionally does not close [image]; an analyzer wrapper can offer it and then
     * pass the same still-open image to other detectors before closing it.
     */
    fun offer(image: ImageProxy) {
        synchronized(lock) {
            if (closed || !started || completionSent || files.size >= TARGET_FRAMES) return
            val sampledAt = SystemClock.elapsedRealtimeNanos()
            if (lastSampleAtNs != Long.MIN_VALUE && sampledAt - lastSampleAtNs < MIN_SAMPLE_INTERVAL_NS) return

            var output: File? = null
            var pendingFile: File? = null
            try {
                if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory) return
                val target = nextFrameFile()
                pendingFile = target
                if (writeFrame(image, target)) output = target else target.delete()
            } catch (_: Exception) {
                try { (output ?: pendingFile)?.delete() } catch (_: Exception) { }
            } catch (_: LinkageError) {
                // A malformed/unavailable camera frame must not terminate ImageAnalysis.
                try { (output ?: pendingFile)?.delete() } catch (_: Exception) { }
            }
            val saved = output ?: return

            files.add(saved)
            lastSampleAtNs = sampledAt
            try {
                // Progress reports the number of successfully saved frames (1..TARGET_FRAMES).
                onProgress(files.size)
            } catch (_: Exception) {
                // Client callback failures must not interrupt collection or analyzer processing.
            }

            // A progress callback is allowed to re-enter this collector (including finish/close).
            if (closed || completionSent) return
            if (files.size >= TARGET_FRAMES) completeLocked()
        }
    }

    /** Completes the round with every fully written frame collected so far. */
    fun finish() {
        synchronized(lock) {
            if (closed || !started || completionSent) return
            completeLocked()
        }
    }

    /** Stops future callbacks and removes files from an incomplete, not-yet-delivered round. */
    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            started = false
            if (!filesDelivered) {
                files.forEach { file ->
                    try { file.delete() } catch (_: Exception) { }
                }
                files.clear()
            }
        }
    }

    private fun completeLocked() {
        if (completionSent || closed) return
        completionSent = true
        started = false
        filesDelivered = true
        val result = files.toList()
        try {
            onComplete(result)
        } catch (_: Exception) {
            // Completion is one-shot even if client code throws.
        }
    }

    private fun nextFrameFile(): File {
        while (true) {
            fileSerial++
            val candidate = File(directory, "frame_${System.currentTimeMillis()}_${fileSerial}.jpg")
            if (!candidate.exists()) return candidate
        }
    }

    /** Converts, crops, rotates upright, downsizes, writes, then promptly recycles the bitmap. */
    private fun writeFrame(image: ImageProxy, destination: File): Boolean {
        var bitmap: Bitmap? = null
        try {
            bitmap = image.toBitmap()
            val source = bitmap ?: return false
            val proxyWidth = image.width
            val proxyHeight = image.height
            if (proxyWidth <= 0 || proxyHeight <= 0 || source.width <= 0 || source.height <= 0) return false

            // ImageProxy cropRect is in buffer coordinates; map it if conversion changed dimensions.
            val crop = image.cropRect
            val left = floor(crop.left.toDouble() * source.width / proxyWidth).toInt().coerceIn(0, source.width)
            val top = floor(crop.top.toDouble() * source.height / proxyHeight).toInt().coerceIn(0, source.height)
            val right = ceil(crop.right.toDouble() * source.width / proxyWidth).toInt().coerceIn(0, source.width)
            val bottom = ceil(crop.bottom.toDouble() * source.height / proxyHeight).toInt().coerceIn(0, source.height)
            if (right <= left || bottom <= top) return false

            val cropped = if (left == 0 && top == 0 && right == source.width && bottom == source.height) {
                source
            } else {
                Bitmap.createBitmap(source, left, top, right - left, bottom - top)
            }
            if (cropped !== source) {
                source.recycle()
                bitmap = cropped
            }

            val rotation = ((image.imageInfo.rotationDegrees % 360) + 360) % 360
            if (rotation != 0) {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                val rotated = Bitmap.createBitmap(cropped, 0, 0, cropped.width, cropped.height, matrix, true)
                if (rotated !== cropped) {
                    cropped.recycle()
                    bitmap = rotated
                }
            }

            val upright = bitmap ?: return false
            val longest = max(upright.width, upright.height)
            if (longest > MAX_EDGE) {
                val scale = MAX_EDGE.toDouble() / longest
                val width = (upright.width * scale).roundToInt().coerceAtLeast(1)
                val height = (upright.height * scale).roundToInt().coerceAtLeast(1)
                val resized = Bitmap.createScaledBitmap(upright, width, height, true)
                if (resized !== upright) {
                    upright.recycle()
                    bitmap = resized
                }
            }

            val finalBitmap = bitmap ?: return false
            FileOutputStream(destination).use { stream ->
                if (!finalBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)) return false
                stream.flush()
            }
            return true
        } catch (_: Exception) {
            return false
        } finally {
            try { bitmap?.recycle() } catch (_: Exception) { }
        }
    }
}
