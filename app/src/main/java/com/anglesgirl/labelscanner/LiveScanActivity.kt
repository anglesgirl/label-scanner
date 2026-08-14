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
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
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
    }

    private lateinit var previewView: PreviewView

    /** 弹框期间暂停分析，避免重复弹窗 */
    private val paused = AtomicBoolean(false)
    private var lastRejected = ""
    private var lastRejectedAt = 0L

    private var barcodeScanner: BarcodeScanner? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live_scan)

        previewView = findViewById(R.id.pvScan)
        val tvHint = findViewById<TextView>(R.id.tvScanHint)
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        tvHint.text = if (title.isEmpty()) "对准条码，自动识别" else "对准${title}条码，自动识别"
        findViewById<Button>(R.id.btnCloseScan).setOnClickListener { finish() }

        barcodeScanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
                .build()
        )
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

    private fun analyzeFrame(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null || paused.get()) {
            imageProxy.close()
            return
        }
        val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        barcodeScanner?.process(inputImage)
            ?.addOnSuccessListener { barcodes ->
                val value = barcodes.firstNotNullOfOrNull { it.rawValue }
                if (value != null) onBarcodeDetected(value)
            }
            ?.addOnFailureListener { /* 单帧失败忽略，继续下一帧 */ }
            ?.addOnCompleteListener { imageProxy.close() }
    }

    private fun onBarcodeDetected(value: String) {
        val now = System.currentTimeMillis()
        // 刚取消过的码：1.5s 冷却，避免取消后立刻又弹
        if (value == lastRejected && now - lastRejectedAt < 1500) return
        if (!paused.compareAndSet(false, true)) return

        runOnUiThread {
            beep()
            AlertDialog.Builder(this)
                .setTitle("📦 扫码结果")
                .setMessage("条码：$value\n\n确认使用这个条码吗？")
                .setCancelable(false)
                .setPositiveButton("确定") { _, _ ->
                    setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT_CODE, value))
                    finish()
                }
                .setNegativeButton("取消") { _, _ ->
                    lastRejected = value
                    lastRejectedAt = System.currentTimeMillis()
                    paused.set(false)
                }
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
        barcodeScanner?.close()
        super.onDestroy()
    }
}
