package com.anglesgirl.labelscanner

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.anglesgirl.labelscanner.camera.StaticRecognizer
import com.anglesgirl.labelscanner.data.Barcode69Lookup
import com.anglesgirl.labelscanner.data.RecordStore
import com.anglesgirl.labelscanner.model.LabelParser
import com.anglesgirl.labelscanner.model.LabelResult
import com.anglesgirl.labelscanner.util.AmbiguousChar
import com.anglesgirl.labelscanner.util.TrayPrefs
import java.io.File

/**
 * 📋 单条入库：一箱一码 / 单品标签；可多条 SN；散货 69 码远程反查物料。
 * 与单箱入库同风格：识别三入口 + 字段补扫 + 已识别条码点选用途。
 */
class SingleInboundActivity : AppCompatActivity() {

    private lateinit var etMaterial: EditText
    private lateinit var etTrayCode: EditText
    private lateinit var etDate: EditText
    private lateinit var etSn: EditText
    /**
     * 合并后：物料编码与 69 码共用同一个输入框。
     *
     * 用户要求"把他们两个合并，给个按钮切换显示物料编码、切换显示 69 码"——
     * 两者一一对应，并排占两个框没有意义；切到哪个视图，框里就显示哪个。
     */
    private var showingEan69 = false

    /** 最近识别/输入到的 69 码。切到物料视图时它不显示，但保存时仍要用。 */
    private var recognizedEan69 = ""

    /** 当前有效的 69 码值（69 视图下取框内容，否则取暂存值）。 */
    private val currentEan69: String
        get() = if (showingEan69) etMaterial.text.toString().trim() else recognizedEan69
    private lateinit var etModel: EditText
    private lateinit var etColor: EditText
    private lateinit var etToner: EditText
    private lateinit var llSnList: LinearLayout
    private lateinit var llCodeCandidates: LinearLayout
    private lateinit var tvStatus: TextView
    private lateinit var tvTrayCount: TextView

    private val snList = mutableListOf<String>()
    private val codeCandidates = mutableListOf<String>()
    /** 候选值的来源（条码 / OCR），仅用于界面标注。 */
    private val codeCandidateSources = mutableMapOf<String, String>()
    private val lookup69Lazy = lazy { Barcode69Lookup(this) }
    private fun lookup69(): Barcode69Lookup = lookup69Lazy.value

    private var scanTargetField: EditText? = null
    private var scanAppendToSn = false
    private var pendingPhotoUri: Uri? = null
    private var photoFile: File? = null

    /**
     * 取图入口：统一走 camera.ImageIn。
     *
     * 三种取图方式（拍照 / 文档扫描 / 相册）共用同一份实现与参数 ——
     * 用户在采集/拆分/测试各处看到过"同一个功能、参数却不一致"的问题。
     */
    // ⚠️ 必须在这里（Activity 构造阶段）就创建，**不能用 by lazy**：
    // registerForActivityResult 要求在当前状态仍为 CREATED 时注册，
    // 延迟到点击按钮时才初始化会抛异常，表现为"相机入口点不进去"。
    private val imageIn = com.anglesgirl.labelscanner.camera.ImageIn(this) { uri -> recognizeStatic(uri) }

