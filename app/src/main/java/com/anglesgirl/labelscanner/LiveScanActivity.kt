package com.anglesgirl.labelscanner

import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.content.ContextCompat
import com.anglesgirl.labelscanner.camera.BarcodePickOverlay
import com.anglesgirl.labelscanner.util.Diag
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.anglesgirl.labelscanner.camera.ZxingDecoder
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 🔍 实时扫码页：输入框旁「扫」按钮弹出的相机。
 *
 * 无需拍照：预览画面自动识别条码 → 弹出「扫码结果确认」框
 *   - 确定 → 条码返回给调用界面（填入目标字段 / 加入 SN 列表）
 *   - 取消 → 继续扫描（同一码 1.5s 内不重弹，防误触）
 */
class LiveScanActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TITLE = "extra_title"          // 提示文字，如"扫描托盘号"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_CODES = "extra_result_codes"
        const val EXTRA_BULK_MODE = "extra_bulk_mode"

        /**
         * 挑码模式：扫到的码**列出**在屏幕上，用户点哪个就用哪个（微信扫码的手感）。
         * 与 bulkMode 的区别：bulkMode 是扫到即自动累加并返回，用户没机会挑选；
         * 集成码这类场景常有多个码同屏出现，必须让用户点选才能取对。
         */
        const val EXTRA_PICK_MODE = "extra_pick_mode"

        /** true = 本页是来扫集成码的：同帧多个码时优先取 2D / 含逗号的那个。 */
        const val EXTRA_WANT_INTEGRATED = "extra_want_integrated"
        const val EXTRA_EXPECTED_COUNT = "extra_expected_count"
        const val EXTRA_INITIAL_CODES = "extra_initial_codes"
    }

    private lateinit var previewView: PreviewView

    /** 弹框期间暂停分析，避免重复弹窗 */
    private val paused = AtomicBoolean(false)
    private var lastRejected = ""
    private var lastRejectedAt = 0L

    private var barcodeScanner: BarcodeScanner? = null
    private var bulkMode = false
    /** 挑码模式：列出扫到的码供用户点选。 */
    private var pickMode = false

    // 挑码模式：预览浮层 + 底部「已选/拆解」栏
    private lateinit var pickOverlay: BarcodePickOverlay
    private lateinit var ivSnapshot: android.widget.ImageView
    private lateinit var llPickBar: android.view.View
    private lateinit var tvPickInfo: TextView
    /** 已选集成码，按扫描顺序（下标 0 → 序号 1 → 第一箱） */
    private val pickedBoxes = mutableListOf<String>()
    /** 挑码模式下累计扫到的所有码（保持出现顺序、去重）。 */
    private val seenCodes = linkedSetOf<String>()
    private var expectedCount = 0
    private val initialCodes = linkedSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge：状态栏/导航栏不遮内容。
        // 注意扫描页是全屏相机，**不能给根布局整体加 padding**（会让取景画面缩水），
        // 只让顶栏与底栏各自避开系统栏。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_live_scan)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.scanTopBar)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, left = bars.left, right = bars.right)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.llPickBar)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(bottom = bars.bottom, left = bars.left, right = bars.right)
            insets
        }

        previewView = findViewById(R.id.pvScan)
        val tvHint = findViewById<TextView>(R.id.tvScanHint)
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        bulkMode = intent.getBooleanExtra(EXTRA_BULK_MODE, false)
        pickMode = intent.getBooleanExtra(EXTRA_PICK_MODE, false)
        // 本页是否为扫集成码：识别到多个码时优先取 2D / 含逗号的那个
        wantIntegrated = intent.getBooleanExtra(EXTRA_WANT_INTEGRATED, false)
        expectedCount = intent.getIntExtra(EXTRA_EXPECTED_COUNT, 0)
        initialCodes += intent.getStringArrayListExtra(EXTRA_INITIAL_CODES).orEmpty()
        tvHint.text = if (title.isEmpty()) "对准条码，自动识别" else "对准${title}条码，自动识别"
        findViewById<Button>(R.id.btnCloseScan).setOnClickListener { finish() }

        if (pickMode) {
            pickOverlay = findViewById(R.id.pickOverlay)
            ivSnapshot = findViewById(R.id.ivSnapshot)
            llPickBar = findViewById(R.id.llPickBar)
            tvPickInfo = findViewById(R.id.tvPickInfo)
            // 点画面上箭头所指的码 → 记下，序号即"第几箱"
            pickOverlay.onPick = { code ->
                if (pickedBoxes.contains(code)) {
                    Toast.makeText(this, "这个码已经选过了", Toast.LENGTH_SHORT).show()
                } else {
                    pickedBoxes.add(code)      // 下标 0 → 序号 1 → 第一箱
                    beep()
                    updatePickBar()
                }
                resumeLiveScan()
            }
            findViewById<Button>(R.id.btnPickDone).setOnClickListener {
                if (pickedBoxes.isEmpty()) {
                    Toast.makeText(this, "还没选任何集成码", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                // 一起拆：把选中顺序（=第几箱）连同码值交给调用方
                setResult(
                    RESULT_OK,
                    Intent().putStringArrayListExtra(EXTRA_RESULT_CODES, ArrayList(pickedBoxes)),
                )
                finish()
            }
            findViewById<Button>(R.id.btnPickClear).setOnClickListener {
                pickedBoxes.clear()
                pickOverlay.setPicked(pickedBoxes)
                updatePickBar()
            }
            llPickBar.visibility = android.view.View.VISIBLE
        }

        barcodeScanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
                .build()
        )
        startCamera()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(ContextCompat.getMainExecutor(this)) { imageProxy ->
                    analyzeFrame(imageProxy)
                }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                Toast.makeText(this, "相机启动失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }



    /** 同一帧里识别到的码，带类型标注（采集场景要全量收下，不能只留一个）。 */
    data class TypedCode(val value: String, val is2D: Boolean, val format: Int)

    /**
     * 全量收码：把这一帧识别到的所有码都交给用户确认，而不是挑一个丢掉其余。
     * 集成码（含逗号 / 2D）排前面，方便一眼看到真正要的那个。
     */
    /**
     * ML Kit 格式码是否为二维码。
     * 集成码（逗号分隔的多 SN）在标签上通常是 2D 码，据此把它排在候选前面，
     * 但**只是排序、不是筛选** —— 同帧所有码照样全部带回上层。
     */
    private fun is2D(format: Int): Boolean = format == Barcode.FORMAT_QR_CODE ||
        format == Barcode.FORMAT_DATA_MATRIX ||
        format == Barcode.FORMAT_AZTEC ||
        format == Barcode.FORMAT_PDF417

    private fun onCodesCollected(codes: List<TypedCode>) {
        if (codes.isEmpty() || !paused.compareAndSet(false, true)) return
        val ordered = codes.sortedWith(
            compareByDescending<TypedCode> { it.value.contains(',') || it.value.contains('，') }
                .thenByDescending { it.is2D }
                .thenByDescending { it.value.length }
        )
        // 自动填是主力（用户明确要求省力）—— 扫到集成码就直接带回上层填箱，
        // 不再多一次"全部使用"确认点击；填错了由拆分页的候选区修正。
        val hasIntegrated = ordered.any { it.value.contains(',') || it.value.contains('\uFF0C') }
        if (hasIntegrated) {
            runOnUiThread {
                beep()
                setResult(RESULT_OK, Intent()
                    .putStringArrayListExtra(EXTRA_RESULT_CODES, ArrayList(ordered.map { it.value })))
                finish()
            }
            return
        }

        // 只有单条码时仍弹框：这类多半是没对准（集成码没进画面），
        // 直接返回会让用户以为扫到了，反而制造错误数据。
        runOnUiThread {
            beep()
            val lines = ordered.mapIndexed { i, c ->
                val tag = when {
                    c.value.contains(',') || c.value.contains('，') -> "集成码"
                    c.is2D -> "二维码"
                    else -> "条码"
                }
                "${i + 1}. [$tag] ${c.value.take(70)}"
            }.joinToString("\n")
            AlertDialog.Builder(this)
                .setTitle("\uD83D\uDCE6 共识别到 ${codes.size} 个码")
                .setMessage("全部收下（不丢数据）：\n\n$lines")
                .setCancelable(false)
                .setPositiveButton("全部使用") { _, _ ->
                    setResult(RESULT_OK, Intent()
                        .putStringArrayListExtra(EXTRA_RESULT_CODES, ArrayList(ordered.map { it.value })))
                    finish()
                }
                .setNegativeButton("重新扫") { _, _ -> paused.set(false) }
                .show()
        }
    }

    /**
     * zxing-cpp 强通道：ML Kit 对高密度 2D 码（集成码常见 DataMatrix）经常解不出，
     * 而旧版"入库"那条路（CaptureActivity→StaticRecognizer）是 ML Kit + zxing 双通道，
     * 所以它能读出集成码、实时扫码读不出。这里给实时路径补上同一条强通道。
     *
     * 每 N 帧抽一次（zxing 比 ML Kit 慢，不能每帧跑），在独立线程池异步执行，
     * 结果并入 ML Kit 的候选里交给上层，不阻塞预览。
     */
    private val zxingPool = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "live-zxing").apply { isDaemon = true }
    }
    private val zxingBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    private var zxingFrameCounter = 0

    /** zxing 补出的码（跨帧暂存，取用时清空）。 */
    private val zxingExtra = mutableListOf<String>()

    /** 本页是否是来扫集成码的。 */
    private var wantIntegrated = false

    private fun analyzeFrame(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null || paused.get()) {
            imageProxy.close()
            return
        }
        val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        barcodeScanner?.process(inputImage)
            ?.addOnSuccessListener { barcodes ->
                // 抽样跑 zxing 强通道：ML Kit 解不出高密度 2D 码，需要它兜底。
                // 必须在 imageProxy.close() 之前取 bitmap，所以在这里同步取、
                // 送到线程池异步解码，结果经 zxingExtra 并入本轮候选。
                if (zxingFrameCounter++ % 6 == 0 && zxingBusy.compareAndSet(false, true)) {
                    runCatching {
                        val bmp = imageProxy.toBitmap()
                        zxingPool.execute {
                            try {
                                val zx = ZxingDecoder.decode(bmp)
                                if (zx.isNotEmpty()) zxingExtra.addAll(zx)
                            } catch (t: Throwable) {
                                android.util.Log.w("LiveScan", "zxing 实时解码失败", t)
                            } finally {
                                zxingBusy.set(false)
                            }
                        }
                    }.onFailure { zxingBusy.set(false) }
                }
                if (pickMode) {
                    // 挑码模式：识别到码就**截屏定格**，在静止画面上标出码的位置让用户点选。
                    // 不能实时叠加 —— 画面里码在移动，手指按下去时码已经移开，必然点错。
                    val picks = barcodes.mapNotNull { b ->
                        val v = b.rawValue?.trim()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                        val bb = b.boundingBox ?: return@mapNotNull null
                        BarcodePickOverlay.Pickable(v, bb)
                    }
                    if (picks.isEmpty()) return@addOnSuccessListener

                    // toBitmap() 返回的是**传感器方向**的原始图（竖屏拍摄时通常是横的），
                    // 必须按 rotationDegrees 转正：否则既是显示方向不对，
                    // 又会让 boundingBox 的坐标系与显示图错位（点击位置全偏）。
                    val raw = runCatching { imageProxy.toBitmap() }.getOrNull()
                    val deg = imageProxy.imageInfo.rotationDegrees
                    val snapshot = if (raw != null && deg != 0) {
                        runCatching {
                            val m = android.graphics.Matrix().apply { postRotate(deg.toFloat()) }
                            android.graphics.Bitmap.createBitmap(
                                raw, 0, 0, raw.width, raw.height, m, true,
                            )
                        }.getOrNull() ?: raw
                    } else {
                        raw
                    }
                    // 坐标基准改为"转正后"的图尺寸，与 boundingBox 所在坐标系一致
                    val fw = snapshot?.width ?: inputImage.width
                    val fh = snapshot?.height ?: inputImage.height
                    runOnUiThread {
                        if (snapshot != null) {
                            ivSnapshot.setImageBitmap(snapshot)
                            ivSnapshot.visibility = android.view.View.VISIBLE
                        }
                        pickOverlay.setSourceSize(fw, fh)
                        pickOverlay.setItems(picks)
                        pickOverlay.setPicked(pickedBoxes)
                        llPickBar.visibility = android.view.View.VISIBLE
                        updatePickBar()
                        // 定格：暂停实时分析，避免画面继续动导致点错
                        paused.set(true)
                    }
                    return@addOnSuccessListener
                }
                // 合并 ML Kit 与 zxing 两路结果（zxing 补高密度 2D 码）
                val drained = java.util.Collections.synchronizedList(zxingExtra).let {
                    synchronized(it) { val c = it.toList(); it.clear(); c }
                }
                val values = (barcodes.mapNotNull { it.rawValue?.trim()?.takeIf(String::isNotBlank) } + drained)
                    .distinct()
                if (bulkMode) {
                    onBarcodesDetected(values)
                } else if (wantIntegrated) {
                    // 采集场景的原则是「全量获得数据」，不是「谁先解出谁赢」。
                    // 标签上常同时有 1D 条码和 2D 集成码，以前只取最先解出的那个，
                    // 等于把其余码直接丢掉 —— 用户只能靠手遮住别的码才扫得到集成码。
                    // 现在：同帧所有码全部收下，按类型标注后一起回传。
                    if (values.isEmpty()) return@addOnSuccessListener
                    val typed = barcodes.mapNotNull { b ->
                        val v = b.rawValue?.trim()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                        TypedCode(v, is2D(b.format), b.format)
                    }.distinctBy { it.value }
                    Diag.event("scan_frame_all", mapOf(
                        "all" to typed.joinToString(" | ") { (if (it.is2D) "2D:" else "1D:") + it.value }.take(260),
                    ))
                    onCodesCollected(typed)
                } else {
                    // 采集层原则：全量获得数据，不做取舍（"先到先得"不属于这一层）。
                    // 谁先解出来、谁更短更长，都不是这里该判断的；上层拿到全部再决定。
                    val typed = barcodes.mapNotNull { b ->
                        val v = b.rawValue?.trim()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                        TypedCode(v, is2D(b.format), b.format)
                    }.distinctBy { it.value }
                    if (typed.isNotEmpty()) {
                        Diag.event("scan_frame_all", mapOf(
                            "all" to typed.joinToString(" | ") { (if (it.is2D) "2D:" else "1D:") + it.value }.take(260),
                            "mode" to "plain",
                        ))
                        onCodesCollected(typed)
                    }
                }
            }
            ?.addOnFailureListener { /* 单帧失败忽略，继续下一帧 */ }
            ?.addOnCompleteListener { imageProxy.close() }
    }

    /**
     * 选完一个码后恢复实时预览，继续扫下一个集成码（多箱：一箱一个码）。
     * 撤掉冻结截图与标记，重新开启分析。
     */
    private fun resumeLiveScan() {
        ivSnapshot.visibility = android.view.View.GONE
        ivSnapshot.setImageBitmap(null)
        pickOverlay.setItems(emptyList())
        pickOverlay.setPicked(pickedBoxes)
        updatePickBar()
        paused.set(false)
    }

    /** 已选的码（多箱拆：连续扫，一箱一条，最后一起拆）。 */
    private fun updatePickBar() {
        tvPickInfo.text = "已选 ${pickedBoxes.size} 个集成码" +
            if (pickedBoxes.isEmpty()) "" else "：" + pickedBoxes.joinToString("、") { it.take(18) }
    }


    /** SN 批量补扫：同帧返回全部条码，已有 SN 不重复加入。 */


    private fun onBarcodesDetected(values: List<String>) {
        val newCodes = values.filterNot(initialCodes::contains).distinct()
        if (newCodes.isEmpty() || !paused.compareAndSet(false, true)) return

        runOnUiThread {
            beep()
            val collectedCount = initialCodes.size + newCodes.size
            val countHint = if (expectedCount > 0) "\n已收集 $collectedCount/$expectedCount 个" else ""
            AlertDialog.Builder(this)
                .setTitle("📦 扫码结果")
                .setMessage("本次识别 ${newCodes.size} 个条码：\n${newCodes.joinToString("\n")}$countHint\n\n确认加入序列号吗？")
                .setCancelable(false)
                .setPositiveButton("确定") { _, _ ->
                    setResult(RESULT_OK, Intent().putStringArrayListExtra(EXTRA_RESULT_CODES, ArrayList(newCodes)))
                    finish()
                }
                .setNegativeButton("取消") { _, _ -> paused.set(false) }
                .setOnDismissListener { paused.set(false) }
                .show()
        }
    }

    /** 扫到条码提示音 + 振动 */
    private fun beep() {
        try {
            val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
            vibrator.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {
        }
        try {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        barcodeScanner?.close()
        super.onDestroy()
    }
}
