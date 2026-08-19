package com.anglesgirl.labelscanner.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import kotlin.math.abs

/**
 * 纯 android.graphics 实现的标签拉正 + 图像增强（零额外依赖）。
 * 思路移植自开源 doc_scanner（Sobel/透视变换/对比度增强），适配 Kotlin + Bitmap。
 *
 * 用途：系统相机高清拍的标签若歪斜，用户拖四角框住标签 → 透视变换拉正 → 增强 → OCR。
 */

object ImageWarp {

    /**
     * 角点排序：返回 [左上, 右上, 右下, 左下]。
     * 依据：左上 = (x+y) 最小；右下 = (x+y) 最大；
     *       右上 = (y-x) 最小；左下 = (y-x) 最大。
     */
    fun sortCorners(corners: List<PointF>): List<PointF> {
        require(corners.size == 4) { "需要恰好 4 个角点" }
        val bySum = corners.sortedBy { it.x + it.y }
        val topLeft = bySum.first()
        val bottomRight = bySum.last()
        val rest = corners.minus(listOf(topLeft, bottomRight)).sortedBy { it.y - it.x }
        val topRight = rest.first()   // y-x 小 → 右上
        val bottomLeft = rest.last()  // y-x 大 → 左下
        return listOf(topLeft, topRight, bottomRight, bottomLeft)
    }

    /**
     * 四点透视变换：把任意四边形 corners 拉正为 width×height 的矩形。
     * 用双线性插值近似透视映射（轻量、零依赖，质量满足标签 OCR 需求）。
     */
    fun perspectiveTransform(src: Bitmap, corners: List<PointF>, width: Int, height: Int): Bitmap {
        val sorted = sortCorners(corners)
        val out = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val (tl, tr, br, bl) = listOf(sorted[0], sorted[1], sorted[2], sorted[3])

        for (y in 0 until height) {
            val ty = y.toFloat() / height
            for (x in 0 until width) {
                val tx = x.toFloat() / width
                // 双线性插值求源图坐标
                val topX = lerp(tl.x, tr.x, tx)
                val topY = lerp(tl.y, tr.y, tx)
                val botX = lerp(bl.x, br.x, tx)
                val botY = lerp(bl.y, br.y, tx)
                val srcX = lerp(topX, botX, ty).toInt().coerceIn(0, src.width - 1)
                val srcY = lerp(topY, botY, ty).toInt().coerceIn(0, src.height - 1)
                out.setPixel(x, y, src.getPixel(srcX, srcY))
            }
        }
        return out
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    /** 计算拉正后输出尺寸（取四边最大边长） */
    fun outputSize(corners: List<PointF>): Pair<Int, Int> {
        val sorted = sortCorners(corners)
        val (tl, tr, br, bl) = listOf(sorted[0], sorted[1], sorted[2], sorted[3])
        val w1 = dist(tl, tr); val w2 = dist(bl, br)
        val h1 = dist(tr, br); val h2 = dist(tl, bl)
        val w = maxOf(w1, w2).toInt().coerceAtLeast(1)
        val h = maxOf(h1, h2).toInt().coerceAtLeast(1)
        return w to h
    }

    private fun dist(a: PointF, b: PointF): Float = kotlin.math.hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat()

    /**
     * 图像增强：对比度 + 锐化（移植 doc_scanner 的 _autoEnhance）。
     * 让浅色字、小字在 OCR 前更清晰。
     */
    fun enhance(src: Bitmap): Bitmap {
        var cur = src.copy(Bitmap.Config.ARGB_8888, true)
        cur = adjustContrast(cur, 1.25f)
        cur = sharpen(cur)
        return cur
    }

    /** 黑白二值化（高对比，适合纯文字标签） */
    fun blackAndWhite(src: Bitmap): Bitmap {
        val gray = toGrayscale(src)
        val out = Bitmap.createBitmap(gray.width, gray.height, Bitmap.Config.ARGB_8888)
        val p = Paint()
        val c = Canvas(out)
        c.drawBitmap(gray, 0f, 0f, p)
        for (y in 0 until out.height) {
            for (x in 0 until out.width) {
                val pixel = out.getPixel(x, y)
                val lum = (android.graphics.Color.red(pixel) * 0.299
                    + android.graphics.Color.green(pixel) * 0.587
                    + android.graphics.Color.blue(pixel) * 0.114).toInt()
                val v = if (lum > 128) 255 else 0
                out.setPixel(x, y, android.graphics.Color.argb(255, v, v, v))
            }
        }
        return out
    }

    private fun toGrayscale(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val m = Matrix()
        val p = Paint()
        val c = Canvas(out)
        val colorMatrix = android.graphics.ColorMatrix().apply { setSaturation(0f) }
        p.colorFilter = android.graphics.ColorMatrixColorFilter(colorMatrix)
        c.drawBitmap(src, m, p)
        return out
    }

    private fun adjustContrast(src: Bitmap, factor: Float): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val f = (259f * (factor * 255f + 255f)) / (255f * (259f - factor * 255f))
        for (y in 0 until src.height) {
            for (x in 0 until src.width) {
                val px = src.getPixel(x, y)
                val r = clamp(f * (android.graphics.Color.red(px) - 128f) + 128f).toInt()
                val g = clamp(f * (android.graphics.Color.green(px) - 128f) + 128f).toInt()
                val b = clamp(f * (android.graphics.Color.blue(px) - 128f) + 128f).toInt()
                out.setPixel(x, y, android.graphics.Color.argb(255, r, g, b))
            }
        }
        return out
    }

    private fun sharpen(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        for (y in 1 until src.height - 1) {
            for (x in 1 until src.width - 1) {
                val c = src.getPixel(x, y)
                val t = src.getPixel(x, y - 1)
                val b = src.getPixel(x, y + 1)
                val l = src.getPixel(x - 1, y)
                val r = src.getPixel(x + 1, y)
                val nr = clamp((5f * android.graphics.Color.red(c)
                    - android.graphics.Color.red(t) - android.graphics.Color.red(b)
                    - android.graphics.Color.red(l) - android.graphics.Color.red(r))).toInt()
                val ng = clamp((5f * android.graphics.Color.green(c)
                    - android.graphics.Color.green(t) - android.graphics.Color.green(b)
                    - android.graphics.Color.green(l) - android.graphics.Color.green(r))).toInt()
                val nb = clamp((5f * android.graphics.Color.blue(c)
                    - android.graphics.Color.blue(t) - android.graphics.Color.blue(b)
                    - android.graphics.Color.blue(l) - android.graphics.Color.blue(r))).toInt()
                out.setPixel(x, y, android.graphics.Color.argb(255, nr, ng, nb))
            }
        }
        // 边缘行直接拷贝，避免黑边
        for (x in 0 until src.width) {
            out.setPixel(x, 0, src.getPixel(x, 0))
            out.setPixel(x, src.height - 1, src.getPixel(x, src.height - 1))
        }
        for (y in 0 until src.height) {
            out.setPixel(0, y, src.getPixel(0, y))
            out.setPixel(src.width - 1, y, src.getPixel(src.width - 1, y))
        }
        return out
    }

    private fun clamp(v: Float) = v.coerceIn(0f, 255f)
}
