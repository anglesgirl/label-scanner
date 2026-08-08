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
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.anglesgirl.labelscanner.camera.BarcodeAnalyzer
import com.anglesgirl.labelscanner.camera.StaticRecognizer
import com.anglesgirl.labelscanner.data.Barcode69Lookup
import com.anglesgirl.labelscanner.data.RecordStore
import com.anglesgirl.labelscanner.model.LabelResult
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var resultPanel: TextView
    private lateinit var etMaterial: EditText
    private lateinit var etDate: EditText
    private lateinit var etSn: EditText
    private lateinit var etEan69: EditText
    private lateinit var btnSave: Button
    private lateinit var btnDiscard: Button
    private lateinit var btnExport: Button
    private lateinit var btnList: Button
    private lateinit var btnDocScan: Button
    private lateinit var btnGallery: Button
    private lateinit var tvBarcodes: TextView
    private lateinit var tvCount: TextView
    private lateinit var tvExtras: TextView

    private var analyzer: BarcodeAnalyzer? = null
    private var cameraExecutor = Executors.newSingleThreadExecutor()
    private var currentResult: LabelResult? = null

    private lateinit var savedResults: MutableList<LabelResult>
    private lateinit var lookup69: Barcode69Lookup

    /** 相册选图回调 */
    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) recognizeStatic(uri)
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else Toast.makeText(this, "需要相机权限", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        resultPanel = findViewById(R.id.resultPanel)
        etMaterial = findViewById(R.id.etMaterial)
        etDate = findViewById(R.id.etDate)
        etSn = findViewById(R.id.etSn)
        etEan69 = findViewById(R.id.etEan69)
        btnSave = findViewById(R.id.btnSave)
        btnDiscard = findViewById(R.id.btnDiscard)
        btnExport = findViewById(R.id.btnExport)
        btnList = findViewById(R.id.btnList)
        btnDocScan = findViewById(R.id.btnDocScan)
        btnGallery = findViewById(R.id.btnGallery)
        tvBarcodes = findViewById(R.id.tvBarcodes)
        tvCount = findViewById(R.id.tvCount)
        tvExtras = findViewById(R.id.tvExtras)

        lookup69 = Barcode69Lookup(this)
        savedResults = RecordStore.load(this)

        btnSave.setOnClickListener { confirmSave() }
        btnDiscard.setOnClickListener { clearCurrent() }
        btnExport.setOnClickListener { exportData() }
        btnList.setOnClickListener {
            startActivityForResult(Intent(this, RecordListActivity::class.java), REQ_LIST)
        }
        btnDocScan.setOnClickListener { startDocScan() }
        btnGallery.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }
        updateCount()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analyzer = BarcodeAnalyzer(
                onResult = { result ->
                    runOnUiThread { showResult(result) }
                },
                lookup69 = { ean -> lookup69.lookup(ean) }
            )
            analysis.setAnalyzer(cameraExecutor, analyzer!!)

            provider.unbindAll()
            provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis
            )
        }, ContextCompat.getMainExecutor(this))
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

        // 附加信息（型号/颜色/硒鼓 + 反查提示）
        val extras = buildList {
            if (result.model.isNotBlank()) add("型号: ${result.model}")
            if (result.color.isNotBlank()) add("颜色: ${result.color}")
            if (result.tonerModel.isNotBlank()) add("硒鼓: ${result.tonerModel}")
            if (result.ean69.isNotBlank()) {
                val hit = lookup69.lookup(result.ean69)
                if (hit != null) add("🔁 69码反查物料: $hit")
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

        if (r.serialNumber.isEmpty()) {
            Toast.makeText(this, "序列号不能为空", Toast.LENGTH_SHORT).show()
            return
        }
        savedResults.add(r)
        RecordStore.save(this, savedResults)
        // 自动学习：69码 → 物料编码 映射（为以后反查积累）
        lookup69.learn(r.ean69, r.materialCode)
        updateCount()
        Toast.makeText(
            this,
            "✅ 已保存（共 ${savedResults.size} 条，反查表 ${lookup69.size()} 条）",
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
        val uri = com.anglesgirl.labelscanner.export.Exporter.export(this, savedResults)
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

    private fun clearCurrent() {
        currentResult = null
        analyzer?.reset()   // 解锁分析器，识别下一个标签
        tvBarcodes.text = ""
        resultPanel.text = "等待识别..."
        resultPanel.setBackgroundColor(ContextCompat.getColor(this, R.color.result_idle))
        tvExtras.text = ""
        etMaterial.setText("")
        etDate.setText("")
        etSn.setText("")
        etEan69.setText("")
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
            uri,
            openStream = { contentResolver.openInputStream(uri) },
            lookup69 = { ean -> lookup69.lookup(ean) },
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_LIST -> {
                // 从列表页返回：重新加载（可能编辑/删除了记录）
                savedResults = RecordStore.load(this)
                updateCount()
            }
            REQ_DOC_SCAN -> {
                // 文档扫描完成，拿矫正后的图片识别
                if (resultCode == RESULT_OK && data != null) {
                    val uri: Uri? = data.getParcelableExtra(
                        GmsDocumentScanning.RESULT_EXTRA_SCANNED_IMAGE_URI
                    )
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
        analyzer?.close()
        StaticRecognizer.close()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val REQ_LIST = 1002
        private const val REQ_DOC_SCAN = 1003
    }
}
