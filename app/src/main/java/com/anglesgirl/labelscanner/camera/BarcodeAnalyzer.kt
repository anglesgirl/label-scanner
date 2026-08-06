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
 * ML Kit 双引擎分析器：
 *  - BarcodeScanner：一帧同时检测所有条码（多码）
 *  - TextRecognizer：OCR 兜底（中文模型）
 *
 * 每帧流程：条码检测 →（无条码时）OCR → 合并解析 → 回调
 * 节流：同一内容 2 秒内不重复回调（避免连续弹）。
 */
class BarcodeAnalyzer(
    private val onResult: (LabelResult) -> Unit,
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
                if (values.isNotEmpty()) {
                    emit(values, "", imageProxy)
                } else {
                    // 2. 无条码 → OCR 兜底
                    ocrFallback(inputImage, imageProxy)
                }
            }
            .addOnFailureListener { e ->
                Log.w(tag, "barcode scan failed", e)
                imageProxy.close()
                processing = false
            }
    }

    private fun ocrFallback(inputImage: InputImage, imageProxy: ImageProxy) {
        recognizer.process(inputImage)
            .addOnSuccessListener { text ->
                val ocr = text.text?.trim() ?: ""
                if (ocr.isNotEmpty()) {
                    emit(emptyList(), ocr, imageProxy)
                } else {
                    imageProxy.close()
                }
                processing = false
            }
            .addOnFailureListener { e ->
                Log.w(tag, "ocr failed", e)
                imageProxy.close()
                processing = false
            }
    }

    private fun emit(barcodes: List<String>, ocrText: String, imageProxy: ImageProxy) {
        val result = LabelParser.parse(barcodes, ocrText)
        val content = barcodes.joinToString(",") + "|" + ocrText
        val now = System.currentTimeMillis()

        // 节流：同内容 2 秒内不重复
        if (result.hasData && (content != lastContent || now - lastEmit > 2000)) {
            lastContent = content
            lastEmit = now
            onResult(result)
        }
        imageProxy.close()
    }

    fun close() {
        scanner.close()
        recognizer.close()
    }
}
