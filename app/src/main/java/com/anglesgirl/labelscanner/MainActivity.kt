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
    private lateinit var btnModeSingle: Button
    private lateinit var btnModeBatch: Button

    // ===== 单条识别面板 =====
    private lateinit var panelSingle: View
    private lateinit var resultPanel: TextView
    private lateinit var etMaterial: EditText
    private lateinit var etDate: EditText
    private lateinit var etSn: EditText
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

    // ===== 整板快速录入面板 =====
    private lateinit var panelBatch: View
    // 共享字段
    private lateinit var etBatchMaterial: EditText
    private lateinit var etBatchDate: EditText
    private lateinit var etBatchEan69: EditText
    private lateinit var etBatchModel: EditText
    private lateinit var etBatchColor: EditText
    private lateinit var etBatchToner: EditText
    private lateinit var btnBatchApplyShared: Button
    // 扫描区
    private lateinit var btnBatchCamera: Button
    private lateinit var btnBatchGallery: Button
    private lateinit var rvBatchSnList: RecyclerView
    private lateinit var tvBatchScannedCount: TextView
    private lateinit var tvBatchCount: TextView
    private lateinit var btnBatchClear: Button
    private lateinit var btnBatchSaveAll: Button

    // ===== 数据/状态 =====
    private var currentResult: LabelResult? = null
    private lateinit var savedResults: MutableList<LabelResult>
    private var lookup69: com.anglesgirl.labelscanner.data.Barcode69Lookup? = null
    private var pendingCaptureUri: Uri? = null

    // 批量模式状态
    private var batchModeEnabled = false
    private var batchSharedConfirmed = false
    private var batchSnList = mutableListOf<String>()
    private var batchSnAdapter: BatchSnAdapter? = null

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
    private val batchPickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) recognizeBatchSn(uri)
    }

    /** 批量模式：系统相机拍照回调 */
    private val batchTakePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success: Boolean ->
        val uri = pendingCaptureUri
        pendingCaptureUri = null
        if (success && uri != null) {
            recognizeBatchSn(uri)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) Toast.makeText(this, "需要相机/存储权限", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 共享 UI
        etTrayCode = findViewById(R.id.etTrayCode)
        btnNextTray = findViewById(R.id.btnNextTray)
        btnModeSingle = findViewById(R.id.btnModeSingle)
        btnModeBatch = findViewById(R.id.btnModeBatch)

        panelSingle = findViewById(R.id.panelSingle)
        panelBatch = findViewById(R.id.panelBatch)

        // 单条面板
        resultPanel = findViewById(R.id.resultPanel)
        etMaterial = findViewById(R.id.etMaterial)
        etDate = findViewById(R.id.etDate)
        etSn = findViewById(R.id.etSn)
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

        // 批量面板
        etBatchMaterial = findViewById(R.id.etBatchMaterial)
        etBatchDate = findViewById(R.id.etBatchDate)
        etBatchEan69 = findViewById(R.id.etBatchEan69)
        etBatchModel = findViewById(R.id.etBatchModel)
        etBatchColor = findViewById(R.id.etBatchColor)
        etBatchToner = findViewById(R.id.etBatchToner)
        btnBatchApplyShared = findViewById(R.id.btnBatchApplyShared)
        btnBatchCamera = findViewById(R.id.btnBatchCamera)
        btnBatchGallery = findViewById(R.id.btnBatchGallery)
        rvBatchSnList = findViewById(R.id.rvBatchSnList)
        tvBatchScannedCount = findViewById(R.id.tvBatchScannedCount)
        tvBatchCount = findViewById(R.id.tvBatchCount)
        btnBatchClear = findViewById(R.id.btnBatchClear)
        btnBatchSaveAll = findViewById(R.id.btnBatchSaveAll)

        // RecyclerView
        rvBatchSnList.layoutManager = LinearLayoutManager(this)
        batchSnAdapter = BatchSnAdapter(batchSnList) { sn ->
            // 长按删除
            batchSnList.remove(sn)
            batchSnAdapter?.notifyDataSetChanged()
            updateBatchCount()
        }
        rvBatchSnList.adapter = batchSnAdapter

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

        // 批量面板监听
        btnBatchApplyShared.setOnClickListener { confirmBatchShared() }
        btnBatchCamera.setOnClickListener { launchBatchCamera() }
        btnBatchGallery.setOnClickListener { batchPickImageLauncher.launch("image/*") }
        btnBatchClear.setOnClickListener { clearBatchSn() }
        btnBatchSaveAll.setOnClickListener { saveBatchAll() }

        // 模式切换
        btnModeSingle.setOnClickListener { switchMode(false) }
        btnModeBatch.setOnClickListener { switchMode(true) }

        updateCount()
        updateBatchCount()
    }

    // ===== 模式切换 =====
    private fun switchMode(isBatch: Boolean) {
        batchModeEnabled = isBatch
        if (isBatch) {
            panelSingle.visibility = View.GONE
            panelBatch.visibility = View.VISIBLE
            btnModeSingle.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF424242.toInt())
            btnModeBatch.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF1565C0.toInt())
            btnModeSingle.setTextColor(0xFFB0BEC5.toInt())
            btnModeBatch.setTextColor(0xFFFFFFFF.toInt())
            // 进入批量模式时重置
            if (!batchSharedConfirmed) {
                resetBatchMode()
            }
        } else {
            panelSingle.visibility = View.VISIBLE
            panelBatch.visibility = View.GONE
            btnModeSingle.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF2E7D32.toInt())
            btnModeBatch.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF424242.toInt())
            btnModeSingle.setTextColor(0xFFFFFFFF.toInt())
            btnModeBatch.setTextColor(0xFFB0BEC5.toInt())
        }
    }

    private fun resetBatchMode() {
        batchSharedConfirmed = false
        batchSnList.clear()
        batchSnAdapter?.notifyDataSetChanged()
        // 共享字段可编辑
        etBatchMaterial.isEnabled = true
        etBatchDate.isEnabled = true
        etBatchEan69.isEnabled = true
        etBatchModel.isEnabled = true
        etBatchColor.isEnabled = true
        etBatchToner.isEnabled = true
        btnBatchApplyShared.text = "✅ 确认共享信息，开始扫描序列号"
        btnBatchApplyShared.isEnabled = true
        btnBatchCamera.isEnabled = false
        btnBatchGallery.isEnabled = false
        updateBatchCount()
    }

    private fun confirmBatchShared() {
        val material = etBatchMaterial.text.toString().trim()
        val date = etBatchDate.text.toString().trim()
        if (material.isEmpty()) {
            Toast.makeText(this, "请填写物料编码", Toast.LENGTH_SHORT).show()
            return
        }
        if (date.isEmpty()) {
            Toast.makeText(this, "请填写生产日期 (yyyymmdd)", Toast.LENGTH_SHORT).show()
            return
        }
        // 锁定共享字段
        etBatchMaterial.isEnabled = false
        etBatchDate.isEnabled = false
        etBatchEan69.isEnabled = false
        etBatchModel.isEnabled = false
        etBatchColor.isEnabled = false
        etBatchToner.isEnabled = false
        btnBatchApplyShared.text = "🔒 共享信息已锁定"
        btnBatchApplyShared.isEnabled = false
        btnBatchCamera.isEnabled = true
        btnBatchGallery.isEnabled = true
        batchSharedConfirmed = true
        Toast.makeText(this, "共享信息已确认，开始扫描序列号", Toast.LENGTH_SHORT).show()
    }

    private fun clearBatchSn() {
        batchSnList.clear()
        batchSnAdapter?.notifyDataSetChanged()
        updateBatchCount()
        Toast.makeText(this, "已清空，重新扫描", Toast.LENGTH_SHORT).show()
    }

    // ===== 托盘码：下一托盘 =====
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
        // 批量模式也清空
        if (batchModeEnabled) {
            resetBatchMode()
        }
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

    // ===== 批量：仅识别序列号 =====
    private fun launchBatchCamera() {
        try {
            val dir = File(cacheDir, "captures").apply { mkdirs() }
            val file = File(dir, "batch_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )
            pendingCaptureUri = uri
            batchTakePictureLauncher.launch(uri)
        } catch (e: Exception) {
            Toast.makeText(this, "无法唤起相机：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun recognizeBatchSn(uri: Uri) {
        tvBatchScannedCount.text = "识别中..."
        StaticRecognizer.recognizeUri(
            resolver = contentResolver,
            uri = uri,
            lookup69 = null, // 批量模式不需要 69 码反查
            onResult = { result ->
                runOnUiThread {
                    // 仅提取条码作为序列号，去重添加
                    for (code in result.barcodes) {
                        val sn = code.trim()
                        if (sn.isNotEmpty() && sn !in batchSnList) {
                            batchSnList.add(sn)
                        }
                    }
                    // 如果没有条码但有 OCR 文本，尝试从中提取可能的序列号
                    if (result.barcodes.isEmpty() && result.ocrText.isNotBlank()) {
                        val lines = result.ocrText.lines()
                        for (line in lines) {
                            val trimmed = line.trim()
                            if (trimmed.length >= 8 && trimmed.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
                                if (trimmed !in batchSnList) {
                                    batchSnList.add(trimmed)
                                }
                            }
                        }
                    }
                    batchSnAdapter?.notifyDataSetChanged()
                    updateBatchCount()
                    tvBatchScannedCount.text = "已扫 ${batchSnList.size} 个"
                }
            },
            onError = { msg ->
                runOnUiThread {
                    tvBatchScannedCount.text = "识别失败：$msg"
                    Toast.makeText(this@MainActivity, "识别失败：$msg", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    // ===== 单条：展示识别结果 =====
    private fun showResult(result: LabelResult) {
        currentResult = result

        tvBarcodes.text = "📦 条码: " + result.barcodes.joinToString("  ") { it }.ifEmpty { "（无条码，OCR 识别）" }
        resultPanel.text = if (result.ocrText.isNotBlank()) "📝 OCR: ${result.ocrText}" else ""

        etMaterial.setText(result.materialCode)
        etDate.setText(result.productionDate)
        etSn.setText(result.serialNumber)
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
        r.serialNumber = etSn.text.toString().trim()
        r.ean69 = etEan69.text.toString().trim()
        r.model = etModel.text.toString().trim()
        r.color = etColor.text.toString().trim()
        r.tonerModel = etToner.text.toString().trim()

        if (r.serialNumber.isEmpty()) {
            Toast.makeText(this, "序列号不能为空", Toast.LENGTH_SHORT).show()
            return
        }

        // 托盘码：记录到 trayCode 字段，不修改原始 serialNumber
        val trayCode = etTrayCode.text.toString().trim()
        if (trayCode.isNotEmpty()) {
            r.trayCode = trayCode
        }

        savedResults.add(r)
        RecordStore.save(this, savedResults)
        // 自动学习：69码 → 物料编码 映射（为以后反查积累）
        lookup69?.learn(r.ean69, r.materialCode)
        updateCount()
        Toast.makeText(
            this,
            "✅ 已保存（共 ${savedResults.size} 条，反查表 ${lookup69?.size() ?: 0} 条）",
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
        etEan69.setText("")
        etModel.setText("")
        etColor.setText("")
        etToner.setText("")
    }

    // ===== 批量：保存全部 =====
    private fun saveBatchAll() {
        if (!batchSharedConfirmed) {
            Toast.makeText(this, "请先确认共享信息", Toast.LENGTH_SHORT).show()
            return
        }
        if (batchSnList.isEmpty()) {
            Toast.makeText(this, "没有扫描到任何序列号", Toast.LENGTH_SHORT).show()
            return
        }

        val material = etBatchMaterial.text.toString().trim()
        val date = etBatchDate.text.toString().trim()
        val ean69 = etBatchEan69.text.toString().trim()
        val model = etBatchModel.text.toString().trim()
        val color = etBatchColor.text.toString().trim()
        val toner = etBatchToner.text.toString().trim()
        val trayCode = etTrayCode.text.toString().trim()

        var saved = 0
        for (sn in batchSnList) {
            val result = LabelResult(
                barcodes = listOf(sn),
                ocrText = "",
                supplier = "NA",
                materialCode = material,
                quantity = 1,
                productionDate = if (date.isNotEmpty()) date else "19000101",
                ean69 = ean69,
                model = model,
                color = color,
                tonerModel = toner,
                serialNumber = sn,
                trayCode = trayCode
            )
            savedResults.add(result)
            saved++
        }
        RecordStore.save(this, savedResults)
        updateCount()

        Toast.makeText(this, "✅ 批量保存 $saved 条（托盘：${trayCode.ifBlank { "无" }}）", Toast.LENGTH_LONG).show()

        // 重置批量模式，准备下一托盘
        batchSnList.clear()
        batchSnAdapter?.notifyDataSetChanged()
        resetBatchMode()
        updateBatchCount()
    }

    private fun updateBatchCount() {
        tvBatchScannedCount.text = "已扫 ${batchSnList.size} 个"
        tvBatchCount.text = "📋 本托盘 ${batchSnList.size} 条"
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_LIST -> {
                savedResults = RecordStore.load(this)
                updateCount()
            }
            REQ_DOC_SCAN -> {
                if (resultCode == RESULT_OK && data != null) {
                    val result = GmsDocumentScanningResult.fromActivityResultIntent(data)
                    val uri: Uri? = result?.pages?.firstOrNull()?.imageUri
                    if (uri != null) {
                        recognizeStatic(uri)
                    } else {
                        Toast.makeText(this, "扫描结果为空", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        StaticRecognizer.close()
    }

    companion object {
        private const val REQ_LIST = 1002
        private const val REQ_DOC_SCAN = 1003
    }

    // ===== 批量序列号 RecyclerView Adapter =====
    private class BatchSnAdapter(
        private val snList: List<String>,
        private val onLongClick: (String) -> Unit
    ) : RecyclerView.Adapter<BatchSnAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val tvSn: TextView = view.findViewById(android.R.id.text1)
            val tvIndex: TextView = view.findViewById(android.R.id.text2)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_2, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val sn = snList[position]
            holder.tvSn.text = "#${position + 1}  $sn"
            holder.tvIndex.text = if (sn.length >= 12 && sn.take(12).all { it.isDigit() } && sn.substring(10, 12) == "01")
                "⚠️ 疑似含物料编码(12位+01)，建议确认"
            else ""
            holder.itemView.setOnLongClickListener {
                onLongClick(sn)
                true
            }
        }

        override fun getItemCount() = snList.size
    }
}