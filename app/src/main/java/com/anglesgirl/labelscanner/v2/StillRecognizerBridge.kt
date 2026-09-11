package com.anglesgirl.labelscanner.v2

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.anglesgirl.labelscanner.camera.StaticRecognizer
import java.io.File

/**
 * 静图识别桥：把拍下来的照片交给**旧版已验证的强通道**
 * （ML Kit + zxing-cpp 3× 放大，见 [StaticRecognizer]）。
 *
 * 为什么要复用旧版而不是重写：实时帧分辨率低、有运动模糊，小码/斜角易漏；
 * 旧版这套静态识别已经调过 zxing-cpp 的 Options（tryHarder/tryRotate/
 * tryInvert/tryDownscale 全开，只开 tryHarder 会漏码），是踩过坑才对的配置。
 * 这里负责"文件 → Bitmap → 取原始码 + OCR 文本"，不重复实现识别。
 * 注意：**OCR 文本也要带回**（部分标签的物料/日期只有印字，没有条码）。
 */
object StillRecognizerBridge {

    private const val TAG = "StillBridge"

    /** 照片最长边限制：过大不仅慢，还会让 zxing 的 3× 放大爆内存。 */
    private const val MAX_EDGE = 2400

    fun recognize(
        file: File,
        onDone: (codes: List<String>, ocrText: String) -> Unit,
        onFail: (String) -> Unit,
    ) {
        if (!file.exists() || file.length() == 0L) {
            onFail("照片文件为空")
            return
        }
        val bitmap = decodeScaled(file)
        if (bitmap == null) {
            onFail("照片解码失败")
            return
        }
        recognizeBitmap(bitmap, onDone, onFail)
    }

    /**
     * 直接识别一张 Bitmap。
     * 之所以单独提供：拍照后会先用 [LabelRectifier] 做透视矫正，
     * 要识别的对象是**矫正后的正图**，而不是原始照片。
     */
    fun recognizeBitmap(
        bitmap: Bitmap,
        onDone: (codes: List<String>, ocrText: String) -> Unit,
        onFail: (String) -> Unit,
    ) {
        try {
            StaticRecognizer.recognize(
                bitmap = bitmap,
                lookup69 = null,
                onResult = { result ->
                    // barcodes 是原始码（v2 的解析输入）
                    val codes = result.barcodes
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                    // ocrText 必须一起带回去：有些标签的物料/日期只以印字存在，
                    // 条码里根本没有；之前把这段丢掉了，等于放弃了 OCR 这条通道。
                    val ocr = result.ocrText.orEmpty()
                    Log.i(TAG, "静图识别: ${codes.size} 个码, OCR ${ocr.length} 字符")
                    onDone(codes, ocr)
                },
                onError = { msg ->
                    Log.w(TAG, "静图识别失败: $msg")
                    onFail(msg)
                },
            )
        } catch (t: Throwable) {
            Log.e(TAG, "静图识别异常", t)
            onFail(t.message ?: "识别异常")
        }
    }

    private fun decodeScaled(file: File): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        var edge = maxOf(bounds.outWidth, bounds.outHeight)
        while (edge / sample > MAX_EDGE) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        BitmapFactory.decodeFile(file.absolutePath, opts)
    } catch (t: Throwable) {
        Log.e(TAG, "解码失败", t)
        null
    }
}
