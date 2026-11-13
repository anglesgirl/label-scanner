package com.anglesgirl.labelscanner.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Handler
import android.os.Looper
import com.anglesgirl.labelscanner.model.SlipOcrParser
import java.io.File

/**
 * 单据 OCR 编排：照片文件 → 解码（采样 + EXIF 转正）→ ML Kit 纯文字识别
 * → [SlipOcrParser] 读数量 / 箱数 / 物料编码。回调一律回主线程；任何一步
 * 失败都回一个空 Result（弹窗照常弹，字段留空手填），不阻断单据归档。
 * 调用方在回调里先判 Activity 是否已销毁再弹框。
 */
object SlipOcrRunner {

    private val mainHandler = Handler(Looper.getMainLooper())

    fun run(photo: File, onDone: (SlipOcrParser.Result) -> Unit) {
        Thread {
            val bmp = decode(photo)
            if (bmp == null) {
                post { onDone(SlipOcrParser.Result()) }
                return@Thread
            }
            StaticRecognizer.recognizeText(
                bmp,
                onResult = { text ->
                    val result = runCatching { SlipOcrParser.parse(text) }
                        .getOrDefault(SlipOcrParser.Result())
                    post { onDone(result) }
                },
                onError = {
                    post { onDone(SlipOcrParser.Result()) }
                },
            )
        }.start()
    }

    private fun post(block: () -> Unit) = mainHandler.post(block)

    /** 解码：长边 ≤ 2048 采样防 OOM，再按 EXIF 转正。 */
    private fun decode(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (maxSide / sample > 2048) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
        return runCatching {
            val exif = ExifInterface(file.absolutePath)
            val deg = when (
                exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            ) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
            if (deg == 0) raw
            else Bitmap.createBitmap(
                raw, 0, 0, raw.width, raw.height,
                Matrix().apply { postRotate(deg.toFloat()) },
                true,
            )
        }.getOrDefault(raw)
    }
}