    /** 字段补扫：实时扫码相机 → 确认框 → 填目标框（不拍照，自动识别） */
    private val liveScan = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            val codes = data?.getStringArrayListExtra(LiveScanActivity.EXTRA_RESULT_CODES)
                ?: data?.getStringExtra(LiveScanActivity.EXTRA_RESULT_CODE)?.let { arrayListOf(it) }
                ?: arrayListOf()
            codes.forEach(::onScannedCode)
        }
    }

    private fun onScannedCode(code: String) {
        if (scanAppendToSn) {
            if (code !in snList) {
                snList.add(code)
                rebuildSnList()
                tvStatus.text = "✅ 已加入序列号: $code"
            } else {
                tvStatus.text = "⚠️ 序列号已存在: $code"
            }
        } else {
            scanTargetField?.setText(code)
            tvStatus.text = "✅ 已填入: $code（可手动修改）"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
        setContentView(R.layout.activity_single_inbound)

        etMaterial = findViewById(R.id.etMaterial)
        etTrayCode = findViewById(R.id.etTrayCode)
        etDate = findViewById(R.id.etDate)
        etSn = findViewById(R.id.etSn)
        findViewById<Button>(R.id.btnToggle69).setOnClickListener { toggleMaterialEanView() }
        etModel = findViewById(R.id.etModel)
        etColor = findViewById(R.id.etColor)
        etToner = findViewById(R.id.etToner)
        llSnList = findViewById(R.id.llSnList)
        llCodeCandidates = findViewById(R.id.llCodeCandidates)
        tvStatus = findViewById(R.id.tvStatus)
        tvTrayCount = findViewById(R.id.tvTrayCount)
        updateTrayCount()

        findViewById<Button>(R.id.btnTakePhoto).setOnClickListener { imageIn.takePhoto() }
        findViewById<Button>(R.id.btnScanDoc).setOnClickListener { imageIn.scanDocument() }
        findViewById<Button>(R.id.btnPickGallery).setOnClickListener { imageIn.pickGallery() }
        findViewById<Button>(R.id.btnAddSn).setOnClickListener { addSnFromInput() }
        findViewById<Button>(R.id.btnScanAddSn).setOnClickListener {
            scanAppendToSn = true
            liveScan.launch(
                Intent(this, LiveScanActivity::class.java)
                    .putExtra(LiveScanActivity.EXTRA_TITLE, "序列号")
                    .putExtra(LiveScanActivity.EXTRA_BULK_MODE, true)
                    .putExtra(LiveScanActivity.EXTRA_EXPECTED_COUNT, snList.size)
                    .putStringArrayListExtra(LiveScanActivity.EXTRA_INITIAL_CODES, ArrayList(snList))
            )
        }
        findViewById<Button>(R.id.btnSave).setOnClickListener { confirmSave() }
        findViewById<Button>(R.id.btnReset).setOnClickListener { resetAll() }
        findViewById<Button>(R.id.btnLookup69).setOnClickListener { manualLookup69() }

        // 托盘号：采集开始时填一次，整批沿用（保存/清空都不重置，换托盘时手动改）
        etTrayCode.setText(TrayPrefs.get(this))
        etTrayCode.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                TrayPrefs.set(this@SingleInboundActivity, s?.toString()?.trim().orEmpty())
                updateTrayGate()
                updateTrayCount()
            }
        })

        // 托盘号门禁：未填托盘号前，禁用所有扫描/识别/保存按钮，强制先录托盘
        updateTrayGate()
        updateTrayCount()

        // 合并后只有这一个框：在「69 码」视图下输入满 13 位且 69 开头 → 自动反查物料
        etMaterial.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val v = s?.toString()?.trim().orEmpty()
                if (showingEan69 && v.length == 13 && v.startsWith("69")) {
                    recognizedEan69 = v
                    doLookup69(v, auto = true)
                }
            }
        })

        // 字段补扫按钮：弹实时扫码相机 → 确认框 → 填入（不拍照后识别）
        // 第三个元素是「目标字段语义」，传给扫码页用于**按该字段的格式给候选排序**
        // （托盘号 TP+8位数字 排前、物料 10~12 位数字排前、日期 8 位数字排前…）。
        // 改这个的起因：用户反馈"这些按钮大多数时候不需要，但需要的时候又很麻烦" ——
        // 原先只传显示文字（"托盘号"），扫码页不知道要哪类值，排序只认"像不像集成码"，
        // 而且单码还要多弹一次"全部使用"确认。现在：**按字段排序 + 单候选直接填**。
        val scanMap = mapOf(
            R.id.btnScanMaterial to Triple(etMaterial, "物料编码", "material"),
            R.id.btnScanTrayCode to Triple(etTrayCode, "托盘号", "tray"),
            R.id.btnScanDate to Triple(etDate, "生产日期", "date"),
            R.id.btnScanModel to Triple(etModel, "型号", "model"),
        )
        for ((btnId, triple) in scanMap) {
            val (field, label, wantField) = triple
            findViewById<Button>(btnId).setOnClickListener {
                scanAppendToSn = false
                scanTargetField = field
                liveScan.launch(
                    Intent(this, LiveScanActivity::class.java)
                        .putExtra(LiveScanActivity.EXTRA_TITLE, label)
                        .putExtra(LiveScanActivity.EXTRA_WANT_FIELD, wantField)
                )
            }
        }

        rebuildSnList()
        rebuildCodeCandidates()
        updateSaveButton()
    }

    private fun recognizeStatic(uri: Uri) {
        tvStatus.text = "识别中..."
        StaticRecognizer.recognizeUri(
            resolver = contentResolver, uri = uri, lookup69 = { ean -> lookup69().lookup(ean) },
            onResult = { result -> runOnUiThread { showResult(result) } },
            onError = { msg -> runOnUiThread { tvStatus.text = "识别失败：$msg" } }
        )
    }

    private fun showResult(result: LabelResult) {
        etMaterial.setText(result.materialCode)
        etDate.setText(result.productionDate)
        recognizedEan69 = result.ean69
        if (showingEan69 && result.ean69.isNotBlank()) etMaterial.setText(result.ean69)
        etModel.setText(result.model)
        etColor.setText(result.color)
        etToner.setText(result.tonerModel)

        snList.clear()
        if (result.serialNumber.isNotBlank()) snList.add(result.serialNumber)
        rebuildSnList()

        codeCandidates.clear()

        codeCandidateSources.clear()

        // 【关键修复】OCR 文本原来被完全丢弃：只把 result.barcodes 放进候选，

        // 导致界面上"只有条码可点、OCR 认到什么完全看不到"。

        // 这里把 OCR 文本按行拆开也作为候选，让用户能看见并点选。

        for (b in result.barcodes) {

            val v = b.trim()

            if (v.isNotEmpty() && v !in codeCandidates) {

                codeCandidates.add(v)

                codeCandidateSources[v] = "条码"

            }

        }

        result.ocrText.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->

            if (line !in codeCandidates) {

                codeCandidates.add(line)

                codeCandidateSources[line] = "OCR"

            }

        }
        rebuildCodeCandidates()

        var tips = ""
        tvStatus.setTextColor(if (result.materialFromEan69) cc(R.color.ls_err) else cc(R.color.ls_primary))
        if (result.materialFromEan69) tips = "🔴 商品码提供的补码：${result.materialCode}"
        if (result.materialCode.isBlank()) {
            tips = "⚠️ 未识别到物料"
            // 远程反查（Turso 库）
            if (result.ean69.isNotBlank()) {
                lookup69().lookupRemote(result.ean69) { material ->
                    runOnUiThread {
                        if (material != null && etMaterial.text.toString().isBlank()) {
                            etMaterial.setText(material)
                            tvStatus.text = "🔁 69码远程反查物料: $material（可修改）"
                        }
                    }
                }
            }
        } else if (result.productionDate == "19000101" && tips.isEmpty()) {
            tips = "⚠️ 未识别到生产日期"
        }
        tvStatus.text = "✅ 识别完成 ${result.barcodes.size} 个条码${if (tips.isEmpty()) "" else "，$tips"}"
        updateSaveButton()
    }

    private fun addSnFromInput() {
        val sn = etSn.text.toString().trim()
        if (sn.isEmpty()) { Toast.makeText(this, "先在序列号框输入内容", Toast.LENGTH_SHORT).show(); return }
        if (sn in snList) { Toast.makeText(this, "序列号已存在", Toast.LENGTH_SHORT).show(); return }
        snList.add(sn)
        rebuildSnList()
        etSn.setText("")
    }

    /**
     * 切换显示：物料编码 ⟷ 69 码。
     *
     * 两者一一对应，切换时顺带用对照表把另一侧补出来 —— 这就是"互补互查"：
     * 无论是 OCR 认到物料、还是扫码枪扫到 69 码，都能切过去看到对应的另一个值。
     */
    private fun toggleMaterialEanView() {
        val cur = etMaterial.text.toString().trim()
        showingEan69 = !showingEan69
        findViewById<Button>(R.id.btnToggle69).text = if (showingEan69) "物料" else "69"

        val other: String = if (showingEan69) {
            // 切到 69 视图：框里是 69 码就用它，否则用暂存的 69 码；
            // 都没有就用对照表按物料反查 69 码。
            val ean = if (cur.length == 13 && cur.startsWith("69")) cur else recognizedEan69
            if (ean.isNotBlank()) {
                recognizedEan69 = ean
                ean
            } else {
                lookup69().lookupByMaterial(cur)?.also { recognizedEan69 = it } ?: cur
            }
        } else {
            // 切回物料视图：按 69 码反查物料
            val ean = if (cur.length == 13 && cur.startsWith("69")) cur else recognizedEan69
            if (ean.isNotBlank()) {
                recognizedEan69 = ean
                lookup69().lookup(ean) ?: cur
            } else cur
        }
        etMaterial.setText(other)
        Toast.makeText(
            this,
            if (showingEan69) "已切换为 69 码" else "已切换为物料编码",
            Toast.LENGTH_SHORT,
        ).show()
    }

    /** 手动点「🔁 查」：无论物料是否已填都强制反查 */
    private fun manualLookup69() {
        val ean = currentEan69
        if (ean.isEmpty()) {
            Toast.makeText(this, "先输入/扫描 69 商品码", Toast.LENGTH_SHORT).show()
            return
        }
        doLookup69(ean, auto = false)
    }

    /**
     * 69 码反查物料：本地表 → 远程 Turso 库。
     * auto=true 时静默（自动触发），失败不弹提示；手动查会明确报告结果。
     */
    private fun doLookup69(ean: String, auto: Boolean) {
        // 1. 本地表命中直接填
        val local = lookup69().lookup(ean)
        if (local != null) {
            etMaterial.setText(local)
            tvStatus.text = "🔁 本地反查命中：$ean → $local"
            return
        }
        // 2. 未配置远程库
        val url = SettingsActivity.getUrl(this)
        val token = SettingsActivity.getToken(this)
        if (url.isEmpty() || token.isEmpty()) {
            if (!auto) {
                tvStatus.text = "⚠️ 未配置反查数据库，请到 ⚙️ 设置 填写连接地址和 Token"
                Toast.makeText(this, "请先在设置里配置反查数据库", Toast.LENGTH_LONG).show()
            }
            return
        }
        // 3. 远程查
        tvStatus.text = "🔁 反查中：$ean ..."
        lookup69().lookupRemote(ean) { material ->
            runOnUiThread {
                if (material != null) {
                    etMaterial.setText(material)
                    tvStatus.text = "✅ 反查成功：$ean → $material（已缓存本地）"
                } else {
                    tvStatus.text = "❌ 数据库中无此 69 码：$ean（可手动填物料，保存后自动学习）"
                    if (!auto) Toast.makeText(this, "库中无此 69 码", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun rebuildSnList() {
        llSnList.removeAllViews()
        for ((index, sn) in snList.withIndex()) {
            val row = LayoutInflater.from(this).inflate(R.layout.item_sn_row, llSnList, false)
            val tvSn = row.findViewById<TextView>(R.id.tvSnItem)
            // 易混淆字符（O/0、I/l/1）标红加粗：OCR 分不清这些形状，人眼同样分不清，
            // 标出来才能让人专注于核对这些位（用户明确要求）。
            // SN 也按来源判断：只有 OCR 途径得到的才可能认错字符、才需要标红核对
            val snFromOcr = codeCandidateSources[sn] == "OCR"
            tvSn.text = android.text.TextUtils.concat(
                "${index + 1}. ",
                if (snFromOcr) AmbiguousChar.highlight(sn) else sn,
            )
            // 点这一行就可修改 —— 人工修正是这类识别误差唯一可靠的闭环
            tvSn.setOnClickListener { editSn(index) }
            row.findViewById<Button>(R.id.btnDelSn).setOnClickListener {
                // 按下标删：原来按值删（remove(sn)），列表里有重复 SN 时会删错条目
                if (index in snList.indices) snList.removeAt(index)
                rebuildSnList()
            }
            llSnList.addView(row)
        }
        updateSaveButton()
    }

    /**
     * 人工修正某个序列号。
     *
     * OCR 对形状相同的字符（O/0、I/l/1）几乎无法分辨；没有条码这类权威来源时
     * 自动纠正不可靠（猜一个替代字符比不猜更糟），所以把判断交给用户：
     * 红色标注指出可疑位，点一下就能改。
     */
    private fun editSn(index: Int) {
        val old = snList.getOrNull(index) ?: return
        val input = EditText(this).apply {
            setText(old)
            setSelection(old.length)
            hint = AmbiguousChar.hint(old) ?: "修改序列号"
        }
        AlertDialog.Builder(this)
            .setTitle("修正序列号")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val nv = input.text.toString().trim()
                if (nv.isNotEmpty()) {
                    snList[index] = nv
                    rebuildSnList()
                    Toast.makeText(this, "已修正", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun rebuildCodeCandidates() {
        llCodeCandidates.removeAllViews()
        if (codeCandidates.isEmpty()) {
            llCodeCandidates.visibility = android.view.View.GONE
            return
        }
        llCodeCandidates.visibility = android.view.View.VISIBLE
        for (code in codeCandidates) {
            val row = LayoutInflater.from(this).inflate(R.layout.item_sn_row, llCodeCandidates, false)
            val tv = row.findViewById<TextView>(R.id.tvSnItem)
            // 标出这个候选值的来源（条码 / OCR）—— 否则用户无法判断哪个是扫码枪读出来的、
            // 哪个是 OCR 认出来的。两者可信度差别很大，必须一眼可分。
            // （片段颜色由 ForegroundColorSpan 决定，会盖过下面 setTextColor 的整行设色，
            //   所以来源前缀仍是主题色，只有可疑字符变红。）
            val src = codeCandidateSources[code]
            // 候选区这里不标红易混字符：这是原始识别明细，混着纯数字、中文等字段，
            // 用户明确说"只有序列号、箱号这种地方才需要标"。这里只标来源。
            tv.text = if (src == null) code
            else android.text.TextUtils.concat("[$src] ", code)
            // OCR 来源用弱化色，条码来源用主色 —— 视觉上进一步拉开差距
            tv.setTextColor(
                if (src == "OCR") cc(R.color.ls_neutral) else cc(R.color.ls_primary)
            )
            row.findViewById<Button>(R.id.btnDelSn).text = "选"
            row.findViewById<Button>(R.id.btnDelSn).setOnClickListener { showCodeActionDialog(code) }
            row.setOnClickListener { showCodeActionDialog(code) }
            llCodeCandidates.addView(row)
        }
    }

    private fun showCodeActionDialog(code: String) {
        AlertDialog.Builder(this)
            .setTitle("条码: $code")
            .setItems(arrayOf(
                "🏷️ 设为物料编码", "📦 设为托盘号", "📅 设为生产日期",
                "🔢 设为 69 商品码", "🏷️ 设为型号",
                "➕ 加入序列号列表", "❌ 取消"
            )) { _, which ->
                when (which) {
                    0 -> { etMaterial.setText(code); Toast.makeText(this, "物料已设为 $code", Toast.LENGTH_SHORT).show() }
                    1 -> { etTrayCode.setText(code); Toast.makeText(this, "托盘号已设为 $code", Toast.LENGTH_SHORT).show() }
                    2 -> { etDate.setText(code); Toast.makeText(this, "日期已设为 $code", Toast.LENGTH_SHORT).show() }
                    3 -> {
                        recognizedEan69 = code
                        // 能反查到物料就直接补上，省得用户再切一次视图
                        val m = lookup69().lookup(code)
                        if (m != null) etMaterial.setText(m)
                        else { showingEan69 = true; etMaterial.setText(code) }
                        Toast.makeText(this, "69 码已设为 $code", Toast.LENGTH_SHORT).show()
                    }
                    4 -> { etModel.setText(code); Toast.makeText(this, "型号已设为 $code", Toast.LENGTH_SHORT).show() }
                    5 -> {
                        if (code !in snList) { snList.add(code); rebuildSnList(); Toast.makeText(this, "已加入序列号", Toast.LENGTH_SHORT).show() }
                        else Toast.makeText(this, "序列号已存在", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun confirmSave() {
        if (etSn.text.toString().isNotBlank() && etSn.text.toString().trim() !in snList && snList.isNotEmpty()) {
            snList.add(etSn.text.toString().trim())
        }
        if (snList.isEmpty()) {
            val single = etSn.text.toString().trim()
            if (single.isEmpty()) { Toast.makeText(this, "序列号不能为空", Toast.LENGTH_SHORT).show(); return }
            snList.add(single)
        }
        val material = etMaterial.text.toString().trim()
        if (material.isEmpty()) { Toast.makeText(this, "物料编码为空（识别不到请扫码或手输）", Toast.LENGTH_SHORT).show(); return }

        val tray = etTrayCode.text.toString().trim()
        if (tray.isEmpty()) { Toast.makeText(this, "托盘号必填（扫描或输入托盘码）", Toast.LENGTH_SHORT).show(); return }
        val date = etDate.text.toString().trim()
        val ean = currentEan69
        val model = etModel.text.toString().trim()
        val color = etColor.text.toString().trim()
        val toner = etToner.text.toString().trim()

        val records = snList.map { sn ->
            LabelResult(
                materialCode = material, productionDate = date, serialNumber = sn,
                // 数量 = 本次序列号个数（与单箱页同一套规则）：
                // WMS 会校验"数量与 SN 是否一致"，不一致就直接拒绝导入。
                quantity = snList.size,
                ean69 = ean, model = model, color = color, tonerModel = toner,
                trayCode = tray, barcodes = codeCandidates.toList()
            )
        }
        val store = com.anglesgirl.labelscanner.data.RecordStore.load(this).toMutableList()
        store.addAll(records)
        com.anglesgirl.labelscanner.data.RecordStore.save(this, store)
        records.forEach { lookup69().learn(it.ean69, it.materialCode) }
        updateTrayCount()
        tvStatus.text = "✅ 已保存 ${records.size} 条（物料 $material，托盘 $tray）"
        Toast.makeText(this, "已保存 ${records.size} 条", Toast.LENGTH_SHORT).show()
        resetAll()
    }

    private fun resetAll() {
        // 托盘号保留（整批沿用），其余清空
        etMaterial.setText(""); etDate.setText("")
        etSn.setText(""); recognizedEan69 = ""; etModel.setText(""); etColor.setText(""); etToner.setText("")
        snList.clear(); codeCandidates.clear()
        rebuildSnList(); rebuildCodeCandidates()
        tvStatus.text = ""
    }

    private fun updateSaveButton() {
        findViewById<Button>(R.id.btnSave).text =
            if (snList.size <= 1) "✅ 保存入库（每 SN 一行）"
            else "✅ 保存入库（${snList.size} 条，共享字段）"
    }

    private fun updateTrayCount() {
        if (!::tvTrayCount.isInitialized || !::etTrayCode.isInitialized) return
        val tray = etTrayCode.text.toString().trim()
        if (tray.isEmpty()) {
            tvTrayCount.text = "当前托盘已保存：0 条箱号/SN"
            return
        }
        val count = RecordStore.loadByTrayCode(this, tray).size
        tvTrayCount.text = "当前托盘已保存：${count} 条箱号/SN"
    }

    /**
     * 托盘号门禁：托盘号为空前，禁用所有扫描/识别/保存动作，
     * 强制"先录托盘号 → 再扫描入库"的标准流程。供应商为选填，不受限。
     */
    private fun updateTrayGate() {
        val hasTray = etTrayCode.text.toString().trim().isNotEmpty()
        val locked = !hasTray
        val gated = listOf(
            R.id.btnTakePhoto, R.id.btnScanDoc, R.id.btnPickGallery,
            R.id.btnScanMaterial, R.id.btnToggle69,
            R.id.btnLookup69, R.id.btnScanDate, R.id.btnScanModel,
            R.id.btnScanAddSn, R.id.btnSave,
        )
        for (id in gated) findViewById<Button>(id).isEnabled = !locked
        tvStatus.text = if (locked) "⚠️ 请先填写托盘号（必填），才能扫描入库" else ""
    }

    /** 取主题色（跟随深浅模式）。 */
    private fun cc(resId: Int): Int = androidx.core.content.ContextCompat.getColor(this, resId)
}