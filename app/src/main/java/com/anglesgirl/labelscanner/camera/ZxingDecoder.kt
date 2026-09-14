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

    /**
     * 带位置的解码：返回 (码值, 外接矩形)。
     *
     * 为什么要位置：实测把标签图缩到 35% 后集成码（PDF417）直接解不出，但把它
     * **裁到码区再放大 3x** 就能解出 —— 瓶颈是"码在画面里占多少像素"，不是码本身
     * 难解。拿到位置才能做"裁切放大重试"。
     */
    fun decodeWithPositions(original: Bitmap): List<Pair<String, android.graphics.Rect>> {
        if (original.width < 10 || original.height < 10) return emptyList()
        return try {
            val reader = BarcodeReader(
                BarcodeReader.Options(
                    tryHarder = true,
                    tryRotate = true,
                    tryInvert = true,
                    tryDownscale = true,
                    maxNumberOfSymbols = MAX_SYMBOLS,
                )
            )
            reader.read(original).mapNotNull { b ->
                val text = b.text?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val rect = runCatching {
                    val p = b.position
                    val xs = listOf(p.topLeft.x, p.topRight.x, p.bottomLeft.x, p.bottomRight.x)
                    val ys = listOf(p.topLeft.y, p.topRight.y, p.bottomLeft.y, p.bottomRight.y)
                    android.graphics.Rect(
                        xs.min(), ys.min(), xs.max(), ys.max()
                    )
                }.getOrNull() ?: return@mapNotNull null
                text to rect
            }
        } catch (e: Throwable) {
            Log.w(TAG, "decodeWithPositions failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * 解码一张 Bitmap。
     *
     * ⚠️ **scale 要按场景选，不是越大越好**：
     * - **静态图 / 密集小码** → 用 3x（`SCALE`）。实测 905×1280 的标签图上条码只有
     *   17~27px 高、模块宽约 1px，不放大解不出；放大后 10/10、15/15 全解。
     * - **实时预览里的单码字段**（托盘号 / 物料 / 日期 / 型号）→ **用 1x**。
     *   分析帧本身已是 1920×1080，一维条码在这里像素充足，再放大 3x 到 5760×3240
     *   （1860 万像素、ARGB 约 74MB）会让单次解码涨到 1~3 秒 —— 用户表现为
     *   **「能识别但很慢，要举很久」**（2026-09 实测反馈：托盘码）。
     *   而且托盘标签常包着塑料膜，**反光区会被一起放大**，反而更难解。
     *
     * @param scale 放大倍数，1f = 原始分辨率（快），3f = 强通道（慢但能啃小码）
     */
    fun decode(original: Bitmap, scale: Float = SCALE): List<String> {
        if (original.width < 10 || original.height < 10) return emptyList()
        return try {
            var w = original.width
            var h = original.height
            var scaled = original
            if (scale > 1f) {
                w = (original.width * scale).toInt()
                h = (original.height * scale).toInt()
                // 大图保护：放大后最长边超过上限则等比收(防 3x 大图 OOM)
                val maxDim = maxOf(w, h)
                if (maxDim > MAX_DIM) {
                    val ratio = MAX_DIM.toFloat() / maxDim
                    w = (w * ratio).toInt().coerceAtLeast(1)
                    h = (h * ratio).toInt().coerceAtLeast(1)
                }
                scaled = Bitmap.createScaledBitmap(original, w, h, true)
            }

            val reader = BarcodeReader(
                BarcodeReader.Options(
                    tryHarder = true,            // 密集小码实测必需
                    tryRotate = true,            // 防拍歪
                    tryInvert = true,            // 反色条码
                    tryDownscale = true,         // 自动降采样多尺度（实测对多码全解关键）
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

    /**
     * 渐进解码：**1x → 2x → 3x 逐级尝试，任一尺度解出即返回**。
     *
     * 为什么（2026-09-14 用户反馈"拆码扫码扫很久"）：
     * 实时扫码的分析帧已是 1920×1080 —— 用户对准后，码在画面里通常占足够像素，
     * **1x 就能解出（几十毫秒）**；只有码很小 / 离得远时才需要放大。
     * 原来集成码模式一刀切直接 3x，把 5760×3240 的图喂给解码器，单次 1~3 秒，
     * 用户体感就是"举着扫很久"。渐进解码让**常见场景快、困难场景兜底**：
     * 1x 命中 → 毫秒级；1x 解不出再 2x（约 0.2~0.5 秒），仍不行才上 3x 强通道。
     *
     * 最坏情况（三档全失败）比直接 3x 多两次快尝试，代价很小；而常见情况快一个数量级。
     */
    fun decodeProgressive(original: Bitmap): List<String> {
        if (original.width < 10 || original.height < 10) return emptyList()
        for (scale in listOf(1f, 2f, 3f)) {
            val r = decode(original, scale)
            if (r.isNotEmpty()) return r
        }
        return emptyList()
    }
}