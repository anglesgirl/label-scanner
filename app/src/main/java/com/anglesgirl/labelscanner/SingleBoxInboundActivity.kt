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
import com.anglesgirl.labelscanner.model.BoxParser
import com.anglesgirl.labelscanner.model.LabelResult
import com.anglesgirl.labelscanner.util.TrayPrefs
import java.io.File

/**
 * 📦 单箱入库：一个外箱（LPN）对应多个序列号。
 *
 * 流程：相册选标签图 → 自动识别（物料=SAP号 / LPN=CA开头 / 日期 / 型号 / 全部 SN）
 * → 人工确认/增删 SN → 保存（每 SN 展开为一条记录，共享物料/箱号/日期）。
 *
 * 规则见 BoxParser（2026-08-11 真实标签 DL-5120P 校准）。
 */
class SingleBoxInboundActivity : AppCompatActivity() {

    private lateinit var etMaterial: EditText
    private lateinit var etBox: EditText
    private lateinit var etDate: EditText
    private lateinit var etModel: EditText
    private lateinit var etManualSn: EditText
    private lateinit var etTrayCode: EditText
    private lateinit var llSnList: LinearLayout
    private lateinit var llCodeCandidates: LinearLayout
    private lateinit var tvBoxStatus: TextView

    private val snList = mutableListOf<String>()
    private val codeCandidates = mutableListOf<String>()
    /** 候选值的来源（条码 / OCR），仅用于界面标注。 */
    private val codeCandidateSources = mutableMapOf<String, String>()
    private val lookup69Lazy = lazy { Barcode69Lookup(this) }
    private fun lookup69(): Barcode69Lookup = lookup69Lazy.value

    /** 字段补扫目标字段引用 */
    private var scanTargetField: EditText? = null
    /** 补扫结果加入 SN 列表（而非填单个字段） */
    private var scanAppendToSn = false
    private var materialFromEan69 = false
    private var recognizedEan69 = ""

    /**
     * 物料输入框当前显示的是 69 码还是物料编码。
     * 两者一一对应，UI 上合并为一个字段 + 按钮切换，不再占两行。
     */
    private var showingEan69 = false
    /** 最近已知的 69 码（条码扫到的或由物料反查得到的）。 */
    private var lastKnownEan69 = ""
    /** 最近已知的物料编码。 */
    private var lastKnownMaterial = ""

