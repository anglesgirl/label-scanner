package com.anglesgirl.labelscanner.camera

import android.graphics.Rect
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/**
 * 轻量取景检测：用条码或 OCR 文本的包围盒判断标签是否进入中央取景框。
 * 连续稳定若干帧后通知拍照，识别结果仍由拍照后的原图流程负责。
 */
class CaptureAlignmentAnalyzer(
    private val onState: (AlignmentState) -> Unit,
    private val onStable: () -> Unit,
) : ImageAnalysis.Analyzer {
    enum class AlignmentState { SEARCHING, MOVE_CLOSER, CENTERED, STABLE }

    private val barcodeScanner = BarcodeScanning.getClient()
    private val textRecognizer: TextRecognizer = TextRecognition.getClient(
        ChineseTextRecognizerOptions.Builder().build()
    )
    private val busy = AtomicBoolean(false)

    private companion object {
        /** 稳定判定：中心位移与面积变化的阈值（比旧版放宽，适应手持抖动）。 */
        const val STABLE_MOVE_TH = 0.045f
        const val STABLE_AREA_TH = 0.10f

        /** 连续多少帧稳定即认为对准。 */
        const val STABLE_NEEDED = 3
    }
    private var stableFrames = 0
    private var lastCenterX = 0f
    private var lastCenterY = 0f
    private var lastArea = 0f

    override fun analyze(imageProxy: ImageProxy) {
        if (!busy.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            finish(imageProxy)
            return
        }
        val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        barcodeScanner.process(input)
            .addOnSuccessListener { barcodes ->
                val barcodeRects = barcodes.mapNotNull { it.boundingBox }
                if (barcodeRects.isNotEmpty()) {
                    evaluate(barcodeRects, input.width, input.height)
                    finish(imageProxy)
                } else {
                    textRecognizer.process(input)
                        .addOnSuccessListener { text ->
                            val textRects = text.textBlocks.flatMap { block ->
                                block.lines.mapNotNull { it.boundingBox }
                            }
                            evaluate(textRects, input.width, input.height)
                        }
                        .addOnFailureListener { evaluate(emptyList(), input.width, input.height) }
                        .addOnCompleteListener { finish(imageProxy) }
                }
            }
            .addOnFailureListener {
                evaluate(emptyList(), input.width, input.height)
                finish(imageProxy)
            }
    }

    private fun evaluate(rects: List<Rect>, width: Int, height: Int) {
        if (rects.isEmpty() || width <= 0 || height <= 0) {
            stableFrames = 0
            lastStateIsStable = false
            emitState(AlignmentState.SEARCHING)
            return
        }

        // 【关键修复】先剔除离群框，再求并集。
        // 旧实现直接把所有 rects 取 min/max 并集，只要混进一个离群的文字块
        // （哪怕是画面边缘的噪点），框面积就会超过 notTooLarge 上限，
        // 状态永远停在 CENTERED → 永远"对不准"、永远不拍照。
        val usable = dropOutliers(rects)
        val left = usable.minOf { it.left }.coerceIn(0, width)
        val top = usable.minOf { it.top }.coerceIn(0, height)
        val right = usable.maxOf { it.right }.coerceIn(0, width)
        val bottom = usable.maxOf { it.bottom }.coerceIn(0, height)

        // 平滑后的框供预览绘制（EMA）：原始检测逐帧跳动，直接画会"边框乱跳"
        val centerX = (left + right) / 2f / width
        val centerY = (top + bottom) / 2f / height
        val area = (right - left).toFloat() * (bottom - top) / (width * height).toFloat()

        // 判定条件整体放宽：旧版的 0.30~0.70 / 0.025 / 0.88 在现场很难同时满足
        val centered = centerX in 0.22f..0.78f && centerY in 0.18f..0.82f
        val largeEnough = area >= 0.015f
        val notTooLarge = area <= 0.96f

        if (!largeEnough) {
            stableFrames = 0
            lastStateIsStable = false
            emitState(AlignmentState.MOVE_CLOSER)
            return
        }
        if (!centered || !notTooLarge) {
            stableFrames = 0
            lastStateIsStable = false
            emitState(AlignmentState.CENTERED)
            return
        }

        val movement = max(kotlin.math.abs(centerX - lastCenterX), kotlin.math.abs(centerY - lastCenterY))
        val areaChange = kotlin.math.abs(area - lastArea)
        // 轻抖不再把计数清零，而是衰减：现场手持必然有抖动，
        // 旧版 "else stableFrames = 1" 会让计数永远攒不满。
        if (movement < STABLE_MOVE_TH && areaChange < STABLE_AREA_TH) {
            stableFrames++
        } else {
            stableFrames = (stableFrames - 1).coerceAtLeast(0)
        }
        lastCenterX = centerX
        lastCenterY = centerY
        lastArea = area

        if (stableFrames >= STABLE_NEEDED) {
            lastStateIsStable = true
            emitState(AlignmentState.STABLE)
            stableFrames = 0
            onStable()
        } else {
            lastStateIsStable = false
            emitState(AlignmentState.CENTERED)
        }
    }

    /**
     * 剔除离群框：以面积最大的框为基准，只保留与它中心距离在合理范围内的框。
     * 这样能挡住画面边缘的噪点/无关文字把标签框撑爆。
     */
    private fun dropOutliers(rects: List<Rect>): List<Rect> {
        if (rects.size <= 1) return rects
        val base = rects.maxByOrNull { it.width().toLong() * it.height().toLong() } ?: return rects
        val limit = maxOf(base.width(), base.height()).toFloat() * 1.6f
        val kept = rects.filter { r ->
            val dx = kotlin.math.abs(r.centerX() - base.centerX()).toFloat()
            val dy = kotlin.math.abs(r.centerY() - base.centerY()).toFloat()
            dx <= limit && dy <= limit
        }
        return kept.ifEmpty { listOf(base) }
    }

    private fun finish(imageProxy: ImageProxy) {
        imageProxy.close()
        busy.set(false)
    }

    /** 上一次真正上报给 UI 的状态与时间（用于迟滞，避免状态闪烁）。 */
    private var lastEmitted: AlignmentState? = null
    private var lastEmittedAt = 0L

    /**
     * 上报状态（带迟滞）。
     *
     * 旧实现每帧都回调 UI，而坐标/面积是逐帧抖动的，于是取景框在
     * "位置合适"与"已对齐"之间高频闪烁 —— 观感就是"跳得更严重"。
     * 这里要求：状态变化后至少停 150ms 才允许再变；同一状态 250ms 内不重复上报。
     */
    private fun emitState(st: AlignmentState) {
        val now = System.currentTimeMillis()
        val changed = st != lastEmitted
        val gap = now - lastEmittedAt
        if (changed && gap < 150) return
        if (!changed && gap < 250) return
        lastEmitted = st
        lastEmittedAt = now
        onState(st)
    }

    /** 最近一次状态是否 STABLE（供预览决定框的颜色）。 */
    @Volatile
    var lastStateIsStable: Boolean = false
        private set

    fun close() {
        barcodeScanner.close()
        textRecognizer.close()
    }
}
