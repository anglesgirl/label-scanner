package com.anglesgirl.labelscanner.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
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
import java.io.InputStream

/**
 * 静态图片识别器：对【单张图片】（文档扫描矫正结果 / 相册导入）跑双通道识别。
 *
 * 与实时 BarcodeAnalyzer 的区别：输入是 Bitmap 而非 CameraX 帧，且不需要字段锁定
 * （单张图只识别一次）。条码 + OCR 并行，结果合并后回调。
 */
object StaticRecognizer {

    private const val TAG = "StaticRecognizer"

    // 全格式条码
    private val scanner: BarcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
            .build()
    )

    // 中文 OCR
    private val recognizer: TextRecognizer = TextRecognition.getClient(
        ChineseTextRecognizerOptions.Builder().build()
    )

    /**
     * 从 Uri 解码 Bitmap（自动缩放，避免超大图 OOM），然后识别。
     */
    fun recognizeUri(
        uri: Uri,
        openStream: () -> InputStream?,
        lookup69: ((String) -> String?)?,
        onResult: (LabelResult) -> Unit,
        onError: (String) -> Unit,
    ) {
        try {
            val bmp = decodeSampledBitmap(openStream)
            if (bmp == null) {
                onError("无法读取图片")
                return
            }
            recognize(bmp, lookup69, onResult, onError)
        } catch (e: Exception) {
            Log.w(TAG, "decode failed", e)
            onError("图片解码失败")
        }
    }

    /** 识别单张 Bitmap：条码 + OCR 并行，结果合并 */
    fun recognize(
        bitmap: Bitmap,
        lookup69: ((String) -> String?)?,
        onResult: (LabelResult) -> Unit,
        onError: (String) -> Unit,
    ) {
        val input = InputImage.fromBitmap(bitmap, 0)

        scanner.process(input)
            .addOnSuccessListener { barcodes ->
                val values = barcodes.mapNotNull { it.rawValue }.distinct()
                recognizer.process(input)
                    .addOnSuccessListener { text ->
                        val ocr = text.text?.trim() ?: ""
                        onResult(LabelParser.parse(values, ocr, lookup69))
                    }
                    .addOnFailureListener { e ->
                        Log.w(TAG, "ocr failed", e)
                        onResult(LabelParser.parse(values, "", lookup69))
                    }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "barcode failed, ocr only", e)
                recognizer.process(input)
                    .addOnSuccessListener { text ->
                        val ocr = text.text?.trim() ?: ""
                        onResult(LabelParser.parse(emptyList(), ocr, lookup69))
                    }
                    .addOnFailureListener { e2 ->
                        Log.w(TAG, "ocr also failed", e2)
                        onError("识别失败")
                    }
            }
    }

    /** 缩放解码：目标边 ≤ 2048，避免大图 OOM */
    private fun decodeSampledBitmap(openStream: () -> InputStream?): Bitmap? {
        // 第一遍：只读尺寸
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream()?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
        while (maxDim / (sample * 2) >= 2048) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return openStream()?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    fun close() {
        scanner.close()
        recognizer.close()
    }
}
