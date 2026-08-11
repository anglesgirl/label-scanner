package com.anglesgirl.labelscanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.anglesgirl.labelscanner.camera.StaticRecognizer
import com.anglesgirl.labelscanner.data.RecordStore
import com.anglesgirl.labelscanner.export.Exporter
import com.anglesgirl.labelscanner.model.LabelParser
import com.anglesgirl.labelscanner.model.LabelResult
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    // ===== 共享 UI =====
    private lateinit var etTrayCode: EditText
    private lateinit var btnNextTray: Button
    private lateinit var btnSettings: Button
    private lateinit var btnModeSingle: Button
    private lateinit var btnScanTrayCode: Button
    private lateinit var btnScanMaterial: Button

    // ===== 单条识别面板 =====
    private lateinit var panelSingle: View
    private lateinit var resultPanel: TextView
    private lateinit var etMaterial: EditText
    private lateinit var etDate: EditText
    private lateinit var etSn: EditText
    private lateinit var llSnList: android.widget.LinearLayout
    private lateinit var btnAddSn: Button
    private lateinit var btnScanAddSn: Button
    private val snList = mutableListOf<String>()
    private lateinit var etEan69: EditText
    private lateinit var etModel: EditText
    private lateinit var etColor: EditText
    private lateinit var etToner: EditText
    private lateinit var btnSave: Button
    private lateinit var btnDiscard: Button
    private lateinit var btnExport: Button
    private lateinit var btnExportWms: Button
    private lateinit var btnList: Button
    private lateinit var btnDocScan: Button
    private lateinit var btnGallery: Button
    private lateinit var btnCamera: Button
    private lateinit var tvBarcodes: TextView
    private lateinit var tvCount: TextView
    private lateinit var tvExtras: TextView
    private lateinit var btnTestRecognize: Button
    private lateinit var btnCopyBarcodes: Button
    private lateinit var btnCopyOcr: Button

    // ===== 数据/状态 =====
    private var currentResult: LabelResult? = null
    private lateinit var savedResults: MutableList<LabelResult>
    private var lookup69: com.anglesgirl.labelscanner.data.Barcode69Lookup? = null
    private var pendingCaptureUri: Uri? = null

    /** 相册选图回调（单条） */
    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) recognizeStatic(uri)
    }

    /** 系统相机拍照回调（单条） */
    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success: Boolean ->
        val uri = pendingCaptureUri
        pendingCaptureUri = null
        if (success && uri != null) {
            recognizeStatic(uri)
        }
    }

    /** 批量模式：相册选图回调 */

    /** 批量模式：系统相机拍照回调 */

    /** 测试识别：相册选图回调（仅打印原始条码+OCR） */
    private val testRecognizeLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) recognizeTest(uri)
    }

    /** 托盘码扫码回调 */
    private val scanTrayCodeLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) recognizeForField(uri, "trayCode")
    }

    /** 物料编码扫码回调（单条模式） */
    private val scanMaterialLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) recognizeForField(uri, "material")
    }
    /** 扫码添加 SN：相册选图 → 识别第一个条码加入 SN 列表 */
    private val scanSnLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            StaticRecognizer.recognizeUri(
                resolver = contentResolver,
                uri = uri,
                lookup69 = null,
                onResult = { result ->
                    runOnUiThread {
                        val first = result.barcodes.firstOrNull()
                        if (first == null) {
                            Toast.makeText(this, "未识别到条码", Toast.LENGTH_SHORT).show()
                        } else if (first in snList) {
                            Toast.makeText(this, "序列号已存在: $first", Toast.LENGTH_SHORT).show()
                        } else {
                            snList.add(first)
                            rebuildSnList()
                            Toast.makeText(this, "✅ 已添加 SN: $first", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onError = { msg -> Toast.makeText(this, "识别失败: $msg", Toast.LENGTH_SHORT).show() }
            )
        }
    }

    /** 物料编码扫码回调（批量模式） */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) Toast.makeText(this, "需要相机/存储权限", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge：内容延伸到状态栏/导航栏底下，避免遮挡
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { view, insets ->
            val sysBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(sysBars.left, sysBars.top, sysBars.right, sysBars.bottom)
            insets
        }

        // 共享 UI
        etTrayCode = findViewById(R.id.etTrayCode)
        btnNextTray = findViewById(R.id.btnNextTray)
        btnSettings = findViewById(R.id.btnSettings)
        btnModeSingle = findViewById(R.id.btnModeSingle)
        btnScanTrayCode = findViewById(R.id.btnScanTrayCode)
        btnScanMaterial = findViewById(R.id.btnScanMaterial)

        panelSingle = findViewById(R.id.panelSingle)

        // 单条面板
        resultPanel = findViewById(R.id.resultPanel)
        etMaterial = findViewById(R.id.etMaterial)
        etDate = findViewById(R.id.etDate)
        etSn = findViewById(R.id.etSn)
        llSnList = findViewById(R.id.llSnList)
        btnAddSn = findViewById(R.id.btnAddSn)
        btnScanAddSn = findViewById(R.id.btnScanAddSn)
        btnAddSn.setOnClickListener { addSnFromInput() }
        btnScanAddSn.setOnClickListener {
            // 扫码补扫一个 SN 加入列表
            scanSnLauncher.launch("image/*")
        }
        etEan69 = findViewById(R.id.etEan69)
        etModel = findViewById(R.id.etModel)
        etColor = findViewById(R.id.etColor)
        etToner = findViewById(R.id.etToner)
        btnSave = findViewById(R.id.btnSave)
        btnDiscard = findViewById(R.id.btnDiscard)
        btnExport = findViewById(R.id.btnExport)
        btnExportWms = findViewById(R.id.btnExportWms)
        btnList = findViewById(R.id.btnList)
        btnDocScan = findViewById(R.id.btnDocScan)
        btnGallery = findViewById(R.id.btnGallery)
        btnCamera = findViewById(R.id.btnCamera)
        tvBarcodes = findViewById(R.id.tvBarcodes)
        tvCount = findViewById(R.id.tvCount)
        tvExtras = findViewById(R.id.tvExtras)
        btnTestRecognize = findViewById(R.id.btnTestRecognize)
        btnCopyBarcodes = findViewById(R.id.btnCopyBarcodes)
        btnCopyOcr = findViewById(R.id.btnCopyOcr)

        // 初始化
        lookup69 = com.anglesgirl.labelscanner.data.Barcode69Lookup(this)
        savedResults = RecordStore.load(this)

        // 单条面板监听
        btnSave.setOnClickListener { confirmSave() }
        btnDiscard.setOnClickListener { clearCurrent() }
        btnExport.setOnClickListener { exportData() }
        btnExportWms.setOnClickListener { exportWms() }
        btnList.setOnClickListener {
            startActivityForResult(Intent(this, RecordListActivity::class.java), REQ_LIST)
        }
        btnDocScan.setOnClickListener { startDocScan() }
        btnGallery.setOnClickListener { pickImageLauncher.launch("image/*") }
        btnCamera.setOnClickListener { launchSystemCamera() }
        btnNextTray.setOnClickListener { nextTray() }
        btnScanTrayCode.setOnClickListener { launchScanForTrayCode() }
        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        btnScanMaterial.setOnClickListener { launchScanForMaterial() }

        // 测试识别：仅打印原始条码+OCR，不解析不保存
        btnTestRecognize.setOnClickListener { testRecognizeLauncher.launch("image/*") }

        // 复制按钮
        btnCopyBarcodes.setOnClickListener {
            val text = tvBarcodes.text.toString()
            copyToClipboard("原始条码", text)
        }
        btnCopyOcr.setOnClickListener {
            val text = tvExtras.text.toString()
            copyToClipboard("原始 OCR", text)
        }

        // 模式切换
        btnModeSingle.setOnClickListener {
            // 单条识别即本面板；点击仅提示（已激活）
            Toast.makeText(this, "单条识别：支持一条或多条序列号", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnModeBox).setOnClickListener {
            startActivity(Intent(this, SingleBoxInboundActivity::class.java))
        }
        findViewById<Button>(R.id.btnGotoCenter).setOnClickListener {
            startActivityForResult(Intent(this, RecordListActivity::class.java), REQ_LIST)
        }

        updateCount()
        updateBatchCount()
    }

    // ===== 模式切换 =====




    private fun nextTray() {
        val trayCode = etTrayCode.text.toString().trim()
        if (trayCode.isEmpty()) {
            Toast.makeText(this, "请先输入/扫描托盘码", Toast.LENGTH_SHORT).show()
            return
        }
        if (savedResults.isNotEmpty()) {
            Toast.makeText(this, "托盘 $trayCode 完成（${savedResults.size} 条），开始下一托盘", Toast.LENGTH_SHORT).show()
        }
        savedResults.clear()
        RecordStore.save(this, savedResults)
        updateCount()
        etTrayCode.setText("") // 可选：清空让用户扫下一个
    }

    // ===== 单条：系统相机/文档扫描/相册 =====
    private fun launchSystemCamera() {
        try {
            val dir = File(cacheDir, "captures").apply { mkdirs() }
            val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )
            pendingCaptureUri = uri
            takePictureLauncher.launch(uri)
        } catch (e: Exception) {
            Toast.makeText(this, "无法唤起相机：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startDocScan() {
        val options = GmsDocumentScannerOptions.Builder()
            .setPageLimit(1)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        val scanner = GmsDocumentScanning.getClient(options)

        scanner.getStartScanIntent(this)
            .addOnSuccessListener { intentSender ->
                startIntentSenderForResult(
                    intentSender, REQ_DOC_SCAN, null, 0, 0, 0
                )
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "文档扫描不可用：${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun recognizeStatic(uri: Uri) {
        resultPanel.text = "识别中..."
        StaticRecognizer.recognizeUri(
            resolver = contentResolver,
            uri = uri,
            lookup69 = { ean -> lookup69?.lookup(ean) },
            onResult = { result ->
                runOnUiThread { showResult(result) }
            },
            onError = { msg ->
                runOnUiThread {
                    resultPanel.text = "识别失败：$msg"
                }
            }
        )
    }

    private fun recognizeTest(uri: Uri) {
        resultPanel.text = "测试识别中..."
        tvBarcodes.text = "原始条码："
        tvExtras.text = "原始 OCR 文本："
        StaticRecognizer.recognizeUri(
            resolver = contentResolver,
            uri = uri,
            lookup69 = null,
            onResult = { result ->
                runOnUiThread {
                    val barcodes = result.barcodes.joinToString("\n") { "• $it" }
                    val ocr = result.ocrText.ifBlank { "(空)" }
                    resultPanel.text = "✅ 识别完成（仅测试，不解析不保存）"
                    tvBarcodes.text = "原始条码 (${result.barcodes.size} 个)：\n$barcodes"
                    tvExtras.text = "原始 OCR 文本：\n$ocr"
                }
            },
            onError = { msg ->
                runOnUiThread {
                    resultPanel.text = "识别失败：$msg"
                }
            }
        )
    }

    // ===== 批量：仅识别序列号 =====


    private fun showResult(result: LabelResult) {
        currentResult = result

        tvBarcodes.text = "📦 条码: " + result.barcodes.joinToString("  ") { it }.ifEmpty { "（无条码，OCR 识别）" }
        resultPanel.text = if (result.ocrText.isNotBlank()) "📝 OCR: ${result.ocrText}" else ""

        etMaterial.setText(result.materialCode)
        etDate.setText(result.productionDate)
        etSn.setText(result.serialNumber)
        // 多 SN：识别主 SN 自动进入列表（可追加/删除）
        snList.clear()
        if (result.serialNumber.isNotBlank()) snList.add(result.serialNumber)
        rebuildSnList()
        etEan69.setText(result.ean69)
        etModel.setText(result.model)
        etColor.setText(result.color)
        etToner.setText(result.tonerModel)

        // 附加信息（反查提示）
        val extras = buildList {
            if (result.ean69.isNotBlank()) {
                val hit = lookup69?.lookup(result.ean69)
                if (hit != null) add("🔁 69码反查物料: $hit")
            }
            // 物料编码为空：提示需人工输入
            if (result.materialCode.isEmpty()) {
                add("⚠️ 未识别到物料编码，请手动输入")
                // 远程反查（Turso 库，设置了配置时自动尝试）
                if (result.ean69.isNotBlank()) {
                    lookup69?.lookupRemote(result.ean69) { material ->
                        runOnUiThread {
                            if (material != null && etMaterial.text.toString().isBlank()) {
                                etMaterial.setText(material)
                                currentResult?.materialCode = material
                                Toast.makeText(
                                    this@MainActivity,
                                    "🔁 69码远程反查物料: $material",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }
            }
            // 生产日期为默认值：提示
            if (result.productionDate == "19000101") {
                add("⚠️ 未识别到生产日期，已设为 19000101")
            }
            // SN 规则触发：提示
            if (result.materialCode.isNotEmpty() && result.serialNumber.isNotEmpty()) {
                val sn = result.serialNumber
                if (sn.length >= 12 && sn.take(12).all { it.isDigit() } && sn.substring(10, 12) == "01") {
                    add("💡 SN 12位+01结尾 → 已自动提取物料编码: ${result.materialCode}")
                }
            }
        }
        tvExtras.text = extras.joinToString("　")

        // 高亮提示
        resultPanel.setBackgroundColor(
            ContextCompat.getColor(this, R.color.result_highlight)
        )
    }

    private fun confirmSave() {
        val r = currentResult ?: return
        // 人工确认/修正后的值
        r.materialCode = etMaterial.text.toString().trim()
        r.productionDate = etDate.text.toString().trim()
        r.ean69 = etEan69.text.toString().trim()
        r.model = etModel.text.toString().trim()
        r.color = etColor.text.toString().trim()
        r.tonerModel = etToner.text.toString().trim()

        // 多 SN：优先用列表（可手动追加/扫码添加）；列表空则用输入框
        if (etSn.text.toString().isNotBlank() &&
            etSn.text.toString().trim() !in snList &&
            snList.isNotEmpty()
        ) {
            // 输入框有新值且不在列表 → 视为追加项
            snList.add(etSn.text.toString().trim())
        }
        if (snList.isEmpty()) {
            val single = etSn.text.toString().trim()
            if (single.isEmpty()) {
                Toast.makeText(this, "序列号不能为空", Toast.LENGTH_SHORT).show()
                return
            }
            snList.add(single)
        }

        val trayCode = etTrayCode.text.toString().trim()

        // 每 SN 一条记录（共享物料/日期/托盘）
        val added = mutableListOf<LabelResult>()
        for (sn in snList) {
            val rec = LabelResult(
                materialCode = r.materialCode,
                productionDate = r.productionDate,
                serialNumber = sn,
                ean69 = r.ean69,
                model = r.model,
                color = r.color,
                tonerModel = r.tonerModel,
                trayCode = trayCode,
                boxCode = r.boxCode,
                barcodes = r.barcodes,
                ocrText = r.ocrText,
            )
            savedResults.add(rec)
            added.add(rec)
            // 自动学习：69码 → 物料编码 映射（为以后反查积累）
            lookup69?.learn(rec.ean69, rec.materialCode)
        }
        RecordStore.save(this, savedResults)
        updateCount()
        Toast.makeText(
            this,
            "✅ 已保存 ${added.size} 条（共 ${savedResults.size} 条，反查表 ${lookup69?.size() ?: 0} 条）",
            Toast.LENGTH_SHORT
        ).show()
        clearCurrent()
    }

    private fun updateCount() {
        tvCount.text = "📋 已保存 ${savedResults.size} 条"
    }

    private fun exportData() {
        if (savedResults.isEmpty()) {
            Toast.makeText(this, "还没有保存任何标签", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = Exporter.export(this, savedResults)
        if (uri == null) {
            Toast.makeText(this, "导出失败", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "标签数据")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "导出标签数据"))
    }

    private fun exportWms() {
        if (savedResults.isEmpty()) {
            Toast.makeText(this, "还没有保存任何标签", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = Exporter.exportWms(this, savedResults)
        if (uri == null) {
            Toast.makeText(this, "WMS 导出失败", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "WMS库存导入数据")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "导出 WMS 数据"))
    }

    private fun clearCurrent() {
        currentResult = null
        tvBarcodes.text = ""
        resultPanel.text = "等待识别..."
        resultPanel.setBackgroundColor(ContextCompat.getColor(this, R.color.result_idle))
        tvExtras.text = ""
        etMaterial.setText("")
        etDate.setText("")
        etSn.setText("")
        snList.clear()
        rebuildSnList()
        etEan69.setText("")
        etModel.setText("")
        etColor.setText("")
        etToner.setText("")
    }

    /** 手动新增 SN：输入框内容加入列表 */
    private fun addSnFromInput() {
        val sn = etSn.text.toString().trim()
        if (sn.isEmpty()) {
            Toast.makeText(this, "先在序列号框输入内容", Toast.LENGTH_SHORT).show()
            return
        }
        if (sn in snList) {
            Toast.makeText(this, "序列号已存在", Toast.LENGTH_SHORT).show()
            return
        }
        snList.add(sn)
        rebuildSnList()
        etSn.setText("")
    }

    /** 重建 SN 列表行（动态 LinearLayout，支持删除） */
    private fun rebuildSnList() {
        llSnList.removeAllViews()
        for ((index, sn) in snList.withIndex()) {
            val row = layoutInflater.inflate(R.layout.item_sn_row, llSnList, false)
            row.findViewById<TextView>(R.id.tvSnItem).text = "${index + 1}. $sn"
            row.findViewById<Button>(R.id.btnDelSn).setOnClickListener {
                snList.remove(sn)
                rebuildSnList()
            }
            llSnList.addView(row)
        }
    }

    // ===== 批量：保存全部 =====


    private fun launchScanForTrayCode() {
        pickImageLauncher.launch("image/*") // 复用相册选择，结果通过 scanTrayCodeLauncher 处理
        // 实际上需要用 scanTrayCodeLauncher，但 pickImageLauncher 已经定义了
        // 这里用一个专门的 launcher 更好，改用相册选图 + scanTrayCodeLauncher
        // 但为了简单，直接用相册选图然后手动调用 recognizeForField
        // 这里我们用 startActivityForResult 启动系统扫码或者相册
        // 简化：直接用相册选图
        scanTrayCodeLauncher.launch("image/*")
    }

    private fun launchScanForMaterial() {
        scanMaterialLauncher.launch("image/*")
    }


    private fun recognizeForField(uri: Uri, field: String) {
        StaticRecognizer.recognizeUri(
            resolver = contentResolver,
            uri = uri,
            lookup69 = null,
            onResult = { result ->
                runOnUiThread {
                    val barcodes = result.barcodes.map { it.trim() }.filter { it.isNotEmpty() }
                    if (barcodes.isEmpty()) {
                        Toast.makeText(this, "未识别到条码", Toast.LENGTH_SHORT).show()
                        return@runOnUiThread
                    }
                    if (barcodes.size == 1) {
                        // 只有一个条码，直接填入
                        fillField(field, barcodes[0])
                    } else {
                        // 多个条码：弹窗让用户选择
                        showBarcodePickerDialog(field, barcodes)
                    }
                }
            },
            onError = { msg ->
                runOnUiThread {
                    Toast.makeText(this, "识别失败：$msg", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun fillField(field: String, code: String) {
        when (field) {
            "trayCode" -> etTrayCode.setText(code)
            "material" -> etMaterial.setText(code)
        }
        Toast.makeText(this, "已填入 $field: $code", Toast.LENGTH_SHORT).show()
    }

    private fun showBarcodePickerDialog(field: String, barcodes: List<String>) {
        val fieldLabel = when (field) {
            "trayCode" -> "托盘码"
            "material" -> "物料编码"
            else -> field
        }
        val items = barcodes.map { "📦 $it" }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("识别到 ${barcodes.size} 个条码，请选择填入【$fieldLabel】")
            .setItems(items) { _, which ->
                fillField(field, barcodes[which])
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ===== 批量序列号 RecyclerView Adapter =====
