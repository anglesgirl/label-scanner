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
import com.anglesgirl.labelscanner.camera.ZxingDecoder
import com.google.mlkit.vision.barcode.common.Barcode
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
                    // 提高分析帧分辨率：默认约 640x480，高密度 2D 码（集成码那种
                    // DataMatrix）在小帧里只占几十像素，再强的解码器也解不出。
                    // 入库那条路用的是全分辨率照片，这才是它能读出集成码的根本原因。
                    .setResolutionSelector(
                        androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                androidx.camera.core.resolutionselector.ResolutionStrategy(
                                    android.util.Size(1920, 1080),
                                    androidx.camera.core.resolutionselector.ResolutionStrategy
                                        .FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                )
                            )
                            .build()
                    )
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
        if (codes.isEmpty() || paused.get()) return
        val ordered = codes.sortedWith(
            compareByDescending<TypedCode> { it.value.contains(',') || it.value.contains('，') }
                .thenByDescending { it.is2D }
                .thenByDescending { it.value.length }
        )
        // 自动填是主力（用户明确要求省力）—— 扫到集成码就直接带回上层填箱，
        // 不再多一次"全部使用"确认点击；填错了由拆分页的候选区修正。
        val hasIntegrated = ordered.any { it.value.contains(',') || it.value.contains('\uFF0C') }
        if (hasIntegrated) {
            singleOnlyStreak = 0
            if (!paused.compareAndSet(false, true)) return
            runOnUiThread {
                beep()
                setResult(RESULT_OK, Intent()
                    .putStringArrayListExtra(EXTRA_RESULT_CODES, ArrayList(ordered.map { it.value })))
                finish()
            }
            return
        }

        // 只有单条码：**先忍几帧**再考虑弹框。
        // zxing 每 6 帧才跑一次，第一帧必然只有 ML Kit 的结果；若立刻弹框，
        // 就会在集成码还没机会出现时反复打断用户（实测症状：一直提示、只有单个条码）。
        if (++singleOnlyStreak < singleOnlyTolerance) return

        if (!paused.compareAndSet(false, true)) return
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
                .setNegativeButton("重新扫") { _, _ -> singleOnlyStreak = 0; paused.set(false) }
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

    /**
     * 连续多少帧只看到单条码（没看到集成码）。
     * zxing 强通道每 6 帧才跑一次，若不等待就会在第一帧误判「没有集成码」
     * 而反复弹框 —— 用户实测到的「一直提示、实际只有单个条码」正是此因。
     */
    private var singleOnlyStreak = 0

    /** 容忍帧数：约 0.2~0.3 秒，足够 zxing 跑一到两轮。 */
    private val singleOnlyTolerance = 10

    /** zxing 补出的码（跨帧暂存，取用时清空）。 */
    private val zxingExtra = mutableListOf<String>()

    /** 本页是否是来扫集成码的。 */
    private var wantIntegrated = false

    /**
     * 集成码专用分析：只用 zxing-cpp 强通道。
     *
     * 为什么不带 ML Kit（用户明确要求"困难模式不让 ml 参与"）:
     *  - ML Kit 解不出高密度 2D 码，本场景它没有正面价值；
     *  - 它每帧都会解出标签上的 1D 条码，导致"只有单个条码"的误报反复打断用户。
     * 少一条只会添乱的通道，既去掉噪音，也省掉"等/容忍若干帧"的补丁。
     *
     * zxing 比 ML Kit 慢，故每 2 帧抽一次并在独立线程池跑，避免拖住预览。
     */
    /**
     * 实时分析：**条码 / 二维码 / 集成码一律交给 zxing-cpp**。
     *
     * 为何不用 ML Kit 扫码（用户定调）：ML Kit 扫码本就弱 —— 高密度 2D 码
     * （集成码那种 DataMatrix）解不出，却每帧都能解出旁边的 1D 条码，造成
     * "只有单个条码"的误报反复打断用户。分工应为：zxing-cpp → 所有条码类；
     * ML Kit → 只做 OCR（本页无 OCR，故不引入）。
     *
     * zxing 比 ML Kit 慢，按模式抽样：集成码困难模式每 2 帧、普通模式每 3 帧，
     * 在独立线程池异步解码，不拖住预览。
     */
    private fun analyzeFrame(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null || paused.get()) {
            imageProxy.close()
            return
        }
        // 帧抽样：困难模式（密集 2D 码）走 3x 强通道、单次耗时长 → 每 2 帧；
        // 普通模式（单码字段：托盘号/物料/日期/型号）用**原始分辨率**解码，很快 →
        // **每帧都跑**（原为每 3 帧），用户一举起来就能出结果。
        val interval = if (wantIntegrated) 2 else 1
        if (zxingFrameCounter++ % interval != 0 || !zxingBusy.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }
        // 必须先取到 bitmap 再 close，否则拿不到像素
        val bmp = runCatching { imageProxy.toBitmap() }.getOrNull()
        imageProxy.close()
        if (bmp == null) {
            zxingBusy.set(false)
            return
        }
        zxingPool.execute {
            try {
                // 放大倍数按模式区分（见 ZxingDecoder.decode 的注释）：
                // 普通模式用 1x —— 分析帧已是 1920×1080，一维条码像素充足；放大 3x
                // 到 5760×3240 会让单次解码 1~3 秒（实测"要举很久"就是这个原因），
                // 而且托盘标签的塑料膜反光会被一起放大、反而更难解。
                // 困难模式仍用 3x 强通道啃密集小码。
                val codes = ZxingDecoder.decode(bmp, if (wantIntegrated) 3f else 1f)
                if (codes.isNotEmpty()) {
                    if (Diag.enabled) {
                        Diag.event("scan_zxing", mapOf(
                            "mode" to if (wantIntegrated) "integrated" else "plain",
                            "found" to codes.size,
                            "all" to codes.joinToString(" | ").take(260),
                        ))
                    }
                    val typed = codes.map {
                        val integ = it.contains(',') || it.contains('\uFF0C')
                        TypedCode(it, integ, 0)
                    }.distinctBy { it.value }
                    runOnUiThread { onCodesCollected(typed) }
                }
            } catch (t: Throwable) {
                android.util.Log.w("LiveScan", "zxing 解码失败", t)
            } finally {
                zxingBusy.set(false)
            }
        }
    }

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
        super.onDestroy()
    }
}
