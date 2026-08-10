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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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

    /** ZXing 解码线程池（放大 3x 解码是 CPU 密集，不阻塞主线程） */
    private val zxingPool = Executors.newSingleThreadExecutor { r ->
        Thread(r, "zxing-decode").apply { isDaemon = true }
    }

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

    /** 识别单张 Bitmap：ML Kit 条码 + ZXing 双解码并行，结果合并，再跑 OCR */
    fun recognize(
        bitmap: Bitmap,
        lookup69: ((String) -> String?)?,
        onResult: (LabelResult) -> Unit,
        onError: (String) -> Unit,
    ) {
        val input = InputImage.fromBitmap(bitmap, 0)

        // ZXing 双解码器（放大 3x，补 ML Kit 漏检的密集小条码）——后台线程并行跑
        val zxingFuture: java.util.concurrent.Future<List<String>> =
            zxingPool.submit(java.util.concurrent.Callable { ZxingDecoder.decode(bitmap) })

        getScanner().process(input)
            .addOnSuccessListener { barcodes ->
                val mlValues = barcodes.mapNotNull { it.rawValue }.distinct()
                val zxValues = awaitZxing(zxingFuture)
                val values = (mlValues + zxValues).distinct()
                finishOcr(input, values, lookup69, onResult, onError)
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "barcode failed, ocr + zxing only", e)
                finishOcr(input, awaitZxing(zxingFuture), lookup69, onResult, onError)
            }
    }

    /** 等待 ZXing 解码结果（上限 2s，超时/异常返回空——ML Kit 结果不受影响） */
    private fun awaitZxing(future: java.util.concurrent.Future<List<String>>): List<String> =
        try {
            future.get(2000, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "zxing timed out: ${e.message}")
            emptyList()
        }

    /** 条码结果就绪后跑 OCR 并合并（两通道共用） */
    private fun finishOcr(
        input: InputImage,
        barcodes: List<String>,
        lookup69: ((String) -> String?)?,
        onResult: (LabelResult) -> Unit,
        onError: (String) -> Unit,
    ) {
        getRecognizer().process(input)
            .addOnSuccessListener { text ->
                val ocr = text.text?.trim() ?: ""
                onResult(LabelParser.parse(barcodes, ocr, lookup69))
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "ocr failed", e)
                onResult(LabelParser.parse(barcodes, "", lookup69))
            }
    }

    /** 缩放解码：目标边 ≤ 4096（密集小条码保真）。FileDescriptor 方式（content URI 最可靠） */
    private fun decodeSampledBitmap(resolver: ContentResolver, uri: Uri): Bitmap? {
        // 方式一：FileDescriptor（bounds 和解码各开一次 fd）
        try {
            var sample = 1
            resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, bounds)
                if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                    val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
                    while (maxDim / (sample * 2) >= 4096) sample *= 2
                }
            }
            if (sample > 0) {
                resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                    BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, opts)
                        ?.let { return it }
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
                while (maxDim / (sample * 2) >= 4096) sample *= 2

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
