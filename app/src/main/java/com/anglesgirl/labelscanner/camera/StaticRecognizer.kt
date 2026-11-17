package com.anglesgirl.labelscanner.camera

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.anglesgirl.labelscanner.model.BoxScanClassifier
import com.anglesgirl.labelscanner.model.LabelParser
import com.anglesgirl.labelscanner.util.Diag
import com.anglesgirl.labelscanner.model.LabelResult
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 静态图片识别器：对【单张图片】（文档扫描矫正结果 / 相册导入）跑双通道识别。
 *
 * 条码 + OCR 并行，结果合并后回调。
 *
 * 注意（2026-08 修复）：识别器实例用【懒加载 + close 后可重建】策略——
 * 之前是单例创建后 close() 就永久失效，Activity 被系统回收重建后
 * 一调用就抛 "Client is closed" → 全部识别失败。现在 close() 只置空，
 * 下次识别自动重建，Activity 生命周期不影响识别。
 */
object StaticRecognizer {

    private const val TAG = "StaticRecognizer"

    private var recognizer: TextRecognizer? = null

    /** ZXing 解码线程池（放大 3x 解码是 CPU 密集，不阻塞主线程） */
    private val zxingPool = Executors.newSingleThreadExecutor { r ->
        Thread(r, "zxing-decode").apply { isDaemon = true }
    }
    private val recognitionPool = Executors.newSingleThreadExecutor { r ->
        Thread(r, "recognition-orchestrator").apply { isDaemon = true }
    }

    private fun getRecognizer(): TextRecognizer =
        recognizer ?: TextRecognition.getClient(
            TextRecognizerOptions.DEFAULT_OPTIONS
        ).also { recognizer = it }

    /**
     * 按 EXIF 方向把图转正。
     *
     * 为什么必须做（用户报"拍照和文档模式现在不处理 y 轴"）：
     * 相机/系统相册给的 JPEG，真实方向记在 EXIF 里，`BitmapFactory` 不会读取，
     * 解码出来就是横的 —— OCR 与 zxing 都在横图上工作，标签横躺，识别率骤降。
     * 用系统自带的 `android.media.ExifInterface`（API 24+，本项目 minSdk 26），
     * 不额外引依赖。
     */
    internal fun applyExifRotation(
        resolver: ContentResolver,
        uri: Uri,
        bmp: Bitmap,
    ): Bitmap = runCatching {
        val deg = resolver.openInputStream(uri)?.use { ins ->
            val exif = android.media.ExifInterface(ins)
            when (
                exif.getAttributeInt(
                    android.media.ExifInterface.TAG_ORIENTATION,
                    android.media.ExifInterface.ORIENTATION_NORMAL,
                )
            ) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } ?: 0
        if (deg == 0) bmp
        else Bitmap.createBitmap(
            bmp, 0, 0, bmp.width, bmp.height,
            android.graphics.Matrix().apply { postRotate(deg.toFloat()) },
            true,
        )
    }.getOrDefault(bmp)

    /**
     * 从 Uri 解码 Bitmap（自动缩放，避免超大图 OOM），然后识别。
     * 解码用 FileDescriptor（content:// 最可靠），失败 fallback 到流。
     */
    fun recognizeUri(
        resolver: ContentResolver,
        uri: Uri,
        lookup69: ((String) -> String?)?,
        onResult: (LabelResult) -> Unit,
        onError: (String) -> Unit,
        /** 多码场景（如单箱一箱多 SN）：条码走并集解码，不提前返回。单码场景默认关（快）。 */
        multiCode: Boolean = false,
    ) {
        if (LiveSampleEvidence.isVerified(uri)) {
            recognitionPool.execute {
                try {
                    val evidence = LiveSampleEvidence.read(resolver, uri)
                    // Repeated matching physical observations outrank a possibly stale 69-code lookup.
                    val parsed = LabelParser.parse(evidence.barcodes, evidence.ocrText)
                    onResult(parsed.copy(
                        unconfirmedBarcodes = emptyList(),
                        unconfirmedOcrText = "",
                        recognitionNote = "同一标签完整数据累计 ${LiveSampleConsensus.REQUIRED_MATCHES} 次一致，已确认；手抖或单帧失败不清空采样",
                    ))
                } catch (e: Exception) { onError("已确认的采样数据无法读取，请重新扫描") }
            }
            return
        }
        if (BurstRecognition.isBurst(uri)) {
            BurstRecognition.recognize(resolver, uri, onResult = { evidence ->
                val parsed = LabelParser.parse(evidence.barcodes, evidence.ocrText, lookup69)
                onResult(parsed.copy(
                    unconfirmedBarcodes = emptyList(),
                    unconfirmedOcrText = "",
                    recognitionNote = "已比对 ${evidence.frameCount} 帧，至少 3 帧一致才自动采用；仅采用稳定结果，后续可人工核对",
                ))
            }, onError = onError)
            return
        }
        val raw = decodeSampledBitmap(resolver, uri)
        if (raw == null) {
            onError("无法读取图片")
            return
        }
        // 先按 EXIF 转正，再交给识别通道（否则 OCR / zxing 拿到的都是躺着的图）。
        val bmp = applyExifRotation(resolver, uri, raw)
        // 相册路径保持原有的安全识别流程；拍照路径的增强已在 CropActivity 中完成。
        // 不在这里逐像素处理 4096 边长图片，避免主线程卡死/OOM。
        recognize(bmp, lookup69, onResult, onError, multiCode)
    }

