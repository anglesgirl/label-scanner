package com.anglesgirl.labelscanner

import android.Manifest
import android.content.pm.PackageManager
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
import com.anglesgirl.labelscanner.model.LabelResult
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var resultPanel: TextView
    private lateinit var etMaterial: EditText
    private lateinit var etDate: EditText
    private lateinit var etSn: EditText
    private lateinit var btnSave: Button
    private lateinit var btnDiscard: Button
    private lateinit var tvBarcodes: TextView

    private var analyzer: BarcodeAnalyzer? = null
    private var cameraExecutor = Executors.newSingleThreadExecutor()
    private var currentResult: LabelResult? = null

    private val savedResults = mutableListOf<LabelResult>()

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
        btnSave = findViewById(R.id.btnSave)
        btnDiscard = findViewById(R.id.btnDiscard)
        tvBarcodes = findViewById(R.id.tvBarcodes)

        btnSave.setOnClickListener { confirmSave() }
        btnDiscard.setOnClickListener { clearCurrent() }

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
            analyzer = BarcodeAnalyzer { result ->
                runOnUiThread { showResult(result) }
            }
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

        tvBarcodes.text = "📦 条码: " + result.barcodes.joinToString("  ") { it }.ifEmpty { "（无，OCR 识别）" }
        resultPanel.text = if (result.ocrText.isNotBlank()) "📝 OCR: ${result.ocrText}" else ""

        etMaterial.setText(result.materialCode)
        etDate.setText(result.productionDate)
        etSn.setText(result.serialNumber)

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

        if (r.serialNumber.isEmpty()) {
            Toast.makeText(this, "序列号不能为空", Toast.LENGTH_SHORT).show()
            return
        }
        savedResults.add(r)
        Toast.makeText(this, "✅ 已保存（共 ${savedResults.size} 条）", Toast.LENGTH_SHORT).show()
        clearCurrent()
    }

    private fun clearCurrent() {
        currentResult = null
        tvBarcodes.text = ""
        resultPanel.text = "等待识别..."
        resultPanel.setBackgroundColor(ContextCompat.getColor(this, R.color.result_idle))
        etMaterial.setText("")
        etDate.setText("")
        etSn.setText("")
    }

    override fun onDestroy() {
        super.onDestroy()
        analyzer?.close()
        cameraExecutor.shutdown()
    }
}
