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
 */
object ImageEnhance {

    /** 提亮 + 高对比度：像素 = clamp(p*c + 128*(1-c) + 128*b)，c=对比度 b=提亮。 */
    fun enhanceBright(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = 1.25f
        val b = 0.14f
        val offset = 128f * (1f - c) + 128f * b
        val cm = ColorMatrix().apply {
            setScale(c, c, c, 1f)
            postTranslate(offset, offset, offset, 0f)
        }
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
        Canvas(out).drawBitmap(src, 0f, 0f, paint)
        return out
    }

    /** 灰度高对比（近似二值化）：去色 + 强对比 + 负偏移，暗的压黑、亮的拉白。 */
    fun binarize(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val cm = ColorMatrix().apply {
            setSaturation(0f)
            setScale(2.4f, 2.4f, 2.4f, 1f)
            postTranslate(-156f, -156f, -156f, 0f)
        }
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
        Canvas(out).drawBitmap(src, 0f, 0f, paint)
        return out
    }
}