    /**
     * 批量清单单页识别（一张纸多个条码）：
     * 解出图中【全部条码】+ OCR 全文，不做字段归类，原样回传由 UI 决定怎么入库。
     *
     * 返回页面全部码和 OCR 文本，由统一清单页负责核对、累计与按序列号独立保存。
     */
    fun recognizeUriMulti(
        resolver: ContentResolver,
        uri: Uri,
        onResult: (codes: List<String>, ocrText: String) -> Unit,
        onError: (String) -> Unit,
        onEvidence: ((BurstConsensus.Result) -> Unit)? = null,
    ) {
        if (LiveCodePageEvidence.isPage(uri)) {
            recognitionPool.execute {
                runCatching { LiveCodePageEvidence.read(resolver, uri) }
                    .onSuccess { onResult(it, "") }
                    .onFailure { onError("实时采集的本页码无法读取，请重新扫描") }
            }
            return
        }
        if (LiveSampleEvidence.isVerified(uri)) {
            recognitionPool.execute {
                try {
                    val evidence = LiveSampleEvidence.read(resolver, uri)
                    if (onEvidence != null) onEvidence(evidence)
                    else onResult(evidence.barcodes, evidence.ocrText)
                } catch (e: Exception) { onError("已确认的采样数据无法读取，请重新扫描") }
            }
            return
        }
        if (BurstRecognition.isBurst(uri)) {
            BurstRecognition.recognize(resolver, uri, onResult = { evidence ->
                if (onEvidence != null) onEvidence(evidence)
                else onResult(evidence.barcodes, evidence.ocrText)
            }, onError = onError)
            return
        }
        val raw = decodeSampledBitmap(resolver, uri)
        if (raw == null) {
            onError("无法读取图片")
            return
        }
        val bmp = applyExifRotation(resolver, uri, raw)
        recognizeMulti(bmp, onResult, onError)
    }

    /** 识别单张 Bitmap：**条码全走 zxing-cpp**，**ML Kit 只做 OCR**（各司其职）。
     *
     * multiCode=true（单箱一箱多 SN）走 [recognizeMultiCode]：先 OCR 取 QTY，
     * 再渐进并集解码，SN 够 QTY 即停、QTY 读不到时全量跑。旧的 decodeUnion
     * 全跑 1x/2x/3x 是慢的根因；提前返回的 decode() 会在某一尺度解出 2 个就停，
     * 剩下 7 个永远没机会（2026-11-02 CTL-2300HK 标签实测：9 个 SN 只出 2 个，
     * 箱号 CA 码也丢了；多码清单 2026-10-01 已是同一教训）。单码场景保持默认关。
     */
    fun recognize(
        bitmap: Bitmap,
        lookup69: ((String) -> String?)?,
        onResult: (LabelResult) -> Unit,
        onError: (String) -> Unit,
        multiCode: Boolean = false,
    ) {
        // 多码场景（单箱一箱多 SN）走 QTY 早停快路，见 recognizeMultiCode。
        if (multiCode) {
            recognizeMultiCode(bitmap, lookup69, onResult)
            return
        }
        val input = InputImage.fromBitmap(bitmap, 0)
        val zxingFuture = zxingPool.submit<List<String>> {
            Log.i(TAG, "[ZXING_STATIC] start ${bitmap.width}x${bitmap.height} multiCode=$multiCode")
            ZxingDecoder.decode(bitmap).also {
                Log.i(TAG, "[ZXING_STATIC] complete count=${it.size}")
            }
        }
        // 条码/二维码一律交给 zxing-cpp（ML Kit 扫码能力弱：高密度 2D 码解不出，
        // 还会用旁边的 1D 结果干扰）。ML Kit 在本流程里**只负责 OCR**。
        finishWithBarcodes(input, emptyList(), zxingFuture, lookup69, onResult, onError, multiCode)
    }

