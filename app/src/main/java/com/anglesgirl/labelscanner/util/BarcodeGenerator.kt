package com.anglesgirl.labelscanner.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.oned.Code128Writer

/**
 * 一维条码生成（zxing-core Code128 编码，zxing-cpp 只解码不编码）。
 * 集成码拆分：每个 SN 生成独立条码，条码图【下方自带内容文本】，
 * 保存/打印时文字与条码一体。
 */
object BarcodeGenerator {

    /**
     * 生成内容对应的 Code128 条码 Bitmap（下方附加内容文本，居中）。
     * 内容非法（过长/编码失败）返回 null。
     */
    fun generate(
        content: String,
        width: Int = 640,
        barHeight: Int = 170,
        textSizePx: Int = 46,
    ): Bitmap? {
        val text = content.trim()
        if (text.isEmpty()) return null
        return try {
            val hints = mapOf(
                EncodeHintType.MARGIN to 8,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            )
            val matrix = Code128Writer().encode(text, BarcodeFormat.CODE_128, width, barHeight, hints)

            // 下方文本区域：文本 + 上下留白
            val pad = 10
            val textH = textSizePx + pad * 2
            val totalH = barHeight + textH
            val bmp = Bitmap.createBitmap(width, totalH, Bitmap.Config.RGB_565)
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.WHITE)

            // 条码区
            for (x in 0 until width) {
                for (y in 0 until barHeight) {
                    if (matrix.get(x, y)) bmp.setPixel(x, y, 0xFF000000.toInt())
                }
            }

            // 文本区：字号自适应（放不下就缩小），水平居中
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            }
            var size = textSizePx
            while (size > 16) {
                paint.textSize = size.toFloat()
                if (paint.measureText(text) <= width - 24) break
                size -= 2
            }
            val bounds = Rect()
            paint.getTextBounds(text, 0, text.length, bounds)
            val x = ((width - bounds.width()) / 2f) - bounds.left
            val y = barHeight + pad + size
            canvas.drawText(text, x, y, paint)

            bmp
        } catch (e: Exception) {
            null
        }
    }
}
