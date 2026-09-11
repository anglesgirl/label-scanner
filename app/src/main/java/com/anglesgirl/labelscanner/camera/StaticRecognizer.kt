package com.anglesgirl.labelscanner.camera

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
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

    private var recognizer: TextRecognizer? = null

    /** ZXing 解码线程池（放大 3x 解码是 CPU 密集，不阻塞主线程） */
    private val zxingPool = Executors.newSingleThreadExecutor { r ->
        Thread(r, "zxing-decode").apply { isDaemon = true }
    }
    private val recognitionPool = Executors.newSingleThreadExecutor { r ->
        Thread(r, "recognition-orchestrator").apply { isDaemon = true }
    }

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
        // 相册路径保持原有的安全识别流程；拍照路径的增强已在 CropActivity 中完成。
        // 不在这里逐像素处理 4096 边长图片，避免主线程卡死/OOM。
        recognize(bmp, lookup69, onResult, onError)
    }

    /** 识别单张 Bitmap：**条码全走 zxing-cpp**，**ML Kit 只做 OCR**（各司其职）。 */
    fun recognize(
        bitmap: Bitmap,
        lookup69: ((String) -> String?)?,
        onResult: (LabelResult) -> Unit,
        onError: (String) -> Unit,
    ) {
        val input = InputImage.fromBitmap(bitmap, 0)
        val zxingFuture = zxingPool.submit<List<String>> {
            Log.i(TAG, "[ZXING_STATIC] start ${bitmap.width}x${bitmap.height}")
            ZxingDecoder.decode(bitmap).also {
                Log.i(TAG, "[ZXING_STATIC] complete count=${it.size}")
            }
        }
        // 条码/二维码一律交给 zxing-cpp（ML Kit 扫码能力弱：高密度 2D 码解不出，
        // 还会用旁边的 1D 结果干扰）。ML Kit 在本流程里**只负责 OCR**。
        finishWithBarcodes(input, emptyList(), zxingFuture, lookup69, onResult, onError)
    }

    /** 在后台等待 C++ 通道并合并，避免阻塞主线程，再只跑一次 OCR。 */
    private fun finishWithBarcodes(
        input: InputImage,
        mlBarcodes: List<String>,
        zxingFuture: java.util.concurrent.Future<List<String>>,
        lookup69: ((String) -> String?)?,
        onResult: (LabelResult) -> Unit,
        onError: (String) -> Unit,
    ) {
        recognitionPool.submit {
            val mergedBarcodes = try {
                (mlBarcodes + zxingFuture.get(15, TimeUnit.SECONDS))
                    .map { it.trim() }
                    .filter(String::isNotBlank)
                    .distinct()
            } catch (e: Exception) {
                zxingFuture.cancel(true)
                Log.w(TAG, "[ZXING_STATIC] timeout/failure; use ML Kit only: ${e.message}")
                mlBarcodes.distinct()
            }
            Log.i(TAG, "[BARCODE_MERGE] ml=${mlBarcodes.size} merged=${mergedBarcodes.size}")
            getRecognizer().process(input)
                .addOnSuccessListener { text ->
                    onResult(LabelParser.parse(mergedBarcodes, text.text?.trim() ?: "", lookup69))
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "ocr failed", e)
                    onResult(LabelParser.parse(mergedBarcodes, "", lookup69))
                }
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

    /** 关闭 OCR 识别器（置空，下次识别自动重建） */
    fun close() {
        try { recognizer?.close() } catch (_: Exception) {}
        recognizer = null
    }
}