    /**
     * 单箱拍照识别（多码）：先跑 OCR 取 QTY，再渐进并集解码。
     *
     * 为什么 OCR 先行（2026-11-17 提速）：旧流程是 7 遍全图并集解码（1x/2x/3x +
     * 增强/二值）跑完才 OCR；大图 2x/3x（4 倍/9 倍像素）是主要耗时。标签上的
     * QTY 就是本箱 SN 数，1x 解出的 SN 够 QTY 就不用再放大——够数即停。
     * QTY 读不到时回落全量并集解码，与旧流程完全一致。
     * 铁律：只有 SN 数 >= QTY 才停；不够数一遍都不能少（见 decodeUnionUntil）。
     */
    private fun recognizeMultiCode(
        bitmap: Bitmap,
        lookup69: ((String) -> String?)?,
        onResult: (LabelResult) -> Unit,
    ) {
        recognitionPool.submit {
            val input = InputImage.fromBitmap(bitmap, 0)
            // QTY 只在印字里，不在条码里：OCR 先跑，解码按 QTY 早停。
            val ocrText = ocrSync(input)
            val qty = BoxScanClassifier.extractQty(ocrText)
            val decodeFuture = zxingPool.submit<List<String>> {
                Log.i(TAG, "[ZXING_STATIC] start ${bitmap.width}x${bitmap.height} multiCode=true qty=$qty")
                val r = if (qty > 0) {
                    ZxingDecoder.decodeUnionUntil(bitmap) { acc -> countSnCandidates(acc) >= qty }
                } else {
                    ZxingDecoder.decodeUnion(bitmap)
                }
                Log.i(TAG, "[ZXING_STATIC] complete count=${r.size}")
                r
            }
            val merged = try {
                decodeFuture.get(25, TimeUnit.SECONDS)
                    .map { it.trim() }
                    .filter(String::isNotBlank)
                    .distinct()
            } catch (e: Exception) {
                decodeFuture.cancel(true)
                Log.w(TAG, "[ZXING_STATIC] decode timeout/failure; use OCR only: ${e.message}")
                emptyList()
            }
            Log.i(TAG, "[BARCODE_MERGE] multiCode qty=$qty merged=${merged.size}")
            // 上报识别实况：图片尺寸 + 解出的码 + QTY（早停是否触发靠 qty 与码数对照）。
            runCatching {
                Diag.event(
                    "static_recognized",
                    mapOf(
                        "img" to "${bitmap.width}x${bitmap.height}",
                        "codes" to merged.size,
                        "code_list" to merged.joinToString(" | ").take(280),
                        "qty" to qty,
                    )
                )
            }
            onResult(LabelParser.parse(merged, ocrText, lookup69))
        }
    }

    /** 同步跑一次 ML Kit 拉丁 OCR，返回全文（25 秒超时返回已读到的部分）。 */
    private fun ocrSync(input: InputImage): String {
        var ocrText = ""
        val latch = java.util.concurrent.CountDownLatch(1)
        getRecognizer().process(input)
            .addOnSuccessListener { t ->
                t.text?.trim()?.takeIf { it.isNotEmpty() }?.let { ocrText = it }
            }
            .addOnFailureListener { e -> Log.w(TAG, "ocr channel failed", e) }
            .addOnCompleteListener { latch.countDown() }
        latch.await(25, TimeUnit.SECONDS)
        Log.i(TAG, "[OCR_SYNC] chars=${ocrText.length}")
        return ocrText
    }

