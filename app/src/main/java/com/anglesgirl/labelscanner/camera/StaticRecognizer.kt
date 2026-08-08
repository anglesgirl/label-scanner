package com.anglesgirl.labelscanner.camera

import android.content.ContentResolver
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

/**
 * 静态图片识别器：对【单张图片】（文档扫描矫正结果 / 相册导入）跑双通道识别。
 *
 * 条码 + OCR 并行，结果合并后回调。
 *
 * 注意（2026-08 修复）：识别器实例用【懒加载 + close 后可重建】策略——
 * 之前是单例创建后 close() 就永久失效，Activity 被系统回收重建后
 * 一调用就抛 "Client is closed" → 全部识别失败。现在 close() 只置空，
 * 下次识别自动重建，Activity 生命周期不影响识别。
 */
object StaticRecognizer {

    private const val TAG = "StaticRecognizer"

    private var scanner: BarcodeScanner? = null
    private var recognizer: TextRecognizer? = null

    private fun getScanner(): BarcodeScanner =
        scanner ?: BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
                .build()
        ).also { scanner = it }

    private fun getRecognizer(): TextRecognizer =
        recognizer ?: TextRecognition.getClient(
            ChineseTextRecognizerOptions.Builder().build()
        ).also { recognizer = it }

    /**
     * 从 Uri 解码 Bitmap（自动缩放，避免超大图 OOM），然后识别。
     * 解码用 FileDescriptor（content:// 最可靠），失败 fallback 到流。
     */
    fun recognizeUri(
        resolver: ContentResolver,
        uri: Uri,
        lookup69: ((String) -> String?)?,
        onResult: (LabelResult) -> Unit,
        onError: (String) -> Unit,
    ) {
        val bmp = decodeSampledBitmap(resolver, uri)
        if (bmp == null) {
            onError("无法读取图片")
            return
        }
        recognize(bmp, lookup69, onResult, onError)
    }

    /** 识别单张 Bitmap：条码 + OCR 并行，结果合并 */
    fun recognize(
        bitmap: Bitmap,
        lookup69: ((String) -> String?)?,
        onResult: (LabelResult) -> Unit,
        onError: (String) -> Unit,
    ) {
        val input = InputImage.fromBitmap(bitmap, 0)

        getScanner().process(input)
            .addOnSuccessListener { barcodes ->
                val values = barcodes.mapNotNull { it.rawValue }.distinct()
                getRecognizer().process(input)
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
                getRecognizer().process(input)
                    .addOnSuccessListener { text ->
                        val ocr = text.text?.trim() ?: ""
                        onResult(LabelParser.parse(emptyList(), ocr, lookup69))
                    }
                    .addOnFailureListener { e2 ->
                        Log.w(TAG, "ocr also failed", e2)
                        onError("识别失败：${e2.message}")
                    }
            }
    }

    /** 缩放解码：目标边 ≤ 2048。FileDescriptor 方式（content URI 最可靠） */
    private fun decodeSampledBitmap(resolver: ContentResolver, uri: Uri): Bitmap? {
        try {
            // 方式一：FileDescriptor（支持 seek，bounds+解码同 fd）
            resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val fd = pfd.fileDescriptor

                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFileDescriptor(fd, null, bounds)
                if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                    var sample = 1
                    val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
                    while (maxDim / (sample * 2) >= 2048) sample *= 2

                    try { pfd.seekTo(0) } catch (_: Exception) { /* 部分实现不支持，忽略 */ }

                    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                    return BitmapFactory.decodeFileDescriptor(fd, null, opts)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "decodeFileDescriptor failed, fallback to stream", e)
        }

        // 方式二：流解码（fallback）
        return try {
            resolver.openInputStream(uri)?.use { ins ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(ins, null, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

                var sample = 1
                val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
                while (maxDim / (sample * 2) >= 2048) sample *= 2

                resolver.openInputStream(uri)?.use { ins2 ->
                    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                    BitmapFactory.decodeStream(ins2, null, opts)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "stream decode failed", e)
            null
        }
    }

    /** 关闭识别器（置空，下次识别自动重建） */
    fun close() {
        try { scanner?.close() } catch (_: Exception) {}
        try { recognizer?.close() } catch (_: Exception) {}
        scanner = null
        recognizer = null
    }
}
