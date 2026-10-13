package com.anglesgirl.labelscanner.camera

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 自动文档捕获分析器：实时检测标签 → 自动变焦 → 清晰度达标自动拍照。
 *
 * 流程：
 * 1. 预览帧降采样到 480p，跑 LabelRectifier.detectCorners() 找标签四角
 * 2. 多帧平滑角点位置（防抖动）
 * 3. 根据标签在画面中的占比，计算目标 zoomRatio（标签占画面 80%）
 * 4. 在标签区域内算 Laplacian 方差（清晰度）
 * 5. 清晰度达标且稳定 N 帧 → 回调 onReadyToCapture()
 *
 * 由调用方负责：setZoomRatio() 和实际拍照。
 */
class AutoDocCaptureAnalyzer(
    private val onCornersDetected: (corners: List<PointF>, zoomRatio: Float) -> Unit,
    private val onReadyToCapture: () -> Unit,
) : ImageAnalysis.Analyzer {

    companion object {
        private const val TAG = "AutoDocCapture"
        // 预览分析用小图，加速
        private const val ANALYZE_MAX_EDGE = 480
        // 标签占画面的目标比例
        private const val TARGET_FILL_RATIO = 0.8f
        // 角点稳定阈值（像素，480p 坐标系）
        private const val STABLE_THRESHOLD_PX = 12f
        // 连续稳定帧数
        private const val STABLE_FRAMES_REQUIRED = 8
        // 清晰度阈值（Laplacian 方差，经验值）
        private const val SHARPNESS_THRESHOLD = 120.0
        // 分析节流：每隔几帧跑一次检测（检测较重）
        private const val DETECT_EVERY_N_FRAMES = 3
    }

    private var frameCount = 0
    private var lastCorners: List<PointF>? = null
    private var stableFrames = 0
    private var lastZoomRatio = 1f
    private var captureTriggered = false

    // YUV → RGB 用的 buffer
    private var rgbBuffer: IntArray? = null

    override fun analyze(image: ImageProxy) {
        if (captureTriggered) {
            image.close()
            return
        }
        try {
            frameCount++
            // 节流
            if (frameCount % DETECT_EVERY_N_FRAMES != 0) return

            val bitmap = imageProxyToSmallBitmap(image) ?: return
            val corners = LabelRectifier.detectCorners(bitmap)
            bitmap.recycle()

            if (corners == null || corners.size != 4) {
                // 没检测到，重置稳定计数
                stableFrames = 0
                lastCorners = null
                return
            }

            // 平滑：与上一帧比较，位移小算稳定
            val isStable = lastCorners?.let { prev ->
                corners.zip(prev).all { (a, b) ->
                    abs(a.x - b.x) < STABLE_THRESHOLD_PX &&
                    abs(a.y - b.y) < STABLE_THRESHOLD_PX
                }
            } ?: false

            if (isStable) {
                stableFrames++
            } else {
                stableFrames = 0
            }
            // 指数平滑角点
            lastCorners = if (lastCorners == null) {
                corners
            } else {
                corners.zip(lastCorners!!).map { (cur, prev) ->
                    PointF(prev.x * 0.6f + cur.x * 0.4f, prev.y * 0.6f + cur.y * 0.4f)
                }
            }

            // 计算目标 zoom：让标签占画面 80%
            val zoomRatio = computeTargetZoom(corners, image.width, image.height)
            // 平滑 zoom，避免跳变
            val smoothZoom = lastZoomRatio * 0.7f + zoomRatio * 0.3f
            lastZoomRatio = smoothZoom
            onCornersDetected(lastCorners!!, smoothZoom)

            // 清晰度检测（在标签区域内）
            if (stableFrames >= STABLE_FRAMES_REQUIRED) {
                val sharpness = computeSharpness(image, corners)
                Log.d(TAG, "stable=$stableFrames sharpness=$sharpness")
                if (sharpness > SHARPNESS_THRESHOLD) {
                    captureTriggered = true
                    onReadyToCapture()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "analyze failed", e)
        } finally {
            image.close()
        }
    }

    fun reset() {
        captureTriggered = false
        stableFrames = 0
        lastCorners = null
        lastZoomRatio = 1f
        frameCount = 0
    }

    /**
     * 计算目标 zoom：标签包围盒占画面比例 → 缩放到目标比例。
     * 简单模型：当前标签宽度占画面 w_ratio，要达到 TARGET_FILL_RATIO，
     * 需要 zoom = TARGET_FILL_RATIO / w_ratio（限幅 1x~4x）。
     */
    private fun computeTargetZoom(corners: List<PointF>, imgW: Int, imgH: Int): Float {
        // corners 是 480p 坐标系下的，需要换算回原图比例
        // 这里简化：用包围盒在 480p 中的占比
        val xs = corners.map { it.x }
        val ys = corners.map { it.y }
        val bw = (xs.maxOrNull() ?: 0f) - (xs.minOrNull() ?: 0f)
        val bh = (ys.maxOrNull() ?: 0f) - (ys.minOrNull() ?: 0f)
        // 480p 工作尺寸
        val workW = ANALYZE_MAX_EDGE.toFloat()
        val wRatio = bw / workW
        if (wRatio <= 0.05f) return 1f // 太小，不 zoom（可能误检）
        val target = TARGET_FILL_RATIO / wRatio
        return target.coerceIn(1f, 4f)
    }

    /**
     * 清晰度：Laplacian 方差。在整图上算（简化，标签区域定位到原图较复杂，
     * 整图清晰度对自动拍照已够用——用户已把标签对准中央）。
     */
    private fun computeSharpness(image: ImageProxy, corners: List<PointF>): Double {
        // 取中心区域（标签大致在中央）做清晰度计算
        val w = image.width
        val h = image.height
        // 为速度，用更小的图
        val smallW = 240
        val smallH = (smallW * h / w).coerceAtLeast(1)
        val bitmap = imageProxyToBitmap(image, smallW, smallH) ?: return 0.0

        val px = IntArray(smallW * smallH)
        bitmap.getPixels(px, 0, smallW, 0, 0, smallW, smallH)
        bitmap.recycle()

        // 灰度
        val gray = DoubleArray(px.size)
        for (i in px.indices) {
            val c = px[i]
            gray[i] = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000.0
        }

        // Laplacian 方差（3x3）
        var sum = 0.0
        var sumSq = 0.0
        var count = 0
        for (y in 1 until smallH - 1) {
            for (x in 1 until smallW - 1) {
                val idx = y * smallW + x
                val lap = -4 * gray[idx] +
                    gray[idx - 1] + gray[idx + 1] +
                    gray[idx - smallW] + gray[idx + smallW]
                sum += lap
                sumSq += lap * lap
                count++
            }
        }
        if (count == 0) return 0.0
        val mean = sum / count
        return sumSq / count - mean * mean
    }

    /** ImageProxy → 降采样 Bitmap（用于角点检测） */
    private fun imageProxyToSmallBitmap(image: ImageProxy): Bitmap? {
        val w = image.width
        val h = image.height
        val scale = ANALYZE_MAX_EDGE.toFloat() / max(w, h)
        val sw = max(1, (w * scale).toInt())
        val sh = max(1, (h * scale).toInt())
        return imageProxyToBitmap(image, sw, sh)
    }

    /** ImageProxy(YUV_420_888) → 指定尺寸 Bitmap */
    private fun imageProxyToBitmap(image: ImageProxy, outW: Int, outH: Int): Bitmap? {
        if (image.format != android.graphics.ImageFormat.YUV_420_888) return null
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride
        val w = image.width
        val h = image.height

        // 先转全尺寸 RGB
        val fullSize = w * h
        var rgb = rgbBuffer
        if (rgb == null || rgb.size != fullSize) {
            rgb = IntArray(fullSize)
            rgbBuffer = rgb
        }
        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        yBuf.rewind(); uBuf.rewind(); vBuf.rewind()

        for (j in 0 until h) {
            for (i in 0 until w) {
                val y = (yBuf.get(j * yRowStride + i).toInt() and 0xFF)
                val uvJ = j / 2
                val uvI = i / 2
                val u = (uBuf.get(uvJ * uvRowStride + uvI * uvPixelStride).toInt() and 0xFF) - 128
                val v = (vBuf.get(uvJ * uvRowStride + uvI * uvPixelStride).toInt() and 0xFF) - 128
                var r = (y + 1.402 * v).toInt()
                var g = (y - 0.344136 * u - 0.714136 * v).toInt()
                var b = (y + 1.772 * u).toInt()
                r = r.coerceIn(0, 255); g = g.coerceIn(0, 255); b = b.coerceIn(0, 255)
                rgb[j * w + i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        val fullBmp = Bitmap.createBitmap(rgb, w, h, Bitmap.Config.ARGB_8888)
        return if (outW == w && outH == h) {
            fullBmp
        } else {
            val small = Bitmap.createScaledBitmap(fullBmp, outW, outH, true)
            fullBmp.recycle()
            small
        }
    }
}