    /**
     * 早停计数：累计码集里“像 SN”的条码有几个。
     *
     * 保守口径（宁可少算、绝不多算 —— 多算会提前停、丢真 SN；少算最多回落全量解码）：
     *  - 逗号/分号分隔的多值码（QR 集成 SN）先拆开逐个计；
     *  - 只计 6~13 位、全字母数字、字母数字混排的码；
     *  - 69 商品码、10~12 位纯数字 SAP 码不计；
     *  - 14 位及以上的混合码不计 —— 那更像箱号/外箱码（如 PM016NP10054022），
     *    且 BoxParser 也会把它算进 SN，计进去会在“7 个真 SN + 1 个箱码 = 8”
     *    时提前停、丢掉第 8 个真 SN。
     * 本计数是 BoxParser 条码 SN 集的子集：计数值 >= QTY 时，BoxParser 的 SN
     * 数一定也 >= QTY；计数值不够时继续放大解码，绝不提前返回。
     */
    private fun countSnCandidates(codes: List<String>): Int {
        var n = 0
        for (raw in codes) {
            for (part in raw.split(',', ';', '，', '；')) {
                val c = part.trim()
                if (c.length in 6..13 && c.any { it.isLetter() } && c.any { it.isDigit() } &&
                    c.all { it.isLetterOrDigit() }
                ) n++
            }
        }
        return n
    }

    /**
     * 多码清单识别核心：zxing 全部条码 + ML Kit OCR 全文，异步回调 (codes, ocrText)。
     */
    fun recognizeMulti(
        bitmap: Bitmap,
        onResult: (codes: List<String>, ocrText: String) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (bitmap.width < 10 || bitmap.height < 10) {
            onError("图片尺寸过小")
            return
        }
        val input = InputImage.fromBitmap(bitmap, 0)
        val zxingFuture = zxingPool.submit<List<String>> {
            Log.i(TAG, "[MULTI_ZXING] start ${bitmap.width}x${bitmap.height}")
            // 多码场景用并集解码（2026-10-01）："任一尺度解出即返回"的 progressive/
            // 3x 直解会在部分码解出后提前返回，小码永远没机会；并集 1x/2x/3x 全跑取并集。
            ZxingDecoder.decodeUnion(bitmap).also {
                Log.i(TAG, "[MULTI_ZXING] complete count=${it.size}")
            }
        }
        recognitionPool.submit {
            val codes = try {
                zxingFuture.get(20, TimeUnit.SECONDS)
                    .map { it.trim() }
                    .filter(String::isNotBlank)
                    .distinct()
            } catch (e: Exception) {
                zxingFuture.cancel(true)
                Log.w(TAG, "[MULTI_ZXING] timeout/failure: ${e.message}")
                emptyList()
            }
            var ocrText = ""
            val latch = java.util.concurrent.CountDownLatch(1)
            getRecognizer().process(input)
                .addOnSuccessListener { t ->
                    t.text?.trim()?.takeIf { it.isNotEmpty() }?.let { ocrText = it }
                }
                .addOnFailureListener { e -> Log.w(TAG, "[MULTI_OCR] failed", e) }
                .addOnCompleteListener { latch.countDown() }
            latch.await(25, TimeUnit.SECONDS)
            Log.i(TAG, "[MULTI_DONE] codes=${codes.size} ocr=${ocrText.length}")
            runCatching {
                Diag.event(
                    "multi_recognized",
                    mapOf(
                        "img" to "${bitmap.width}x${bitmap.height}",
                        "codes" to codes.size,
                        "code_list" to codes.joinToString(" | ").take(280),
                    )
                )
            }
            onResult(codes, ocrText)
        }
    }

