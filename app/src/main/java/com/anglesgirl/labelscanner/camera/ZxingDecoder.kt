package com.anglesgirl.labelscanner.camera

import android.graphics.Bitmap
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.GenericMultipleBarcodeReader

/**
 * ZXing 双解码器：对放大后的图片跑 MultiFormatReader（GenericMultipleBarcodeReader 一次解多个码）。
 *
 * 背景（2026-08 实测）：密集小条码照片（条码高仅 17~27px、模块宽 1px 量级）ML Kit 漏检，
 * ZXing 原图直接解也 0 检出；但【放大 3x 后 ZXing 100% 全解】（横排 10/10、竖排 15/15 实测）。
 *
 * 因此流程：Bitmap → INTER_CUBIC 放大 3x → 灰度像素 → 解码 → 返回全部文本（去重）。
 */
object ZxingDecoder {

    private const val TAG = "ZxingDecoder"
    /** 实测放大倍数（3x 时 905x1280 测试图 100% 全解） */
    private const val SCALE = 3f
    /** 放大后最长边上限（防大图 3x 后 OOM：4096 原图 3x=12K 会爆，压到 6144 内） */
    private const val MAX_DIM = 6144

    // 与 ML Kit 全格式对齐的 1D/2D 格式白名单
    private val FORMATS = listOf(
        BarcodeFormat.CODE_128,
        BarcodeFormat.CODE_39,
        BarcodeFormat.CODE_93,
        BarcodeFormat.EAN_13,
        BarcodeFormat.EAN_8,
        BarcodeFormat.UPC_A,
        BarcodeFormat.UPC_E,
        BarcodeFormat.ITF,
        BarcodeFormat.QR_CODE,
        BarcodeFormat.DATA_MATRIX,
        BarcodeFormat.AZTEC,
        BarcodeFormat.PDF_417,
    )

    /** 解码一张 Bitmap（内部放大 3x），返回所有条码值（distinct，按检测顺序） */
    fun decode(original: Bitmap): List<String> {
        if (original.width < 10 || original.height < 10) return emptyList()
        return try {
            var w = (original.width * SCALE).toInt()
            var h = (original.height * SCALE).toInt()
            // 大图保护：放大后最长边超过上限则等比收(防 3x 大图 OOM)
            val maxDim = maxOf(w, h)
            if (maxDim > MAX_DIM) {
                val ratio = MAX_DIM.toFloat() / maxDim
                w = (w * ratio).toInt().coerceAtLeast(1)
                h = (h * ratio).toInt().coerceAtLeast(1)
            }
            val scaled = Bitmap.createScaledBitmap(original, w, h, true)
            // 可选灰度增强：INTER_CUBIC 已足够（实测 3x 全解）；不再叠二值化，保留抗锯齿
            val pixels = IntArray(w * h)
            scaled.getPixels(pixels, 0, w, 0, 0, w, h)
            if (scaled !== original) scaled.recycle()

            val source = RGBLuminanceSource(w, h, pixels)
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            val hints = mapOf(
                DecodeHintType.POSSIBLE_FORMATS to FORMATS,
                DecodeHintType.TRY_HARDER to true,
            )
            val results = GenericMultipleBarcodeReader(MultiFormatReader())
                .decodeMultiple(bitmap, hints)
            results
                .mapNotNull { it.text?.trim()?.takeIf { t -> t.isNotEmpty() } }
                .distinct()
                .also { Log.d(TAG, "decoded ${it.size} barcodes after upscale (${w}x${h})") }
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "zxing OOM (input ${original.width}x${original.height}): ${e.message}")
            emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "zxing decode failed: ${e.message}")
            emptyList()
        }
    }
}