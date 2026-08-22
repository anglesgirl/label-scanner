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
            onState(AlignmentState.SEARCHING)
            return
        }
        val left = rects.minOf { it.left }.coerceIn(0, width)
        val top = rects.minOf { it.top }.coerceIn(0, height)
        val right = rects.maxOf { it.right }.coerceIn(0, width)
        val bottom = rects.maxOf { it.bottom }.coerceIn(0, height)
        val centerX = (left + right) / 2f / width
        val centerY = (top + bottom) / 2f / height
        val area = (right - left).toFloat() * (bottom - top) / (width * height).toFloat()
        val centered = centerX in 0.30f..0.70f && centerY in 0.27f..0.73f
        val largeEnough = area >= 0.025f
        val notTooLarge = area <= 0.88f
        if (!largeEnough) {
            stableFrames = 0
            onState(AlignmentState.MOVE_CLOSER)
            return
        }
        if (!centered || !notTooLarge) {
            stableFrames = 0
            onState(AlignmentState.CENTERED)
            return
        }
        val movement = max(kotlin.math.abs(centerX - lastCenterX), kotlin.math.abs(centerY - lastCenterY))
        val areaChange = kotlin.math.abs(area - lastArea)
        if (movement < 0.025f && areaChange < 0.06f) stableFrames++ else stableFrames = 1
        lastCenterX = centerX
        lastCenterY = centerY
        lastArea = area
        if (stableFrames >= 5) {
            onState(AlignmentState.STABLE)
            stableFrames = 0
            onStable()
        } else {
            onState(AlignmentState.CENTERED)
        }
    }

    private fun finish(imageProxy: ImageProxy) {
        imageProxy.close()
        busy.set(false)
    }

    fun close() {
        barcodeScanner.close()
        textRecognizer.close()
    }
}