    /** 在后台等待 C++ 通道并合并，避免阻塞主线程，再只跑一次 OCR。 */
    private fun finishWithBarcodes(
        input: InputImage,
        mlBarcodes: List<String>,
        zxingFuture: java.util.concurrent.Future<List<String>>,
        lookup69: ((String) -> String?)?,
        onResult: (LabelResult) -> Unit,
        onError: (String) -> Unit,
        multiCode: Boolean = false,
    ) {
        recognitionPool.submit {
            val mergedBarcodes = try {
                // 并集要跑 1x/2x/3x 三遍，大图上比单次解码慢，给足 25 秒（多码清单同款 20 秒+余量）；
                // 单次解码保持 15 秒，超时不拖界面。
                (mlBarcodes + zxingFuture.get(if (multiCode) 25 else 15, TimeUnit.SECONDS))
                    .map { it.trim() }
                    .filter(String::isNotBlank)
                    .distinct()
            } catch (e: Exception) {
                zxingFuture.cancel(true)
                Log.w(TAG, "[ZXING_STATIC] timeout/failure; use ML Kit only: ${e.message}")
                mlBarcodes.distinct()
            }
            Log.i(TAG, "[BARCODE_MERGE] ml=${mlBarcodes.size} merged=${mergedBarcodes.size}")
            // 上报识别实况：图片尺寸 + 解出的码 + OCR 长度。
            // 为什么需要：用户反馈"同一张图在设置里的测试识别能出结果，正式流程不行"，
            // 而两条路共用同一个识别函数 —— 差异只可能在"喂进来的图"。把尺寸和结果
            // 都上报后，这类问题不用再靠猜（实测：图缩到 35% 集成码就全解不出）。
            runCatching {
                Diag.event(
                    "static_recognized",
                    mapOf(
                        "img" to "${input.width}x${input.height}",
                        "codes" to mergedBarcodes.size,
                        "code_list" to mergedBarcodes.joinToString(" | ").take(280),
                    )
                )
            }
            // 拉丁（英文/数字）识别器：用户字段（物料编码/日期/序列号/69码）全是
            // 纯字母数字，不需要中文模型 —— 少跑一路，OCR 更快更准（2026-09-17 简化）。
            var ocrText = ""
            val latch = java.util.concurrent.CountDownLatch(1)
            getRecognizer().process(input)
                .addOnSuccessListener { t ->
                    t.text?.trim()?.takeIf { it.isNotEmpty() }?.let { ocrText = it }
                }
                .addOnFailureListener { e -> Log.w(TAG, "ocr channel failed", e) }
                .addOnCompleteListener { latch.countDown() }
            // 本方法已在线程池里执行，等待不会卡主线程
            latch.await(25, TimeUnit.SECONDS)
            Log.i(TAG, "[OCR_MERGE] latin => ${ocrText.length} chars")
            onResult(LabelParser.parse(mergedBarcodes, ocrText, lookup69))
        }
    }

    /**
     * 单据 OCR 专用：只跑 ML Kit 文字识别，返回全文（按行拼，\n 分隔）。
     * 单据（进仓单）没有条码要解，不走 zxing，省时间；调用方在后台线程等
     * 回调，onResult/onError 都在 recognitionPool 线程上跑，UI 操作自行切主线程。
     */
    fun recognizeText(
        bitmap: Bitmap,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (bitmap.width < 10 || bitmap.height < 10) {
            onError("图片尺寸过小")
            return
        }
        val input = InputImage.fromBitmap(bitmap, 0)
        recognitionPool.submit {
            var ocrText = ""
            val latch = java.util.concurrent.CountDownLatch(1)
            getRecognizer().process(input)
                .addOnSuccessListener { t ->
                    t.text?.trim()?.takeIf { it.isNotEmpty() }?.let { ocrText = it }
                }
                .addOnFailureListener { e -> Log.w(TAG, "[SLIP_OCR] failed", e) }
                .addOnCompleteListener { latch.countDown() }
            latch.await(25, TimeUnit.SECONDS)
            Log.i(TAG, "[SLIP_OCR] done chars=${ocrText.length}")
            if (ocrText.isNotEmpty()) onResult(ocrText) else onError("单据上没有读到文字")
        }
    }

    /** 缩放解码：目标边 ≤ 4096（密集小条码保真）。FileDescriptor 方式（content URI 最可靠） */
    internal fun decodeSampledBitmap(resolver: ContentResolver, uri: Uri): Bitmap? {
        // 方式一：FileDescriptor（bounds 和解码各开一次 fd）
        try {
            var sample = 1
            resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, bounds)
                if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                    sample = ImageDecodePolicy.sampleSize(bounds.outWidth, bounds.outHeight)
                }
            }
            if (sample > 0) {
                resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                    BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, opts)
                        ?.let { return it }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "decodeFileDescriptor failed, fallback to stream", e)
        }

        // 方式二：流解码（fallback）
        return try {
            resolver.openInputStream(uri)?.use { ins ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(ins, null, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

                val sample = ImageDecodePolicy.sampleSize(bounds.outWidth, bounds.outHeight)

                resolver.openInputStream(uri)?.use { ins2 ->
                    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                    BitmapFactory.decodeStream(ins2, null, opts)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "stream decode failed", e)
            null
        }
    }

    /** 关闭 OCR 识别器（置空，下次识别自动重建） */
    fun close() {
        try { recognizer?.close() } catch (_: Exception) {}
        recognizer = null
    }
}
