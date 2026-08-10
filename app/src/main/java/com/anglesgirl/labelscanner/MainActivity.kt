package com.anglesgirl.labelscanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.anglesgirl.labelscanner.camera.StaticRecognizer
import com.anglesgirl.labelscanner.data.RecordStore
import com.anglesgirl.labelscanner.export.Exporter
import com.anglesgirl.labelscanner.model.LabelResult
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    // UI
    private lateinit var etTrayCode: EditText
    private lateinit var btnNextTray: Button
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

    private var currentResult: LabelResult? = null
    private lateinit var savedResults: MutableList<LabelResult>
    private var lookup69: com.anglesgirl.labelscanner.data.Barcode69Lookup? = null
    private var pendingCaptureUri: Uri? = null

    /** 相册选图回调 */
    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) recognizeStatic(uri)
    }

    /** 系统相机拍照回调（拍完直接识别，配合系统相机"文档"模式） */
    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success: Boolean ->
        val uri = pendingCaptureUri
        pendingCaptureUri = null
        if (success && uri != null) {
            recognizeStatic(uri)
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

        etTrayCode = findViewById(R.id.etTrayCode)
        btnNextTray = findViewById(R.id.btnNextTray)
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

        lookup69 = com.anglesgirl.labelscanner.data.Barcode69Lookup(this)
        savedResults = RecordStore.load(this)

        btnSave.setOnClickListener { confirmSave() }
        btnDiscard.setOnClickListener { clearCurrent() }
        btnExport.setOnClickListener { exportData() }
        btnExportWms.setOnClickListener { exportWms() }
        btnList.setOnClickListener {
            startActivityForResult(Intent(this, RecordListActivity::class.java), REQ_LIST)
        }
        btnDocScan.setOnClickListener { startDocScan() }
        btnGallery.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }
        btnCamera.setOnClickListener { launchSystemCamera() }
        btnNextTray.setOnClickListener { nextTray() }
        updateCount()
    }

    /** 托盘码：清空当前托盘数据，开始下一托盘 */
    private fun nextTray() {
        val trayCode = etTrayCode.text.toString().trim()
        if (trayCode.isEmpty()) {
            Toast.makeText(this, "请先输入/扫描托盘码", Toast.LENGTH_SHORT).show()
            return
        }
        // 保存当前托盘标记（可选：作为前缀加到 SN 前，或单独记录）
        // 这里仅清空列表，托盘码留在输入框供参考
        if (savedResults.isNotEmpty()) {
            // 导出当前托盘快照（可选自动导出），这里只是提示
            Toast.makeText(this, "托盘 $trayCode 完成（${savedResults.size} 条），开始下一托盘", Toast.LENGTH_SHORT).show()
        }
        savedResults.clear()
        RecordStore.save(this, savedResults)
        updateCount()
        etTrayCode.setText("") // 可选：清空让用户扫下一个
    }

    /** 唤起系统相机拍照（可手动切"文档"模式），拍完直接识别 */
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

    /** 文档扫描：ML Kit Document Scanner（自动对焦/扶正/去背景），返回矫正后图片再识别 */
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

    /** 静态图片识别（文档扫描结果 / 相册导入共用） */
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

    /** 展示识别结果（自动填充 + 可编辑） */
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

        // 可选：在 SN 前加上托盘码前缀，便于区分托盘
        val trayCode = etTrayCode.text.toString().trim()
        if (trayCode.isNotEmpty() && !r.serialNumber.startsWith(trayCode)) {
            r.serialNumber = "${trayCode}-${r.serialNumber}"
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

    /** 导出 WMS 格式：库存导入模板 DATA01~DATA14 */
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
}
