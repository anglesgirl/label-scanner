package com.anglesgirl.labelscanner.camera

import android.graphics.Bitmap
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.Result
import com.google.zxing.common.HybridBinarizer

/**
 * ZXing 双解码器：对放大后的图片跑 MultiFormatReader 一次解多个码。
 *
 * 背景（2026-08 实测）：密集小条码照片（条码高仅 17~27px、模块宽 1px 量级）ML Kit 漏检，
 * ZXing 原图直接解也 0 检出；但【放大 3x 后 ZXing 100% 全解】（横排 10/10、竖排 15/15 实测）。
 *
 * 2026-08-11 修复（条码过多卡死）：GenericMultipleBarcodeReader + TRY_HARDER 在条码多的
 * 图上组合爆炸（每个码重扫全图，数十秒~数分钟）；改为手动循环 + 时间预算（deadline），
 * 每解出一个码就涂抹该区域继续找，超时立即返回已收集结果 → 任何标签图 10s 内必返回。
 */
object ZxingDecoder {

    private const val TAG = "ZxingDecoder"
    /** 实测放大倍数（3x 时 905x1280 测试图 100% 全解） */
    private const val SCALE = 3f
    /** 放大后最长边上限（防大图 3x 后 OOM：4096 原图 3x=12K 会爆，压到 6144 内） */
    private const val MAX_DIM = 6144
    /** 单张解码硬性时间预算：条码越多耗时越长，到点返回已解出的部分（防卡死） */
    private const val TIME_BUDGET_MS = 14_000L
    /** 涂抹参数：垂直方向半径（须盖满 3x 码高 51px + 解码行偏移误差） */
    private const val BLOT_RADIUS = 60
    /** 沿码方向扩展 */
    private const val BLOT_PAD = 8
    /** 循环上限（防 "重复码反复解出" 死循环） */
    private const val MAX_LOOPS = 60

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

    /** 解码一张 Bitmap（内部放大 3x），返回所有条码值（distinct，按检测顺序，≤时间预算） */
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
            val pixels = IntArray(w * h)
            scaled.getPixels(pixels, 0, w, 0, 0, w, h)
            if (scaled !== original) scaled.recycle()

            decodeUntilDeadline(pixels, w, h)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "zxing OOM (input ${original.width}x${original.height}): ${e.message}")
            emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "zxing decode failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * 循环解码直到时间预算用完：
     * 解出一个码 → 涂抹其区域（防重复检出）→ 继续找下一个。
     *
     * 关键坑（2026-08-11 实测）：ZXing 1D 的 resultPoints 只给「解码行」两端点，
     * 不给码身高度——同一码会以不同 y（码的上/下部残留行）反复解出。
     * 因此【重复码也必须涂抹当前解码行】逐次蚕食码身，直到该码消失，再去解下一个。
     * 不用 GenericMultipleBarcodeReader（多码组合爆炸卡死）。
     */
    private fun decodeUntilDeadline(pixels: IntArray, w: Int, h: Int): List<String> {
        val results = mutableListOf<String>()
        val deadline = System.currentTimeMillis() + TIME_BUDGET_MS
        val reader = MultiFormatReader()
        val hints = mapOf<DecodeHintType, Any>(
            DecodeHintType.POSSIBLE_FORMATS to FORMATS,
            DecodeHintType.TRY_HARDER to true, // 实测必需：去掉后 3x 放大也 0 检出
        )
        var loops = 0

        while (System.currentTimeMillis() < deadline && loops < MAX_LOOPS) {
            loops++
            val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(w, h, pixels)))
            val result: Result = try {
                reader.decode(bitmap, hints)
            } catch (e: NotFoundException) {
                break // 图中没有更多码
            } catch (e: Exception) {
                break // 其他异常停止
            }

            val text = result.text?.trim().orEmpty()
            if (text.isEmpty()) break
            if (text in results) {
                // 重复码：涂抹当前解码行继续蚕食（同一码可能从码身残留行反复解出）
                blot(pixels, w, h, result)
                continue
            }
            results.add(text)
            blot(pixels, w, h, result)
        }

        Log.d(TAG, "decoded ${results.size} barcodes in ${loops} loops within ${TIME_BUDGET_MS}ms budget (${w}x${h})")
        return results
    }

    /** 涂抹：以解码行两端点为中心，垂直方向扩展 BLOT_RADIUS、沿码方向扩展 BLOT_PAD 涂白 */
    private fun blot(pixels: IntArray, w: Int, h: Int, result: Result) {
        val pts = result.resultPoints
        if (pts.isEmpty()) return
        val y0 = pts[0].y.toInt()
        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        for (p in pts) {
            val x = p.x.toInt()
            if (x < minX) minX = x
            if (x > maxX) maxX = x
        }
        for (y in (y0 - BLOT_RADIUS).coerceAtLeast(0)..(y0 + BLOT_RADIUS).coerceAtMost(h - 1)) {
            val row = y * w
            for (x in (minX - BLOT_PAD).coerceAtLeast(0)..(maxX + BLOT_PAD).coerceAtMost(w - 1)) {
                pixels[row + x] = -0x010101 // 0xFEFFFFFF ≈ 白色
            }
        }
    }
}