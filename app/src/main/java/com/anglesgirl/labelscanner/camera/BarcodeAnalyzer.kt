package com.anglesgirl.labelscanner.camera

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
import com.anglesgirl.labelscanner.model.LabelParser
import com.anglesgirl.labelscanner.model.LabelResult

/**
 * ML Kit 双通道分析器：
 *  - BarcodeScanner：一帧同时检测所有条码（69码/SN码/QR 9段码 等）
 *  - TextRecognizer：OCR（中文模型）—— 和条码【同时跑】，不是兜底
 *
 * 为什么同时跑：标签上通常同时有 69 码（条码）和 文字（物料/日期/型号）。
 * 条码准但字段少，OCR 能补型号/颜色/硒鼓/交叉验证物料。条码结果优先。
 *
 * 每帧流程：条码检测 + OCR 并行 → 合并解析 → 回调
 * 节流：同一内容 2 秒内不重复回调（避免连续弹）。
 */
class BarcodeAnalyzer(
    private val onResult: (LabelResult) -> Unit,
    private val lookup69: ((String) -> String?)? = null,
) : ImageAnalysis.Analyzer {

    private val tag = "BarcodeAnalyzer"

    // 全格式条码（能扫到什么返回什么）
    private val scanner: BarcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_ALL_FORMATS,
            )
            .build()
    )

    // 中文 OCR
    private val recognizer: TextRecognizer = TextRecognition.getClient(
        ChineseTextRecognizerOptions.Builder().build()
    )

    private var lastEmit = 0L
    private var lastContent = ""
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

        // 1. 条码检测（多码）
        scanner.process(inputImage)
            .addOnSuccessListener { barcodes ->
                val values = barcodes.mapNotNull { it.rawValue }.distinct()
                // 2. OCR 与条码并行（无论条码有没有都跑，补型号/颜色等）
                runOcr(inputImage, values, imageProxy)
            }
            .addOnFailureListener { e ->
                Log.w(tag, "barcode scan failed, fallback ocr only", e)
                runOcr(inputImage, emptyList(), imageProxy)
            }
    }

    private fun runOcr(inputImage: InputImage, barcodes: List<String>, imageProxy: ImageProxy) {
        recognizer.process(inputImage)
            .addOnSuccessListener { text ->
                val ocr = text.text?.trim() ?: ""
                emit(barcodes, ocr, imageProxy)
            }
            .addOnFailureListener { e ->
                Log.w(tag, "ocr failed, use barcodes only", e)
                emit(barcodes, "", imageProxy)
            }
    }

    private fun emit(barcodes: List<String>, ocrText: String, imageProxy: ImageProxy) {
        val result = LabelParser.parse(barcodes, ocrText, lookup69)
        val content = barcodes.joinToString(",") + "|" + ocrText
        val now = System.currentTimeMillis()

        // 节流：同内容 2 秒内不重复
        if (result.hasData && (content != lastContent || now - lastEmit > 2000)) {
            lastContent = content
            lastEmit = now
            onResult(result)
        }
        imageProxy.close()
        processing = false
    }

    fun close() {
        scanner.close()
        recognizer.close()
    }
}
