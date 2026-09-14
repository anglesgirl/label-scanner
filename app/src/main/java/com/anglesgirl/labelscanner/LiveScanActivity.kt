package com.anglesgirl.labelscanner

import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Button
import android.graphics.Bitmap
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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
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
        private const val TAG = "LiveScan"
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

        /**
         * 目标字段语义（"tray" / "material" / "date" / "model" / ""）。
         *
         * 为什么需要：补扫按钮原先只传 `EXTRA_TITLE`（"托盘号"这种**显示文字**），
         * 扫码页并不知道你要的是哪类值，于是排序只用"像不像集成码"（集成码 > 2D > 长值），
         * 跟托盘号毫无关系；而且单码场景还要弹框让用户点"全部使用" ——
         * 用户的感受是「大多数时候不需要这个按钮，但需要的时候又很麻烦」。
         *
         * ⚠️ 注意：这只影响**排序**，**不会丢弃任何码** —— 同帧全部码照样带回上层
         * （采集层不取舍是硬规定，丢数据比取错更贵），其余候选仍进宿主的候选区。
         */
        const val EXTRA_WANT_FIELD = "extra_want_field"
        const val EXTRA_EXPECTED_COUNT = "extra_expected_count"
        const val EXTRA_INITIAL_CODES = "extra_initial_codes"
    }

    private lateinit var previewView: PreviewView

    /**
     * 本次扫码的目标字段（"tray"/"material"/"date"/"model"），空 = 通用模式。
     * 只影响候选排序与"单候选是否直接返回"，**不影响收码的完整性**。
     */
    private var wantField: String = ""

    /**
     * OCR 兜底通道（用户原则：**有条码优先条码，没条码才用 OCR**）。
     *
     * 为什么需要：不是所有字段都在标签上有条码 —— 用户明确指出的「型号」就"不可能有条码，
     * 直接就是 OCR 识别"。托盘号/物料/日期虽有码，但码可能磨损或被塑料膜反光糊掉，
     * 这时标签上的印字（如 TP36217944）就是唯一的读取途径。
     *
     * 代价控制：**OCR 只在连续多帧没解出任何条码之后才跑**，有条码时一次都不跑 ——
     * 既保住"条码优先"的准确率，也不让 OCR 拖慢正常扫码（OCR 单次约百毫秒级）。
     */
    private val ocrRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }
    private val ocrBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    /** 连续无条码的 zxing 周期数，达到阈值才启用 OCR。 */
    private var noCodeStreak = 0
    /** 无条码多少轮后启用 OCR：型号（已知无码）更快，其余多等等以免打断正常扫条码。 */
    private fun ocrAfterRounds(): Int = if (wantField == "model") 3 else 8

    /** 弹框期间暂停分析，避免重复弹窗 */
    private val paused = AtomicBoolean(false)
    private var lastRejected = ""
    private var lastRejectedAt = 0L

    private var bulkMode = false
    /** 挑码模式：列出扫到的码供用户点选。 */
    private var pickMode = false

    // 挑码模式：预览浮层 + 底部「已选/填入」栏
    private lateinit var pickOverlay: BarcodePickOverlay
    private lateinit var ivSnapshot: android.widget.ImageView
    private lateinit var llPickBar: android.view.View
    private lateinit var tvPickInfo: TextView
    private lateinit var btnPickDone: Button
    private lateinit var btnPickClear: Button
    private lateinit var btnRescan: Button
    /** 已选内容，按点选顺序。字段补扫（wantField 非空）单选，批量场景多选。 */
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
        // 目标字段语义（补扫时传入）：只用于给候选排序，不做筛选、不丢数据。
        wantField = intent.getStringExtra(EXTRA_WANT_FIELD).orEmpty()
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
            btnPickDone = findViewById(R.id.btnPickDone)
            btnPickClear = findViewById(R.id.btnPickClear)
            btnRescan = findViewById(R.id.btnRescan)

            // 微信扫码式：识别到内容后画面定格、每个内容画框标记，
            // 点框即选中/取消 —— **只把点选的填进去，绝不一把全填**。
            // 字段补扫（wantField 非空）填的是单个框 → 单选（点新替旧）；
            // SN 批量等场景 → 多选（点一下选上、再点取消）。
            pickOverlay.onPick = { code -> togglePick(code) }

            btnPickDone.setOnClickListener { confirmPicks() }
            btnPickClear.setOnClickListener {
                pickedBoxes.clear()
                pickOverlay.setPicked(pickedBoxes)
                updatePickBar()
            }
            btnRescan.setOnClickListener { resumeLiveScan() }

            // 主按钮文案按场景：
            //  - 扫集成码（拆码页挑码）→ 「✓ 一起拆」
            //  - 字段补扫 → 「填入所选」
            //  - SN 批量补扫 → 「✓ 加入序列号」
            btnPickDone.text = when {
                wantIntegrated -> "✓ 一起拆"
                wantField.isNotEmpty() -> "填入所选"
                else -> "✓ 加入序列号"
            }
            llPickBar.visibility = android.view.View.VISIBLE
            updatePickBar()
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

    /**
     * OCR 兜底：把画面里的**印字**识别出来作为候选值。
     *
     * 触发条件见 `analyzeFrame`：连续多轮没解出任何条码才跑（型号字段阈值更短，
     * 因为它天然没有条码）。有条码时一次都不跑 —— 保住条码的准确率，也不拖慢正常扫码。
     *
     * ⚠️ 仍遵循"采集层不取舍"：OCR 到的**每一行都带回上层**（按目标字段评分排序，
     * 最像的排最前），不是只留一个 —— 其余照样进宿主的候选区供人工修正。
     * 典型用途：托盘码被塑料膜反光糊掉时，读标签上印的 TP36217944。
     */
    private fun runOcrFallback(bmp: Bitmap) {
        if (!ocrBusy.compareAndSet(false, true)) return
        ocrRecognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { text ->
                ocrBusy.set(false)
                if (paused.get()) return@addOnSuccessListener

                // 补扫定格点选：OCR 文字也要能框出来点选（型号等无码字段靠它），
                // 每个识别行 = 一个画面可点区域，只标该区域里最像目标字段的值。
                if (pickMode) {
                    val picks = buildOcrPicks(text)
                    if (picks.isNotEmpty()) runOnUiThread { freezeAndShow(bmp, picks) }
                    return@addOnSuccessListener
                }

                // 非 pickMode（拆码页 OCR 兜底）：逐行取，整行 + 拆段都是候选。
                // 但 OCR 会把同一行里并排的两个字段合成一行（如 `TP36217944   2025-09-17`），
                // 整行打分必然落空 —— 所以除了整行，还把按空白拆出的各段也一并作为候选：
                // 拆出的段更能命中目标字段的格式（托盘号 TP+8 位数字），整行则留给
                // "字段本身含空格"的情况（如日期 `2026 6 22`）。只增候选、不删数据，
                // 排序仍由 fieldScore 决定，不匹配的段自然排到最后。
                val lines = text.textBlocks
                    .flatMap { it.lines }
                    .flatMap { line ->
                        val t = line.text.trim()
                        if (t.isEmpty()) emptyList()
                        else listOf(t) + t.split(Regex("\\s+")).filter { it.isNotEmpty() }
                    }
                    .map { it.trim() }
                    .filter { it.length in 4..40 }
                    .distinct()
                if (lines.isEmpty()) return@addOnSuccessListener
                val scored = if (wantField.isNotEmpty()) {
                    lines.sortedByDescending { fieldScore(it, wantField) }
                } else {
                    lines.sortedByDescending { it.length }
                }
                if (Diag.enabled) {
                    Diag.event(
                        "scan_ocr",
                        mapOf(
                            "field" to wantField.ifEmpty { "-" },
                            "lines" to lines.size,
                            "top" to scored.take(3).joinToString(" | ").take(160),
                        ),
                    )
                }
                // OCR 已经是"等不到条码才退而求其次"的结果，不必再让上层等单码轮次
                singleOnlyStreak = singleOnlyTolerance
                val typed = scored.map { TypedCode(it, false, 0) }
                runOnUiThread { onCodesCollected(typed) }
            }
            .addOnFailureListener { e ->
                ocrBusy.set(false)
                android.util.Log.w(TAG, "ocr fallback failed: ${e.message}")
            }
    }

    /**
     * OCR 行 → (最优候选, 整行位置框)。
     *
     * 每个 Text.Line 就是一个"画面可点区域"：整行与按空白拆出的各段都是候选
     * （一行并排两字段被 OCR 合成一行时，拆段才有正确答案），但一个框只能标一个
     * 值 —— 取 fieldScore 最高的段（字段补扫）或最长的段（批量场景）作为该框
     * 的可点项，其余丢弃（值都在同一画面区域里，点框即得最优值）。
     */
    private fun buildOcrPicks(text: com.google.mlkit.vision.text.Text): List<Pair<String, android.graphics.Rect>> {
        val out = mutableListOf<Pair<String, android.graphics.Rect>>()
        for (line in text.textBlocks.flatMap { it.lines }) {
            val t = line.text.trim()
            if (t.isEmpty()) continue
            val box = line.boundingBox ?: continue
            val cands = listOf(t) + t.split(Regex("\\s+")).filter { it.isNotEmpty() }
            val best = if (wantField.isNotEmpty()) {
                cands.maxByOrNull { fieldScore(it, wantField) } ?: t
            } else {
                cands.maxByOrNull { it.length } ?: t
            }
            if (best.length in 4..40) out.add(best to android.graphics.Rect(box))
        }
        return out.distinctBy { it.first }
    }

    /**
     * 候选值"有多像"目标字段 —— **只用于排序，不筛除任何值**。
     *
     * 规则一律取宽：宁可把不像的排在后面，也不要因为规则太窄而把正确答案压下去。
     * （"长度写死 12 位"、"SN 必须以物料编码开头"这两类过窄规则，都已被真实标签证伪 ——
     *  实测 SAP 号 10 位与 12 位都存在，而真实 SN 一律不以物料编码开头。）
     */
    private fun fieldScore(value: String, want: String): Int {
        val v = value.trim()
        if (v.isEmpty()) return -1000
        return when (want) {
            "tray" -> when {
                // 托盘号格式（用户明确给出）：**TP + 8 位数字**，实测样本 TP36217944
                v.matches(Regex("^TP\\d{8}$", RegexOption.IGNORE_CASE)) -> 200
                v.startsWith("TP", ignoreCase = true) -> 100
                // 箱号类（CA/PA 前缀）在采集里跟托盘号同源，一并靠前
                v.startsWith("CA", ignoreCase = true) || v.startsWith("PA", ignoreCase = true) -> 80
                v.length in 8..20 -> 20
                else -> 0
            }
            "material" -> when {
                // 物料（SAP）编码（用户明确给出）：**只有 10 位和 12 位**
                //   10 位 → 导出时后面补 01；12 位 → 不补
                // 不用 \d{10,12} —— 那会把 11 位也纳进来，是把规则写宽了
                v.matches(Regex("^\\d{10}$")) || v.matches(Regex("^\\d{12}$")) -> 100
                // 69 开头的 13 位是**商品条码（69 码）**，不是物料 —— 明确压低，
                // 避免"要物料时把 69 码填进去"（两者靠双向查关联，不是一回事）
                v.startsWith("69") && v.length == 13 -> -50
                // 混合码的前 10~12 位纯数字前缀即物料（如 201051012201V00224）
                v.matches(Regex("^\\d{10,12}[A-Za-z].*")) -> 60
                else -> 0
            }
            "date" -> when {
                v.matches(Regex("^\\d{8}$")) -> 100
                v.matches(Regex("^\\d{4}[-/.\\u5e74]\\d{1,2}[-/.\\u6708]\\d{1,2}\\u65e5?$")) -> 90
                else -> 0
            }
            "box" -> when {
                // 箱号：单台机器时**箱号就等于序列号**，粉盒（一箱多台）时箱号是另一套。
                // 两种形态都与托盘号同源（TP/CA/PA 前缀 + 数字），所以这里与 tray 一致，
                // 只是额外让"纯数字/字母数字混合的长串"（SN 形态）也靠前。
                v.matches(Regex("^TP\\d{8}$", RegexOption.IGNORE_CASE)) -> 200
                v.startsWith("TP", ignoreCase = true) -> 100
                v.startsWith("CA", ignoreCase = true) || v.startsWith("PA", ignoreCase = true) -> 90
                v.matches(Regex("^[A-Za-z0-9]{8,26}$")) && v.any { it.isDigit() } -> 70
                v.length in 8..26 -> 20
                else -> 0
            }
            "model" -> when {
                v.any { it.isLetter() } && v.matches(Regex("^[A-Za-z0-9\\-]{4,20}$")) -> 60
                else -> 0
            }
            else -> 0
        }
    }

    private fun onCodesCollected(codes: List<TypedCode>) {
        if (codes.isEmpty() || paused.get()) return
        val ordered = if (wantField.isNotEmpty()) {
            // 补扫模式：按目标字段的格式特征排序。
            // ⚠️ 只是**排序**：同帧所有码照样全部带回上层（采集层不取舍是硬规定）。
            codes.sortedWith(
                compareByDescending<TypedCode> { fieldScore(it.value, wantField) }
                    .thenByDescending { it.value.length }
            )
        } else {
            codes.sortedWith(
                compareByDescending<TypedCode> { it.value.contains(',') || it.value.contains('，') }
                    .thenByDescending { it.is2D }
                    .thenByDescending { it.value.length }
            )
        }
        // 补扫单个字段、且只有一个候选 → 直接带回，不弹确认框。
        // 用户点「扫」时已明确说了要哪个字段，画面里就一个码那就是答案 ——
        // 原来这里要多弹一次「全部使用」，是用户说的"需要的时候又很麻烦"的一部分。
        // 多候选的情况仍走下面的弹框（全量列出，由人决定），不替他选。
        if (wantField.isNotEmpty() && ordered.size == 1) {
            if (!paused.compareAndSet(false, true)) return
            runOnUiThread {
                beep()
                setResult(
                    RESULT_OK,
                    Intent().putStringArrayListExtra(
                        EXTRA_RESULT_CODES, ArrayList(ordered.map { it.value })
                    )
                )
                finish()
            }
            return
        }

        // 自动填是主力（用户明确要求省力）—— 扫到集成码就直接带回上层填箱，
        // 不再多一次"全部使用"确认点击；填错了由拆分页的候选区修正。
        // ⚠️ 仅限批量/通用场景（wantField 为空）：字段补扫要的是单一值，
        // 集成码（含逗号的整串多 SN）不该自动整个填进托盘/物料/日期/型号框，
        // 让它进单选列表由用户挑。
        val hasIntegrated = ordered.any { it.value.contains(',') || it.value.contains('\uFF0C') }
        if (hasIntegrated && wantField.isEmpty()) {
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
            // 多候选确认：**单选列表**，默认选中排序最靠前的（最像目标字段的），
            // 点「确定」只返回选中的那一个。
            //
            // 为什么改（用户反馈）：原来只有「全部使用 / 重新扫」，点"全部使用"就把
            // 所有候选（含 OCR 垃圾行、无关码）整个带回上层 —— 字段补扫时宿主
            // forEach 逐个 setText 到同一个框，后面的覆盖前面的，最终留下排序最差的
            // 那个；SN 批量时垃圾全进列表。现在能只取想要的那一个。
            val items = ordered.mapIndexed { i, c ->
                val tag = when {
                    c.value.contains(',') || c.value.contains('，') -> "集成码"
                    c.is2D -> "二维码"
                    else -> "条码"
                }
                "${i + 1}. [$tag] ${c.value.take(70)}"
            }.toTypedArray()
            var checked = 0
            val builder = AlertDialog.Builder(this)
                .setTitle("\uD83D\uDCE6 识别到 ${codes.size} 个候选，点选要用的")
                .setSingleChoiceItems(items, 0) { _, which -> checked = which }
                .setCancelable(false)
                .setPositiveButton("确定") { _, _ ->
                    setResult(RESULT_OK, Intent()
                        .putStringArrayListExtra(EXTRA_RESULT_CODES, arrayListOf(ordered[checked].value)))
                    finish()
                }
                .setNegativeButton("重新扫") { _, _ -> singleOnlyStreak = 0; paused.set(false) }
            // 字段补扫（wantField 非空）要的是单一值，只返回选中的那个，不提供
            // 「全部使用」—— 全填会把一个框覆盖成错值。批量场景（SN 补扫等）
            // 才保留「全部使用」，一次带回全部候选。
            if (wantField.isEmpty()) {
                builder.setNeutralButton("全部使用") { _, _ ->
                    setResult(RESULT_OK, Intent()
                        .putStringArrayListExtra(EXTRA_RESULT_CODES, ArrayList(ordered.map { it.value })))
                    finish()
                }
            }
            builder.show()
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
                // 补扫（pickMode）用**带位置**解码：定格后要把每个码框出来供点选，
                // 只有值没有位置框就没法标记。原始分辨率解 1D 码字段足够
                // （用户已确认普通模式用原始分辨率解码）。
                val found = if (pickMode) {
                    ZxingDecoder.decodeWithPositions(bmp)
                } else {
                    ZxingDecoder.decode(bmp, if (wantIntegrated) 3f else 1f).map { it to null }
                }
                if (found.isNotEmpty()) {
                    if (Diag.enabled) {
                        Diag.event("scan_zxing", mapOf(
                            "mode" to if (wantIntegrated) "integrated" else if (pickMode) "pick" else "plain",
                            "found" to found.size,
                            "all" to found.joinToString(" | ") { it.first }.take(260),
                        ))
                    }
                    if (pickMode) {
                        // 定格 + 画框标记，用户点选后再填（只填点选的，不一把全填）
                        runOnUiThread { freezeAndShow(bmp, found) }
                    } else {
                        val typed = found.map { (v, _) ->
                            val integ = v.contains(',') || v.contains('\uFF0C')
                            TypedCode(v, integ, 0)
                        }.distinctBy { it.value }
                        runOnUiThread { onCodesCollected(typed) }
                    }
                } else {
                    // ⭐ 无条码 → 累计轮数，达到阈值启用 OCR 兜底。
                    // 用户原则："有条码的优先识别条码；没有条码的，就用 OCR 补。"
                    // 型号字段天然没有条码（用户明确指出），所以它的阈值取得更短。
                    // 有条码时**一次都不跑 OCR** —— 既保住条码的准确率，也不拖慢正常扫码。
                    noCodeStreak += 1
                    if (noCodeStreak >= ocrAfterRounds()) {
                        noCodeStreak = 0
                        runOcrFallback(bmp)
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.w("LiveScan", "zxing 解码失败", t)
            } finally {
                zxingBusy.set(false)
            }
        }
    }

    /**
     * 定格 + 标记：把这一帧固定显示，识别到的内容画框，用户在这张静止画面上点选。
     *
     * 为什么必须定格：实时预览里码在移动，手指点下去时码已移开，必然点错
     * （布局里 ivSnapshot 的注释也是这个原因）。
     *
     * @param bmp 当前帧（zxing 分析帧 / OCR 用同一帧，坐标一致）
     * @param pickables (值, 图像坐标外接框) —— 条码来自 decodeWithPositions，
     *                  OCR 文字来自 ML Kit 的 boundingBox
     */
    private fun freezeAndShow(bmp: Bitmap, pickables: List<Pair<String, android.graphics.Rect?>>) {
        if (paused.get()) return
        if (!paused.compareAndSet(false, true)) return
        val items = pickables
            .mapNotNull { (v, r) -> r?.let { BarcodePickOverlay.Pickable(v, it) } }
            .distinctBy { it.value }
        if (items.isEmpty()) {
            // 只有值没有位置框（理论上不应发生）：不定格，继续实时扫
            paused.set(false)
            return
        }
        ivSnapshot.setImageBitmap(bmp)
        ivSnapshot.visibility = android.view.View.VISIBLE
        pickOverlay.setSourceSize(bmp.width, bmp.height)
        pickOverlay.setItems(items)
        pickOverlay.setPicked(pickedBoxes)
        beep()
        updatePickBar()
    }

    /** 点画面上某个框：选中/取消。字段补扫填单值框 → 单选（点新替旧）。 */
    private fun togglePick(code: String) {
        if (pickedBoxes.contains(code)) {
            pickedBoxes.remove(code)
        } else {
            if (wantField.isNotEmpty()) pickedBoxes.clear()   // 单选：换选即替换
            pickedBoxes.add(code)
        }
        beep()
        pickOverlay.setPicked(pickedBoxes)
        updatePickBar()
    }

    /** 只把用户点选的内容返回给调用方（绝不一把全填）。 */
    private fun confirmPicks() {
        if (pickedBoxes.isEmpty()) {
            Toast.makeText(this, "还没选任何内容：点画面上的框选择", Toast.LENGTH_SHORT).show()
            return
        }
        beep()
        setResult(
            RESULT_OK,
            Intent().putStringArrayListExtra(EXTRA_RESULT_CODES, ArrayList(pickedBoxes)),
        )
        finish()
    }

    private fun resumeLiveScan() {
        ivSnapshot.visibility = android.view.View.GONE
        ivSnapshot.setImageBitmap(null)
        pickOverlay.setItems(emptyList())
        pickedBoxes.clear()
        pickOverlay.setPicked(pickedBoxes)
        updatePickBar()
        paused.set(false)
    }

    /** 已选内容栏：字段补扫显示单值；批量/拆码显示数量与内容。 */
    private fun updatePickBar() {
        tvPickInfo.text = when {
            pickedBoxes.isEmpty() ->
                "识别到内容已定格：点画面上的框选择要填的，再点下方按钮"
            wantField.isNotEmpty() && pickedBoxes.size == 1 ->
                "已选：${pickedBoxes.first().take(24)}（点其他框可替换）"
            wantField.isNotEmpty() ->
                "已选 ${pickedBoxes.size} 项（字段补扫单选，请只留一个）"
            else ->
                "已选 ${pickedBoxes.size} 项：" + pickedBoxes.joinToString("、") { it.take(18) }
        }
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
                .setTitle("\uD83D\uDCE6 扫码结果")
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