package com.anglesgirl.labelscanner.camera

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Finds the whole label on a small upright/cropped preview frame, suggests a safe zoom, and only
 * signals capture after the geometry, optical zoom, sharpness, and camera adjustment have settled.
 * All frame conversion after taking a compact YUV-plane snapshot runs on a shared background thread.
 */
class AutoDocCaptureAnalyzer(
    private val onDetection: (Detection?) -> Unit,
    private val onReadyToCapture: () -> Unit,
) : ImageAnalysis.Analyzer {

    /** Corners are clockwise, normalized [0,1] coordinates in the upright ImageProxy crop. */
    data class Detection(
        val corners: List<PointF>,
        val center: PointF,
        val targetZoom: Float,
        val sharpness: Double,
        val stable: Boolean,
        val geometryStable: Boolean,
        val adjusting: Boolean,
        val status: Status,
        // Content bounds are NOT paper corners: use for AF/stability only, never zoom/crop to them.
        val contentOnly: Boolean = false,
        val coreFields: Boolean = false,
        val sampledAt: Long = SystemClock.elapsedRealtime(),
    )

    enum class Status { FOUND, ADJUSTING_ZOOM, WAITING_FOR_STABILITY, WAITING_FOR_SHARPNESS, READY }

    /** Compatibility constructor for existing callers; new integrations should consume Detection?. */
    constructor(
        onCornersDetected: (corners: List<PointF>, zoomRatio: Float) -> Unit,
        onReadyToCapture: () -> Unit,
    ) : this(
        onDetection = { detection ->
            if (detection != null) onCornersDetected(detection.corners, detection.targetZoom)
        },
        onReadyToCapture = onReadyToCapture,
    )

    private data class CameraState(val currentZoom: Float, val minZoom: Float, val maxZoom: Float, val adjusting: Boolean)
    private data class PlaneBytes(val data: ByteArray, val rowStride: Int, val pixelStride: Int, val position: Int)
    private data class Frame(
        val width: Int,
        val height: Int,
        val cropLeft: Int,
        val cropTop: Int,
        val cropWidth: Int,
        val cropHeight: Int,
        val rotation: Int,
        val y: PlaneBytes,
        val u: PlaneBytes,
        val v: PlaneBytes,
        val sampledAt: Long,
    )

    companion object {
        private const val TAG = "AutoDocCapture"
        private const val ANALYZE_MAX_EDGE = 480
        private const val DETECT_EVERY_N_FRAMES = 3
        private val background = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "label-frame-analysis").apply { isDaemon = true }
        }
    }

    private val cameraState = AtomicReference(CameraState(1f, 1f, 4f, false))
    private val resetGeneration = AtomicLong(0)
    private val captureTriggered = AtomicBoolean(false)
    private val inputFrameCount = AtomicLong(0)
    private val pendingFrame = AtomicReference<Frame?>(null)
    private val workerActive = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    // Accessed by the single background executor only.
    private var appliedResetGeneration = 0L
    private val captureGate = HandheldCaptureGate()

    /** Publish camera state confirmed by CameraX; safe to call from the UI thread. */
    fun updateCameraState(currentZoom: Float, minZoom: Float, maxZoom: Float, adjusting: Boolean) {
        if (!currentZoom.isFinite() || !minZoom.isFinite() || !maxZoom.isFinite()) return
        val minValue = minZoom.coerceAtLeast(0.1f)
        cameraState.set(CameraState(currentZoom.coerceIn(minValue, maxZoom.coerceAtLeast(minValue)), minValue, maxZoom.coerceAtLeast(minValue), adjusting))
    }

    override fun analyze(image: ImageProxy) {
        if (closed.get() || captureTriggered.get() || inputFrameCount.incrementAndGet() % DETECT_EVERY_N_FRAMES != 0L) {
            image.close()
            return
        }
        val frame = try {
            snapshot(image)
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to snapshot camera frame", t)
            null
        } finally {
            // Never hold up CameraX while YUV is converted or corners/sharpness are computed.
            image.close()
        } ?: return

        if (captureTriggered.get()) return
        pendingFrame.set(frame)
        scheduleWorker()
    }

    private fun scheduleWorker() {
        if (!workerActive.compareAndSet(false, true)) return
        try {
            background.execute {
                try {
                    while (true) {
                        val latest = pendingFrame.getAndSet(null) ?: break
                        applyPendingReset()
                        if (!captureTriggered.get()) process(latest)
                    }
                } finally {
                    workerActive.set(false)
                    if (pendingFrame.get() != null) scheduleWorker()
                }
            }
        } catch (e: RuntimeException) {
            workerActive.set(false)
            Log.w(TAG, "Background analysis unavailable", e)
        }
    }

    /** Reset/rearm after a capture attempt; thread-safe from the UI/camera thread. */
    fun reset() {
        resetGeneration.incrementAndGet()
        pendingFrame.set(null)
        captureTriggered.set(false)
    }

    fun close() {
        closed.set(true)
        resetGeneration.incrementAndGet()
        pendingFrame.set(null)
    }

    private fun applyPendingReset() {
        val generation = resetGeneration.get()
        if (generation != appliedResetGeneration) {
            appliedResetGeneration = generation
            captureGate.reset()
        }
    }

    private fun process(frame: Frame) {
        if (closed.get()) return
        val generation = resetGeneration.get()
        val bitmap = frame.toSmallUprightBitmap() ?: run {
            loseDetection()
            return
        }
        try {
            // LabelRectifier returns corners in the source bitmap's coordinate system; here that
            // source is the already upright, crop-only, <=480px image (not the full sensor frame).
            val pixels = LabelRectifier.detectCorners(bitmap, liveDetection = true)?.takeIf { points ->
                AutoDocGeometry.hasCompleteOutline(points.map { it.x / bitmap.width to it.y / bitmap.height })
            }
            val argb = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(argb, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val core = LabelContentLocator.locateCore(argb, bitmap.width, bitmap.height)
            val contentOnly = core != null || pixels == null || pixels.size != 4
            val corners = if (core != null) {
                listOf(PointF(core.left, core.top), PointF(core.right, core.top),
                    PointF(core.right, core.bottom), PointF(core.left, core.bottom))
            } else if (!contentOnly) {
                pixels!!.map { p ->
                    PointF((p.x / bitmap.width).coerceIn(0f, 1f), (p.y / bitmap.height).coerceIn(0f, 1f))
                }
            } else {
                // Low-contrast labels on white boxes/film have no separable bright outer edge.
                // Detect ink texture without running OCR or decoding barcodes in the preview.
                LabelContentLocator.locate(argb, bitmap.width, bitmap.height)?.let { b ->
                    listOf(PointF(b.left, b.top), PointF(b.right, b.top),
                        PointF(b.right, b.bottom), PointF(b.left, b.bottom))
                }
            }
            if (corners == null) {
                if (!closed.get() && resetGeneration.get() == generation) loseDetection()
                return
            }
            if (closed.get() || resetGeneration.get() != generation) return
            val normalized = corners.map { it.x to it.y }
            // Core fields guide AF/sharpness only. Zoom still needs a complete paper envelope;
            // never magnify using the smaller field rectangle and clip surrounding data.
            val paper = pixels?.map { PointF(it.x / bitmap.width, it.y / bitmap.height) }
            val paperBounds = paper?.let { bounds(it) }
            val safePaper = paperBounds?.takeIf { b ->
                core == null || (core.left >= b.left && core.right <= b.right &&
                    core.top >= b.top && core.bottom <= b.bottom)
            }
            val state = cameraState.get()
            val targetZoom = if (safePaper == null) state.currentZoom else AutoDocGeometry.targetZoom(
                safePaper.left, safePaper.right, safePaper.top, safePaper.bottom,
                state.currentZoom, state.minZoom, state.maxZoom,
            )
            val zoomSettled = AutoDocGeometry.zoomSettled(targetZoom, state.currentZoom)
            val sharpness = sharpnessWithinLabel(bitmap, corners)
            val gate = captureGate.observe(SystemClock.elapsedRealtime(), normalized, sharpness,
                cameraAdjusting = !zoomSettled || state.adjusting)
            val geometricStable = gate.geometryStable
            val ready = gate.ready
            val center = PointF(
                corners.map { it.x }.average().toFloat(),
                corners.map { it.y }.average().toFloat(),
            )
            val status = when {
                state.adjusting || !zoomSettled -> Status.ADJUSTING_ZOOM
                ready -> Status.READY
                sharpness < HandheldCaptureGate.SHARPNESS_THRESHOLD -> Status.WAITING_FOR_SHARPNESS
                !ready -> Status.WAITING_FOR_STABILITY
                else -> Status.FOUND
            }
            if (closed.get() || resetGeneration.get() != generation) return
            onDetection(Detection(corners, center, targetZoom, sharpness, ready, geometricStable,
                state.adjusting, status, contentOnly, core != null, frame.sampledAt))
            if (ready && captureTriggered.compareAndSet(false, true)) onReadyToCapture()
        } catch (e: Exception) {
            Log.e(TAG, "analyze failed", e)
            if (!closed.get() && resetGeneration.get() == generation) loseDetection()
        } finally {
            bitmap.recycle()
        }
    }

    private fun loseDetection() {
        if (closed.get()) return
        captureGate.miss(SystemClock.elapsedRealtime())
        // Null is intentionally emitted on every sampled miss: consumers can clear stale UI state.
        onDetection(null)
    }

    private data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float)
    private fun bounds(points: List<PointF>) = Bounds(
        points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y },
    )

    /** Laplacian variance is measured only inside the detected polygon, never on the background. */
    private fun sharpnessWithinLabel(bitmap: Bitmap, corners: List<PointF>): Double {
        val width = bitmap.width
        val height = bitmap.height
        val px = IntArray(width * height)
        bitmap.getPixels(px, 0, width, 0, 0, width, height)
        val gray = IntArray(px.size)
        for (i in px.indices) {
            val c = px[i]
            gray[i] = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
        }
        val b = bounds(corners)
        val xStart = (b.left * width).toInt().coerceIn(1, width - 2)
        val xEnd = (b.right * width).toInt().coerceIn(2, width - 1)
        val yStart = (b.top * height).toInt().coerceIn(1, height - 2)
        val yEnd = (b.bottom * height).toInt().coerceIn(2, height - 1)
        var sum = 0.0
        var sumSq = 0.0
        var count = 0
        for (y in yStart until yEnd) for (x in xStart until xEnd) {
            if (!insidePolygon(x + 0.5f, y + 0.5f, corners, width, height)) continue
            val i = y * width + x
            val lap = -4 * gray[i] + gray[i - 1] + gray[i + 1] + gray[i - width] + gray[i + width]
            sum += lap
            sumSq += lap.toDouble() * lap
            count++
        }
        if (count < 16) return 0.0
        val mean = sum / count
        return sumSq / count - mean * mean
    }

    private fun insidePolygon(x: Float, y: Float, corners: List<PointF>, width: Int, height: Int): Boolean {
        var inside = false
        var j = corners.lastIndex
        for (i in corners.indices) {
            val a = corners[i]
            val b = corners[j]
            val ax = a.x * width; val ay = a.y * height
            val bx = b.x * width; val by = b.y * height
            if ((ay > y) != (by > y) && x < (bx - ax) * (y - ay) / (by - ay) + ax) inside = !inside
            j = i
        }
        return inside
    }

    /** Copy the YUV planes (only), crop metadata, and rotation, then return without RGB conversion. */
    private fun snapshot(image: ImageProxy): Frame? {
        if (image.format != android.graphics.ImageFormat.YUV_420_888 || image.planes.size < 3) return null
        val crop = image.cropRect
        val left = crop.left.coerceIn(0, image.width - 1)
        val top = crop.top.coerceIn(0, image.height - 1)
        val right = crop.right.coerceIn(left + 1, image.width)
        val bottom = crop.bottom.coerceIn(top + 1, image.height)
        fun copyPlane(index: Int): PlaneBytes {
            val plane = image.planes[index]
            val buffer = plane.buffer.duplicate()
            val position = buffer.position()
            val data = ByteArray(buffer.remaining())
            buffer.get(data)
            return PlaneBytes(data, plane.rowStride, plane.pixelStride, position)
        }
        return Frame(
            image.width, image.height, left, top, right - left, bottom - top,
            ((image.imageInfo.rotationDegrees % 360) + 360) % 360,
            copyPlane(0), copyPlane(1), copyPlane(2),
            SystemClock.elapsedRealtime(),
        )
    }

    private fun Frame.toSmallUprightBitmap(): Bitmap? {
        if (rotation !in listOf(0, 90, 180, 270)) return null
        val uprightWidth = if (rotation == 90 || rotation == 270) cropHeight else cropWidth
        val uprightHeight = if (rotation == 90 || rotation == 270) cropWidth else cropHeight
        val scale = ANALYZE_MAX_EDGE.toFloat() / max(uprightWidth, uprightHeight)
        val outWidth = max(1, (uprightWidth * scale).roundToInt())
        val outHeight = max(1, (uprightHeight * scale).roundToInt())
        val out = IntArray(outWidth * outHeight)
        for (oy in 0 until outHeight) for (ox in 0 until outWidth) {
            val ux = (((ox + 0.5f) * uprightWidth / outWidth) - 0.5f).roundToInt().coerceIn(0, uprightWidth - 1)
            val uy = (((oy + 0.5f) * uprightHeight / outHeight) - 0.5f).roundToInt().coerceIn(0, uprightHeight - 1)
            val (cx, cy) = when (rotation) {
                90 -> uy to (cropHeight - 1 - ux)
                180 -> (cropWidth - 1 - ux) to (cropHeight - 1 - uy)
                270 -> (cropWidth - 1 - uy) to ux
                else -> ux to uy
            }
            val sourceX = cropLeft + cx
            val sourceY = cropTop + cy
            val yValue = planeValue(y, sourceY * y.rowStride + sourceX * y.pixelStride).toInt() and 0xff
            val uvIndexU = (sourceY / 2) * u.rowStride + (sourceX / 2) * u.pixelStride
            val uvIndexV = (sourceY / 2) * v.rowStride + (sourceX / 2) * v.pixelStride
            val uu = (planeValue(u, uvIndexU).toInt() and 0xff) - 128
            val vv = (planeValue(v, uvIndexV).toInt() and 0xff) - 128
            val yy = max(0, yValue - 16) * 1.164f
            val r = (yy + 1.596f * vv).toInt().coerceIn(0, 255)
            val g = (yy - 0.813f * vv - 0.391f * uu).toInt().coerceIn(0, 255)
            val b = (yy + 2.018f * uu).toInt().coerceIn(0, 255)
            out[oy * outWidth + ox] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(out, outWidth, outHeight, Bitmap.Config.ARGB_8888)
    }

    private fun planeValue(plane: PlaneBytes, absoluteIndex: Int): Byte {
        val index = absoluteIndex - plane.position
        return if (index in plane.data.indices) plane.data[index] else 0
    }
}
