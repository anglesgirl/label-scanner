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
    private lateinit var etEan69: EditText
    private lateinit var etModel: EditText
    private lateinit var etColor: EditText
    private lateinit var etToner: EditText
    private lateinit var llSnList: LinearLayout
    private lateinit var llCodeCandidates: LinearLayout
    private lateinit var tvStatus: TextView
    private lateinit var tvTrayCount: TextView

    private val snList = mutableListOf<String>()
    private val codeCandidates = mutableListOf<String>()
    private val lookup69Lazy = lazy { Barcode69Lookup(this) }
    private fun lookup69(): Barcode69Lookup = lookup69Lazy.value

    private var scanTargetField: EditText? = null
    private var scanAppendToSn = false
    private var pendingPhotoUri: Uri? = null
    private var photoFile: File? = null

    private val pickGallery = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) recognizeStatic(uri)
    }
    /** 字段补扫：实时扫码相机 → 确认框 → 填目标框（不拍照，自动识别） */
    private val liveScan = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val code = result.data?.getStringExtra(LiveScanActivity.EXTRA_RESULT_CODE)
            if (code != null) onScannedCode(code)
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
    private val takePhoto = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringExtra(com.anglesgirl.labelscanner.camera.CaptureActivity.EXTRA_OUTPUT_URI)
                ?.let { recognizeStatic(Uri.parse(it)) }
        }
    }
    /** 文档扫描（ML Kit FULL） */
    private val scanDoc = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
                .fromActivityResultIntent(result.data)?.pages?.firstOrNull()?.imageUri
            if (uri != null) recognizeStatic(uri)
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
        etEan69 = findViewById(R.id.etEan69)
        etModel = findViewById(R.id.etModel)
        etColor = findViewById(R.id.etColor)
        etToner = findViewById(R.id.etToner)
        llSnList = findViewById(R.id.llSnList)
        llCodeCandidates = findViewById(R.id.llCodeCandidates)
        tvStatus = findViewById(R.id.tvStatus)
        tvTrayCount = findViewById(R.id.tvTrayCount)
        updateTrayCount()

        findViewById<Button>(R.id.btnTakePhoto).setOnClickListener { launchCamera() }
        findViewById<Button>(R.id.btnScanDoc).setOnClickListener { launchDocScan() }
        findViewById<Button>(R.id.btnPickGallery).setOnClickListener { pickGallery.launch("image/*") }
        findViewById<Button>(R.id.btnAddSn).setOnClickListener { addSnFromInput() }
        findViewById<Button>(R.id.btnScanAddSn).setOnClickListener {
            scanAppendToSn = true
            liveScan.launch(
                Intent(this, LiveScanActivity::class.java)
                    .putExtra(LiveScanActivity.EXTRA_TITLE, "序列号")
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

        // 69 码输入完（失焦或输够 13 位）自动反查
        etEan69.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val ean = s?.toString()?.trim().orEmpty()
                // 13 位 69 开头即自动反查（物料为空时才填）
                if (ean.length == 13 && ean.startsWith("69") && etMaterial.text.toString().isBlank()) {
                    doLookup69(ean, auto = true)
                }
            }
        })

        // 字段补扫按钮：弹实时扫码相机 → 确认框 → 填入（不拍照后识别）
        val scanMap = mapOf(
            R.id.btnScanMaterial to (etMaterial to "物料编码"),
            R.id.btnScanTrayCode to (etTrayCode to "托盘号"),
            R.id.btnScanDate to (etDate to "生产日期"),
            R.id.btnScanModel to (etModel to "型号"),
            R.id.btnScanEan69 to (etEan69 to "69 商品码"),
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

        rebuildSnList()
        rebuildCodeCandidates()
        updateSaveButton()
    }

    private fun launchCamera() {
        takePhoto.launch(Intent(this, com.anglesgirl.labelscanner.camera.CaptureActivity::class.java))
    }

    private fun launchDocScan() {
        try {
            val options = com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.Builder()
                .setGalleryImportAllowed(true)
                .setPageLimit(1)
                // FULL 模式（最强）：自动找边 + 裁切 + 透视矫正 + 图像增强。
                // 对 PDF417 集成码/密集条码至关重要——系统相机拍照有透视变形，
                // 二维堆叠码对透视极敏感，矫正后才能稳定解出。
                .setScannerMode(com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                .setResultFormats(com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                .build()
            com.google.mlkit.vision.documentscanner.GmsDocumentScanning.getClient(options)
                .getStartScanIntent(this)
                .addOnSuccessListener { scanDoc.launch(androidx.activity.result.IntentSenderRequest.Builder(it).build()) }
                .addOnFailureListener { e -> Toast.makeText(this, "文档扫描不可用: ${e.message}", Toast.LENGTH_LONG).show() }
        } catch (e: Exception) {
            Toast.makeText(this, "文档扫描不可用: ${e.message}", Toast.LENGTH_LONG).show()
        }
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
        etEan69.setText(result.ean69)
        etModel.setText(result.model)
        etColor.setText(result.color)
        etToner.setText(result.tonerModel)

        snList.clear()
        if (result.serialNumber.isNotBlank()) snList.add(result.serialNumber)
        rebuildSnList()

        codeCandidates.clear()
        codeCandidates.addAll(result.barcodes)
        rebuildCodeCandidates()

        var tips = ""
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
        } else if (result.productionDate == "19000101") {
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

    /** 手动点「🔁 查」：无论物料是否已填都强制反查 */
    private fun manualLookup69() {
        val ean = etEan69.text.toString().trim()
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
            row.findViewById<TextView>(R.id.tvSnItem).text = "${index + 1}. $sn"
            row.findViewById<Button>(R.id.btnDelSn).setOnClickListener {
                snList.remove(sn); rebuildSnList()
            }
            llSnList.addView(row)
        }
        updateSaveButton()
    }

    private fun rebuildCodeCandidates() {
        llCodeCandidates.removeAllViews()
        if (codeCandidates.isEmpty()) return
        for (code in codeCandidates) {
            val row = LayoutInflater.from(this).inflate(R.layout.item_sn_row, llCodeCandidates, false)
            val tv = row.findViewById<TextView>(R.id.tvSnItem)
            tv.text = code
            tv.setTextColor(0xFF1B6EF3.toInt())
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
                    3 -> { etEan69.setText(code); Toast.makeText(this, "69 码已设为 $code", Toast.LENGTH_SHORT).show() }
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
        val ean = etEan69.text.toString().trim()
        val model = etModel.text.toString().trim()
        val color = etColor.text.toString().trim()
        val toner = etToner.text.toString().trim()

        val records = snList.map { sn ->
            LabelResult(
                materialCode = material, productionDate = date, serialNumber = sn,
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
        etSn.setText(""); etEan69.setText(""); etModel.setText(""); etColor.setText(""); etToner.setText("")
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
            R.id.btnScanMaterial, R.id.btnScanEan69,
            R.id.btnLookup69, R.id.btnScanDate, R.id.btnScanModel,
            R.id.btnScanAddSn, R.id.btnSave,
        )
        for (id in gated) findViewById<Button>(id).isEnabled = !locked
        tvStatus.text = if (locked) "⚠️ 请先填写托盘号（必填），才能扫描入库" else ""
    }
}