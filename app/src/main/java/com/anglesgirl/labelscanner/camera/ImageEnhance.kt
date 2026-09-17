package com.anglesgirl.labelscanner.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint

/**
 * 暗光扫码图像增强（Canvas + ColorMatrix GPU 加速，毫秒级）。
 *
 * 为什么：暗光下条码识别率骤降（对比度低，模块边界糊）。文档模式已有逐像素
 * 增强（ImageWarp.enhance），但 1920x1080 逐像素 setPixel 每帧跑会卡死 ——
 * 这里用 ColorMatrix 走 GPU 光栅化，单帧几毫秒，可放进实时解码的失败兜底。
 *
 * 两条增强：
 * - enhanceBright：提亮 + 高对比度（同文档模式 1.25x 对比，再加一点提亮）
 * - binarize：灰度高对比（近似二值化，暗光/浅字条码的模块边界最清晰）
 *
 * ⚠️ ColorMatrix 没有 postTranslate/setTranslate 公开方法，偏移必须直接写进
 * 矩阵数组（第 5 列）构造 —— 之前两版构建失败就是这个原因。
 */
object ImageEnhance {

    /**
     * 提亮 + 高对比度：像素 = clamp(p*c + offset)，c=对比度、offset=128*(1-c)+128*b。
     * 矩阵：对角 c、第 5 列 offset（RGB 相同），Alpha 不变。
     */
    fun enhanceBright(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = 1.25f
        val b = 0.14f
        val offset = 128f * (1f - c) + 128f * b
        val cm = ColorMatrix(
            floatArrayOf(
                c, 0f, 0f, 0f, offset,
                0f, c, 0f, 0f, offset,
                0f, 0f, c, 0f, offset,
                0f, 0f, 0f, 1f, 0f,
            )
        )
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
        Canvas(out).drawBitmap(src, 0f, 0f, paint)
        return out
    }

    /**
     * 灰度高对比（近似二值化）：BT.709 亮度灰度 × 强对比 + 负偏移，
     * 暗的压黑、亮的拉白。矩阵一次性算好（灰度系数 × 对比度写在 3x3，
     * 偏移写在第 5 列）。
     */
    fun binarize(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = 2.4f
        val t = -156f
        val r = 0.213f * c
        val g = 0.715f * c
        val b = 0.072f * c
        val cm = ColorMatrix(
            floatArrayOf(
                r, g, b, 0f, t,
                r, g, b, 0f, t,
                r, g, b, 0f, t,
                0f, 0f, 0f, 1f, 0f,
            )
        )
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
        Canvas(out).drawBitmap(src, 0f, 0f, paint)
        return out
    }
}
