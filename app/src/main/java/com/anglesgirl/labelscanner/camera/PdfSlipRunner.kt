package com.anglesgirl.labelscanner.camera

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import com.anglesgirl.labelscanner.model.SlipOcrParser
import java.io.File
import java.io.FileOutputStream

/**
 * PDF 单据读取：系统 PdfRenderer（全离线，不引第三方库）逐页渲染成图，
 * 走现有 [SlipOcrRunner] 读数量 / 箱数 / 物料编码。
 *
 * 逐页合并：数量、箱数、物料各取先读到的非空值；三项齐了就提前停，
 * 最多读前 [MAX_PAGES] 页（多页 PDF 太慢）。首页图一并回传，调用方
 * 把它当单据照片归档；PDF 原文件由调用方当附件存。
 * 打不开（加密/损坏）回 opened=false，调用方只提示不弹确认框。
 */
object PdfSlipRunner {

    data class Result(
        val ocr: SlipOcrParser.Result,
        /** 首页渲染图（JPEG 临时文件）；打不开或无页面时为 null。 */
        val firstPageJpeg: File?,
        val opened: Boolean,
    )

    const val MAX_PAGES = 5

    private val mainHandler = Handler(Looper.getMainLooper())

    fun run(pdf: File, onDone: (Result) -> Unit) {
        Thread {
            val pages = renderPages(pdf)
            if (pages == null) {
                post { onDone(Result(SlipOcrParser.Result(), null, opened = false)) }
                return@Thread
            }
            if (pages.isEmpty()) {
                post { onDone(Result(SlipOcrParser.Result(), null, opened = true)) }
                return@Thread
            }
            // 首页图留给调用方归档；其余页 OCR 完即删。
            val firstJpeg = pages[0]
            var merged = SlipOcrParser.Result()
            var index = 0
            fun ocrNext() {
                if (index >= pages.size ||
                    (merged.quantity != null && merged.quantity != 0 &&
                        merged.boxCount != null && merged.boxCount != 0 &&
                        !merged.materialCode.isNullOrBlank())
                ) {
                    for (i in 1 until pages.size) runCatching { pages[i].delete() }
                    post { onDone(Result(merged, firstJpeg, opened = true)) }
                    return
                }
                val jpg = pages[index]
                index++
                SlipOcrRunner.run(jpg) { r ->
                    if (merged.quantity == null || merged.quantity == 0) {
                        if (r.quantity != null && r.quantity != 0) merged = merged.copy(quantity = r.quantity)
                    }
                    if (merged.boxCount == null || merged.boxCount == 0) {
                        if (r.boxCount != null && r.boxCount != 0) merged = merged.copy(boxCount = r.boxCount)
                    }
                    if (merged.materialCode.isNullOrBlank() && !r.materialCode.isNullOrBlank()) {
                        merged = merged.copy(materialCode = r.materialCode)
                    }
                    ocrNext()
                }
            }
            ocrNext()
        }.start()
    }

    /**
     * 返回各页 OCR 原文拼接（不解析）：给多行入库单用。打不开回 opened=false；
     * 能打开但无页时原文为空串。首页之后各页的临时图 OCR 完即删。
     */
    fun runText(pdf: File, onDone: (text: String, opened: Boolean) -> Unit) {
        Thread {
            val pages = renderPages(pdf)
            if (pages == null) {
                post { onDone("", opened = false) }
                return@Thread
            }
            if (pages.isEmpty()) {
                post { onDone("", opened = true) }
                return@Thread
            }
            val texts = mutableListOf<String>()
            var index = 0
            fun ocrNext() {
                if (index >= pages.size) {
                    for (i in pages.indices) runCatching { pages[i].delete() }
                    post { onDone(texts.joinToString("\n"), opened = true) }
                    return
                }
                val jpg = pages[index]
                index++
                SlipOcrRunner.runText(jpg) { text ->
                    if (text.isNotBlank()) texts.add(text)
                    ocrNext()
                }
            }
            ocrNext()
        }.start()
    }

    private fun post(block: () -> Unit) = mainHandler.post(block)

    /**
     * 渲染：null = 打不开（加密/损坏）；空列表 = 能打开但 0 页。
     * 长边 ≤ 2048（跟 SlipOcrRunner 解码上限一致），先填白底再渲染，
     * 否则透明底转 JPEG 会变黑。
     */
    private fun renderPages(pdf: File): List<File>? {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            pfd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            val count = renderer.pageCount
            val out = mutableListOf<File>()
            for (i in 0 until minOf(count, MAX_PAGES)) {
                val page = renderer.openPage(i)
                try {
                    val scale = 2048f / maxOf(page.width, page.height).coerceAtLeast(1)
                    val w = maxOf(1, (page.width * scale).toInt())
                    val h = maxOf(1, (page.height * scale).toInt())
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val jpg = File(
                        pdf.parentFile,
                        "tmp_pdf_p${i}_${System.currentTimeMillis()}.jpg",
                    )
                    FileOutputStream(jpg).use { outStream ->
                        bmp.compress(Bitmap.CompressFormat.JPEG, 90, outStream)
                    }
                    bmp.recycle()
                    out += jpg
                } finally {
                    page.close()
                }
            }
            return out
        } catch (_: Exception) {
            return null
        } finally {
            runCatching { renderer?.close() }
            runCatching { pfd?.close() }
        }
    }
}
