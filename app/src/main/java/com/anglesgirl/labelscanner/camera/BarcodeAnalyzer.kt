package com.anglesgirl.labelscanner.camera

import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import android.graphics.Bitmap
import com.anglesgirl.labelscanner.model.LabelParser
import com.anglesgirl.labelscanner.model.LabelResult

/**
 * ML Kit 双通道分析器（字段锁定版）。
 *
 * 核心设计：**识别结果锁定（first-wins）** —— 已识别到的字段值绝不覆盖，
 * 后续帧只补空缺字段。解决实时扫描时 OCR/条码每帧抖动、字段来回跳的问题：
 *   - 序列号/物料/日期/69码 一旦有值就锁定
 *   - OCR 附加信息（型号/颜色/硒鼓）同样锁定
 *   - 条码签名变化（换标签）→ 自动重置锁定
 *   - 手动 reset()（保存/丢弃后）→ 解锁
 *
 * 条码 + OCR 双通道同时跑：条码准但字段少，OCR 补型号/颜色/交叉验证。
 * 只有当合并结果【发生变化】时才回调 UI（不会每秒乱跳）。
 */
class BarcodeAnalyzer(
    private val onResult: (LabelResult) -> Unit,
    private val lookup69: ((String) -> String?)? = null,
) : ImageAnalysis.Analyzer {

    private val tag = "BarcodeAnalyzer"

    // 全格式条码（能扫到什么返回什么）
    private val scanner: BarcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
            .build()
    )

    // 中文 OCR
    private val recognizer: TextRecognizer = TextRecognition.getClient(
        ChineseTextRecognizerOptions.Builder().build()
    )

    /** 已锁定的识别结果（first-wins 累积） */
    private var locked: LabelResult? = null
    /** 上一帧条码签名（检测换标签） */
    private var lastBarcodeSig = ""
    private var processing = false

    /**
     * zxing-cpp 强通道线程池。
     *
     * 实时分析如果只跑 ML Kit 会漏小码/斜角/密集码 —— 旧版把 zxing-cpp
     * 只用在了拍照（静态）路径，实时扫描没有强通道。这里补上，
     * 但**不能每帧都跑**（C++ 解码较重，会拖垮预览帧率），
     * 因此按帧间隔抽样执行，结果与 ML Kit 合并去重。
     */
    private val zxingPool = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "zxing-realtime").apply { isDaemon = true }
    }
    private var frameSeq = 0

    companion object {
        /** 每多少帧跑一次 zxing 强通道（折中：不漏码也不掉帧）。 */
        private const val ZXING_EVERY_N_FRAMES = 5
    }

    override fun analyze(imageProxy: ImageProxy) {
        if (processing) {
            imageProxy.close()
            return
        }
        processing = true

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            processing = false
            return
        }

        val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        // 1. 条码检测（ML Kit 每帧；zxing 强通道抽样）
        frameSeq++
        val useZxing = frameSeq % ZXING_EVERY_N_FRAMES == 0
        val zxingFuture: java.util.concurrent.Future<List<String>>? = if (useZxing) {
            val bmp = try { imageProxy.toBitmap() } catch (t: Throwable) { null }
            if (bmp != null) {
                zxingPool.submit<List<String>> {
                    runCatching { ZxingDecoder.decode(bmp) }.getOrDefault(emptyList())
                }
            } else null
        } else null

        scanner.process(inputImage)
            .addOnSuccessListener { barcodes ->
                val mlValues = barcodes.mapNotNull { it.rawValue }.distinct()
                val zx = try { zxingFuture?.get() ?: emptyList() } catch (t: Throwable) { emptyList() }
                val values = (mlValues + zx).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                if (zx.isNotEmpty() || (useZxing && values.size > mlValues.size)) {
                    Log.i(tag, "强通道补充: ml=${mlValues.size} zxing=${zx.size} 合并=${values.size}")
                }
                runOcr(inputImage, values, imageProxy)
            }
            .addOnFailureListener { e ->
                Log.w(tag, "barcode scan failed, fallback ocr only", e)
                val zx = try { zxingFuture?.get() ?: emptyList() } catch (t: Throwable) { emptyList() }
                runOcr(inputImage, zx, imageProxy)
            }
    }

    private fun runOcr(inputImage: InputImage, barcodes: List<String>, imageProxy: ImageProxy) {
        recognizer.process(inputImage)
            .addOnSuccessListener { text ->
                val ocr = text.text?.trim() ?: ""
                emit(barcodes, ocr, imageProxy)
            }
            .addOnFailureListener { e ->
                Log.w(tag, "ocr failed, use barcodes only", e)
                emit(barcodes, "", imageProxy)
            }
    }

    private fun emit(barcodes: List<String>, ocrText: String, imageProxy: ImageProxy) {
        val fresh = LabelParser.parse(barcodes, ocrText, lookup69)
        val sig = barcodes.joinToString(",")

        // 换标签检测：条码签名变化（且当前帧有条码）→ 重置锁定
        if (barcodes.isNotEmpty() && sig != lastBarcodeSig) {
            locked = null
            lastBarcodeSig = sig
        }

        // 合并：first-wins 锁定 + 补缺
        val merged = merge(locked, fresh)

        // 只有关键字段发生变化才回调（不每秒乱跳）
        // 注意：不能用 merged != locked —— barcodes/OCR 原文每帧抖动，
        // 用字段签名判断，barcodes 怎么变都不影响。
        if (merged.hasData && fieldSig(merged) != fieldSig(locked)) {
            locked = merged
            onResult(merged)
        }
        imageProxy.close()
        processing = false
    }

    /** 关键字段签名：这些不变就不刷新 UI */
    private fun fieldSig(r: LabelResult?): String = r?.let {
        listOf(
            it.supplier, it.serialNumber, it.materialCode,
            it.quantity.toString(), it.productionDate, it.ean69,
            it.model, it.color, it.tonerModel,
        ).joinToString("|")
    } ?: ""

    /**
     * 合并识别结果：已有字段锁定不覆盖，缺失字段用新帧补上。
     * 字段优先级：条码通道的结果已经在 parse 里优先，这里只做 first-wins。
     */
    private fun merge(prev: LabelResult?, fresh: LabelResult): LabelResult {
        if (prev == null) return fresh
        return prev.copy(
            // 已有值锁定，缺失才补
            supplier = if (prev.supplier != "NA") prev.supplier else fresh.supplier,
            serialNumber = prev.serialNumber.ifBlank { fresh.serialNumber },
            materialCode = prev.materialCode.ifBlank { fresh.materialCode },
            quantity = prev.quantity,
            productionDate = prev.productionDate.ifBlank { fresh.productionDate },
            ean69 = prev.ean69.ifBlank { fresh.ean69 },
            model = prev.model.ifBlank { fresh.model },
            color = prev.color.ifBlank { fresh.color },
            tonerModel = prev.tonerModel.ifBlank { fresh.tonerModel },
            // OCR 原文：首次非空即锁定（OCR 每帧抖动，避免文本来回变）
            ocrText = prev.ocrText.ifBlank { fresh.ocrText },
            // 条码集：保留最新（换标签判定用）
            barcodes = fresh.barcodes.ifEmpty { prev.barcodes },
        )
    }

    /** 手动重置锁定（保存/丢弃后调用，解锁识别下一个标签） */
    fun reset() {
        locked = null
        lastBarcodeSig = ""
    }

    fun close() {
        scanner.close()
        recognizer.close()
        zxingPool.shutdownNow()
    }
}
