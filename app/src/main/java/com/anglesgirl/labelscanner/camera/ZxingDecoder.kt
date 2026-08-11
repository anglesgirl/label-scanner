package com.anglesgirl.labelscanner.camera

import android.graphics.Bitmap
import android.util.Log
import zxingcpp.BarcodeReader

/**
 * ZXing-C++ 解码器：C++ 内核（io.github.zxing-cpp:android），替代 Java ZXing。
 *
 * 为什么换（2026-08-11 实测）：
 * - Java ZXing 密集小码（3x 放大后）只能解 13/15、7/10；zxing-cpp 同条件 10/10、15/15 全解
 * - Java ZXing 多码靠 GenericMultipleBarcodeReader 组合爆炸 + 手动涂抹循环易卡死；
 *   zxing-cpp 内核原生多码检测（一次 read 返回全部符号），maxNumberOfSymbols 硬限制 → 天然不卡死
 *
 * 流程：Bitmap → 放大 3x（密集小码实测必需）→ BarcodeReader.read → 全部码文本（去重）。
 */
object ZxingDecoder {

    private const val TAG = "ZxingDecoder"
    /** 实测放大倍数（3x 时 905x1280 测试图 100% 全解） */
    private const val SCALE = 3f
    /** 放大后最长边上限（防大图 3x 后 OOM：4096 原图 3x=12K 会爆，压到 6144 内） */
    private const val MAX_DIM = 6144
    /** 单张最多解出的码数（标签一般 ≤30 码；限 100 防极端图耗时失控） */
    private const val MAX_SYMBOLS = 100

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

            val reader = BarcodeReader(
                BarcodeReader.Options(
                    tryHarder = true,          // 密集小码实测必需
                    maxNumberOfSymbols = MAX_SYMBOLS,
                )
            )
            val results = reader.read(scaled)
            if (scaled !== original) scaled.recycle()

            results
                .mapNotNull { it.text?.trim()?.takeIf { t -> t.isNotEmpty() } }
                .distinct()
                .also { Log.d(TAG, "zxing-cpp decoded ${it.size} barcodes (${w}x${h})") }
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "zxing-cpp OOM (input ${original.width}x${original.height}): ${e.message}")
            emptyList()
        } catch (e: Throwable) {
            Log.w(TAG, "zxing-cpp decode failed: ${e.message}")
            emptyList()
        }
    }
}
