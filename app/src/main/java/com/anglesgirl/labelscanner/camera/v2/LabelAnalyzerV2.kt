package com.anglesgirl.labelscanner.camera.v2

/*
 * 【用途：模式 B —— 卡板清单 / 大批量取码，当前采集流程未使用】
 * 同 SmartTrackAnalyzer，保留给以后的大批量连续读码模式。**请勿当作死代码删除。**
 */

import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

/**
 * v2 实时分析器：**只负责抓码与抓文本，不做归组**。
 *
 * 与 v1 的区别（针对真实标签的实际形态）：
 *  - v1 在分析器里就做"换标签检测 + first-wins 锁定"。但真实标签常常
 *    **一张标签上有多个码**（箱号 + N 个 SN），条码签名每帧都在变，
 *    v1 会误判成"换了标签"而反复重置锁定。
 *  - v2 把归组完全交给 [com.anglesgirl.labelscanner.model.v2.LabelParserV2]
 *    （已用 6 类真实标签回归 40/40）。分析器只做一件事：把这一帧看到的
 *    码和文本报上去，由采集页累加。职责单一，逻辑可离线测试。
 *
 * 双通道保留（v1 验证有效的部分）：条码准但字段少，OCR 补 SAP 行 / QTY / 日期。
 */
class LabelAnalyzerV2(
    private val onFrame: (barcodes: List<String>, ocrLines: List<String>) -> Unit,
) : ImageAnalysis.Analyzer {

    private val tag = "LabelAnalyzerV2"

    private val scanner: BarcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
            .build()
    )

    private val recognizer: TextRecognizer = TextRecognition.getClient(
        ChineseTextRecognizerOptions.Builder().build()
    )

    /** 帧级重入保护：识别未完成时丢弃新帧，避免堆积。 */
    @Volatile
    private var processing = false

    override fun analyze(imageProxy: ImageProxy) {
        if (processing) {
            imageProxy.close()
            return
        }
        processing = true

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            processing = false
            return
        }
        val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        scanner.process(inputImage)
            .addOnSuccessListener { barcodes ->
                val values = barcodes.mapNotNull { it.rawValue?.trim() }.filter { it.isNotEmpty() }
                runOcr(inputImage, values, imageProxy)
            }
            .addOnFailureListener { e ->
                Log.w(tag, "条码识别失败，仅走 OCR", e)
                runOcr(inputImage, emptyList(), imageProxy)
            }
    }

    private fun runOcr(inputImage: InputImage, barcodes: List<String>, imageProxy: ImageProxy) {
        recognizer.process(inputImage)
            .addOnSuccessListener { text ->
                val lines = text.textBlocks
                    .flatMap { it.lines }
                    .map { it.text.trim() }
                    .filter { it.isNotEmpty() }
                onFrame(barcodes, lines)
            }
            .addOnFailureListener { e ->
                Log.w(tag, "OCR 失败，仅用条码", e)
                onFrame(barcodes, emptyList())
            }
            .addOnCompleteListener {
                imageProxy.close()
                processing = false
            }
    }

    fun close() {
        scanner.close()
        recognizer.close()
    }
}