    private var pendingPhotoUri: Uri? = null
    private var photoFile: File? = null

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
                tvBoxStatus.text = "✅ 已加入序列号: $code"
            } else {
                tvBoxStatus.text = "⚠️ 序列号已存在: $code"
            }
        } else {
            scanTargetField?.setText(code)
            tvBoxStatus.text = "✅ 已填入: $code（可手动修改）"
        }
    }

    /**
     * 取图入口：统一走 camera.ImageIn。
     *
     * 三种取图方式（拍照 / 文档扫描 / 相册）共用同一份实现与参数 ——
     * 之前单条采集、整箱采集、集成码拆分、设置测试各写了一份，参数容易走偏。
     */
    private val imageIn by lazy {
        com.anglesgirl.labelscanner.camera.ImageIn(this) { uri -> recognizeLabel(uri) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge：状态栏/导航栏不留黑色遮罩
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
        setContentView(R.layout.activity_single_box)

        etMaterial = findViewById(R.id.etMaterial)
        // 物料编码 ⟷ 69 码 一一对应：同一个输入框，按钮切换显示
        findViewById<Button>(R.id.btnToggle69).setOnClickListener { toggleMaterialEanView() }
        etBox = findViewById(R.id.etBox)
        etDate = findViewById(R.id.etDate)
        etModel = findViewById(R.id.etModel)
        etManualSn = findViewById(R.id.etManualSn)
        etTrayCode = findViewById(R.id.etTrayCode)
        llSnList = findViewById(R.id.llSnList)
        llCodeCandidates = findViewById(R.id.llCodeCandidates)
        tvBoxStatus = findViewById(R.id.tvBoxStatus)

        findViewById<Button>(R.id.btnTakePhoto).setOnClickListener { imageIn.takePhoto() }
        findViewById<Button>(R.id.btnScanDoc).setOnClickListener { imageIn.scanDocument() }
        findViewById<Button>(R.id.btnPickGallery).setOnClickListener { imageIn.pickGallery() }
        findViewById<Button>(R.id.btnAddSn).setOnClickListener { addManualSn() }
        findViewById<Button>(R.id.btnSaveBox).setOnClickListener { saveBox() }
        findViewById<Button>(R.id.btnResetBox).setOnClickListener { resetBox() }

        // 托盘号：采集开始时填一次，整批沿用（保存/清空都不重置，换托盘时手动改）
        etTrayCode.setText(TrayPrefs.get(this))
        etTrayCode.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                TrayPrefs.set(this@SingleBoxInboundActivity, s?.toString()?.trim().orEmpty())
            }
        })

        // 字段补扫按钮：弹实时扫码相机 → 确认框 → 填入（不拍照后识别）
        val scanMap = mapOf(
            R.id.btnScanTrayCode to (etTrayCode to "托盘号"),
            R.id.btnScanMaterial to (etMaterial to "物料编码"),
            R.id.btnScanBox to (etBox to "箱号"),
            R.id.btnScanDate to (etDate to "生产日期"),
            R.id.btnScanModel to (etModel to "型号"),
        )
        for ((btnId, pair) in scanMap) {
            val field = pair.first
            val label = pair.second
            findViewById<Button>(btnId).setOnClickListener {
                scanAppendToSn = false
                scanTargetField = field
                liveScan.launch(
                    Intent(this, LiveScanActivity::class.java)
                        .putExtra(LiveScanActivity.EXTRA_TITLE, label)
                )
            }
        }
        findViewById<Button>(R.id.btnScanSn).setOnClickListener {
            scanAppendToSn = true
            liveScan.launch(
                Intent(this, LiveScanActivity::class.java)
                    .putExtra(LiveScanActivity.EXTRA_TITLE, "序列号")
                    .putExtra(LiveScanActivity.EXTRA_BULK_MODE, true)
                    .putExtra(LiveScanActivity.EXTRA_EXPECTED_COUNT, snList.size)
                    .putStringArrayListExtra(LiveScanActivity.EXTRA_INITIAL_CODES, ArrayList(snList))
            )
        }

        rebuildSnList()
        rebuildCodeCandidates()
        updateStatus()
    }

    /** 系统相机拍照 → 全分辨率存 captures/ → 识别 */
    private fun recognizeLabel(uri: Uri) {
        tvBoxStatus.text = "识别中..."
        StaticRecognizer.recognizeUri(
            resolver = contentResolver,
            uri = uri,
            lookup69 = null,
            onResult = { result ->
                runOnUiThread {
                    val box = BoxParser.parse(
                        result.barcodes,
                        result.ocrText,
                        lookup69 = { ean -> lookup69().lookup(ean) },
                    )
                    if (!box.hasData) {
                        tvBoxStatus.text = "⚠️ 未识别到内容，请换图重试"
                        return@runOnUiThread
                    }
                    // 互补互查：OCR 读到的物料 ↔ 条码扫到的 69 码，哪边有就补另一边
                    crossFillMaterialEan(box.materialCode, box.ean69)
                    etMaterial.setText(
                        if (showingEan69 && lastKnownEan69.isNotBlank()) lastKnownEan69
                        else box.materialCode
                    )
                    recognizedEan69 = box.ean69
                    materialFromEan69 = box.materialFromEan69
                    etBox.setText(box.boxCode)
                    etDate.setText(box.productionDate)
                    etModel.setText(box.model)
                    snList.clear()
                    snList.addAll(box.serialNumbers)
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

                    val tips = mutableListOf<String>()
                    if (box.materialFromEan69) tips.add("🔴 商品码提供的补码：${box.materialCode}")
                    if (box.materialCode.isBlank()) tips.add("⚠️ 未识别到物料(SAP)，请手动输入")
                    if (box.boxCode.isBlank()) tips.add("⚠️ 未识别到箱号，请手动输入")
                    if (box.productionDate.isBlank()) tips.add("⚠️ 未识别到日期，请手动输入")
                    if (snList.isEmpty()) tips.add("⚠️ 未识别到序列号，请手动添加")
                    tvBoxStatus.setTextColor(if (box.materialFromEan69) cc(R.color.ls_err) else cc(R.color.ls_primary))
                    tvBoxStatus.text = "✅ 识别完成：物料=${box.materialCode.ifBlank { "?" }} 箱号=${box.boxCode.ifBlank { "?" }} SN×${snList.size}\n${tips.joinToString("\n")}"

                    // 物料空但有条码 → 远程反查（Turso 库）
                    if (box.materialCode.isBlank()) {
                        val ean = result.barcodes.firstOrNull { it.length == 13 && it.startsWith("69") }
                            ?: box.ean69
                        if (ean.isNotBlank()) {
                            lookup69().lookupRemote(ean) { material ->
                                runOnUiThread {
                                    if (material != null && etMaterial.text.toString().isBlank()) {
                                        etMaterial.setText(material)
                                        materialFromEan69 = true
                                        tvBoxStatus.setTextColor(cc(R.color.ls_err))
                                        tvBoxStatus.text = "🔴 商品码提供的补码：$material（可修改）"
                                    }
                                }
                            }
                        }
                    }
                }
            },
            onError = { msg ->
                runOnUiThread {
                    tvBoxStatus.text = "识别失败：$msg"
                    Toast.makeText(this, "识别失败：$msg", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun addManualSn() {
        val sn = etManualSn.text.toString().trim()
        if (sn.isEmpty()) {
            Toast.makeText(this, "请输入序列号", Toast.LENGTH_SHORT).show()
            return
        }
        if (sn !in snList) {
            snList.add(sn)
            rebuildSnList()
            etManualSn.setText("")
            updateStatus()
        } else {
            Toast.makeText(this, "序列号已存在", Toast.LENGTH_SHORT).show()
        }
    }

    /** 保存：每个 SN 展开为一条记录（共享物料/LPN/日期/型号），去重后追加 */
    private fun saveBox() {
        val material = etMaterial.text.toString().trim()
        val box = etBox.text.toString().trim()
        val date = etDate.text.toString().trim()
        val model = etModel.text.toString().trim()
        val tray = etTrayCode.text.toString().trim()

        if (snList.isEmpty()) {
            Toast.makeText(this, "序列号列表为空，无法保存", Toast.LENGTH_SHORT).show()
            return
        }
        if (material.isEmpty()) {
            Toast.makeText(this, "物料编码为空（识别不到请手动输入）", Toast.LENGTH_SHORT).show()
            return
        }
        if (box.isEmpty()) {
            Toast.makeText(this, "箱号为空（识别不到请手动输入）", Toast.LENGTH_SHORT).show()
            return
        }
        if (tray.isEmpty()) {
            Toast.makeText(this, "托盘号必填（扫描或输入托盘码）", Toast.LENGTH_SHORT).show()
            return
        }

        val records = snList.map { sn ->
            LabelResult(
                barcodes = listOf(sn),
                serialNumber = sn,
                materialCode = material,
                quantity = 1,
                productionDate = date,
                model = model,
                boxCode = box,
                trayCode = tray,
                ean69 = recognizedEan69,
                materialFromEan69 = materialFromEan69,
            )
        }
        RecordStore.append(this, records)
        if (recognizedEan69.isNotBlank()) lookup69().learn(recognizedEan69, material)
        tvBoxStatus.text = "✅ 已保存 ${records.size} 条（物料 $material / 箱号 $box）"
        Toast.makeText(this, "已保存 ${records.size} 条记录", Toast.LENGTH_SHORT).show()
        resetBox()
    }

    private fun resetBox() {
        // 托盘号保留（整批沿用），其余清空
        etMaterial.setText("")
        etBox.setText("")
        etDate.setText("")
        etModel.setText("")
        etManualSn.setText("")
        snList.clear()
        codeCandidates.clear()
        recognizedEan69 = ""
        materialFromEan69 = false
        rebuildSnList()
        rebuildCodeCandidates()
        updateStatus()
    }

    private fun updateStatus() {
        val n = snList.size
        tvBoxStatus.text = if (n == 0) "序列号 0 个" else "📦 序列号 $n 个，保存后每 SN 一行"
    }

    /** 重建 SN 行列表（LinearLayout 动态加行，避免 RecyclerView 在 ScrollView 内显示不全） */
    private fun rebuildSnList() {
        llSnList.removeAllViews()
        for ((index, sn) in snList.withIndex()) {
            val row = LayoutInflater.from(this).inflate(R.layout.item_sn_row, llSnList, false)
            row.findViewById<TextView>(R.id.tvSnItem).text = "${index + 1}. $sn"
            row.findViewById<Button>(R.id.btnDelSn).setOnClickListener {
                snList.remove(sn)
                rebuildSnList()
                updateStatus()
            }
            llSnList.addView(row)
        }
        updateStatus()
    }

    /**
     * 切换物料字段显示形态：物料编码 ⟷ 69 码。
     * 切换时顺便用对照表补出另一侧，这就是"互补互查"。
     */
    private fun toggleMaterialEanView() {
        val cur = etMaterial.text.toString().trim()
        showingEan69 = !showingEan69
        findViewById<Button>(R.id.btnToggle69).text = if (showingEan69) "物料" else "69"
        if (cur.isEmpty()) return

        val other = if (showingEan69) {
            lookup69().lookupByMaterial(cur) ?: lastKnownEan69.takeIf { it.isNotBlank() }
        } else {
            lookup69().lookup(cur) ?: lastKnownMaterial.takeIf { it.isNotBlank() }
        }
        if (other.isNullOrBlank()) {
            Toast.makeText(this, "对照表里没有这一项，请手工填写", Toast.LENGTH_SHORT).show()
            return
        }
        etMaterial.setText(other)
        if (showingEan69) lastKnownEan69 = other else lastKnownMaterial = other
        Toast.makeText(
            this,
            if (showingEan69) "已切换为 69 码：$other" else "已切换为物料编码：$other",
            Toast.LENGTH_SHORT,
        ).show()
    }

    /**
     * 互补互查：识别结果里只要有一侧，就补出另一侧并写入对照表。
     *  - 有物料编码 → 反查 69 码（OCR 常常只能读到物料）
     *  - 有 69 码   → 正查物料编码（外箱往往只有 69 条码）
     * 查到即互相 learn，下一次直接命中。
     */
    private fun crossFillMaterialEan(material: String, ean69: String) {
        val m = material.trim()
        val e = ean69.trim()
        if (m.isNotEmpty()) lastKnownMaterial = m
        if (e.isNotEmpty()) lastKnownEan69 = e

        if (m.isNotEmpty() && e.isEmpty()) {
            val found = lookup69().lookupByMaterial(m)
            if (!found.isNullOrBlank()) {
                lastKnownEan69 = found
                lookup69().learn(found, m)
            }
        }
        if (e.isNotEmpty() && m.isEmpty()) {
            val found = lookup69().lookup(e)
            if (!found.isNullOrBlank()) {
                lastKnownMaterial = found
                lookup69().learn(e, found)
            }
        }
        if (m.isNotEmpty() && e.isNotEmpty()) {
            lookup69().learn(e, m)
        }
    }

    /** 重建「已识别条码」候选区：点击任一码 → 弹选择用途（修正识别错误） */
    private fun rebuildCodeCandidates() {
        llCodeCandidates.removeAllViews()
        if (codeCandidates.isEmpty()) {
            llCodeCandidates.visibility = android.view.View.GONE
            return
        }
        llCodeCandidates.visibility = android.view.View.VISIBLE
        for ((index, code) in codeCandidates.withIndex()) {
            val row = LayoutInflater.from(this).inflate(R.layout.item_sn_row, llCodeCandidates, false)
            val tv = row.findViewById<TextView>(R.id.tvSnItem)
            val src = codeCandidateSources[code]
            tv.text = if (src == null) code else "[$src] $code"
            tv.setTextColor(
                if (src == "OCR") cc(R.color.ls_neutral) else cc(R.color.ls_primary)
            )
            row.findViewById<Button>(R.id.btnDelSn).text = "选"
            row.findViewById<Button>(R.id.btnDelSn).setOnClickListener { showCodeActionDialog(code) }
            row.setOnClickListener { showCodeActionDialog(code) }
            llCodeCandidates.addView(row)
        }
    }

    /** 条码用途选择：修正识别错误的入口 */
    private fun showCodeActionDialog(code: String) {
        AlertDialog.Builder(this)
            .setTitle("条码: $code")
            .setItems(
                arrayOf(
                    "📦 设为箱号",
                    "🏷️ 设为物料编码",
                    "📅 设为生产日期",
                    "➕ 加入序列号列表",
                    "❌ 取消"
                )
            ) { _, which ->
                when (which) {
                    0 -> { etBox.setText(code); Toast.makeText(this, "箱号已设为 $code", Toast.LENGTH_SHORT).show() }
                    1 -> { etMaterial.setText(code); Toast.makeText(this, "物料已设为 $code", Toast.LENGTH_SHORT).show() }
                    2 -> { etDate.setText(code); Toast.makeText(this, "日期已设为 $code", Toast.LENGTH_SHORT).show() }
                    3 -> {
                        if (code !in snList) {
                            snList.add(code)
                            rebuildSnList()
                            Toast.makeText(this, "已加入序列号", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, "序列号已存在", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .show()
    }

    /** 取主题色（跟随深浅模式）。 */
    private fun cc(resId: Int): Int = androidx.core.content.ContextCompat.getColor(this, resId)
}