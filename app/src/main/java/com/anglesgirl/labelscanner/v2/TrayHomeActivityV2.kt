package com.anglesgirl.labelscanner.v2

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.anglesgirl.labelscanner.camera.v2.LabelAnalyzerV2
import com.anglesgirl.labelscanner.databinding.ActivityTrayHomeV2Binding
import com.anglesgirl.labelscanner.util.Diag
import java.util.concurrent.Executors

/**
 * 托盘首页：**扫码或手输托盘号 → 进入采集**。
 *
 * 之所以把"建托盘"独立成一步：托盘号是一整批记录的外键（模板 DATA02 卡板列），
 * 先确定它，采集时就只需关心箱内内容，也便于最后按托盘一次性导出。
 */
class TrayHomeActivityV2 : AppCompatActivity() {

    companion object {
        private const val REQ_CAMERA = 1002
    }

    private lateinit var binding: ActivityTrayHomeV2Binding
    private val executor = Executors.newSingleThreadExecutor()
    private var analyzer: LabelAnalyzerV2? = null

    /** 扫到的托盘码候选（取最高频的一个，避免误扫箱号）。 */
    private val seen = HashMap<String, Int>()
    private var scanning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTrayHomeV2Binding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnManual.setOnClickListener { manualInput() }
        binding.btnUseScanned.setOnClickListener { startCollect(seen.keys.lastOrNull().orEmpty()) }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startScan() else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startScan()
        } else {
            Toast.makeText(this, "可用「手工输入托盘号」继续", Toast.LENGTH_LONG).show()
        }
    }

    private fun startScan() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            val a = LabelAnalyzerV2 { barcodes, _ ->
                if (barcodes.isEmpty()) return@LabelAnalyzerV2
                runOnUiThread {
                    for (c in barcodes) seen[c] = (seen[c] ?: 0) + 1
                    binding.textScanned.text = "扫到：\n" + seen.entries
                        .sortedByDescending { it.value }
                        .take(5)
                        .joinToString("\n") { "${it.key}  (${it.value} 次)" }
                    Diag.event("tray_scan", mapOf("distinct" to seen.size))
                }
            }
            analyzer = a
            analysis.setAnalyzer(executor, a)
            try {
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                scanning = true
                Diag.event("tray_scan_started", emptyMap())
            } catch (t: Throwable) {
                Diag.event("tray_camera_failed", mapOf("err" to t.message))
                Toast.makeText(this, "相机启动失败：${t.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun manualInput() {
        val input = EditText(this)
        AlertDialog.Builder(this)
            .setTitle("手工输入托盘号")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val code = input.text.toString().trim()
                if (code.isEmpty()) {
                    Toast.makeText(this, "托盘号不能为空", Toast.LENGTH_SHORT).show()
                } else {
                    Diag.event("tray_manual", mapOf("code" to code))
                    startCollect(code)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startCollect(trayCode: String) {
        if (trayCode.isBlank()) {
            Toast.makeText(this, "请先扫到或输入托盘号", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(CollectActivityV2.intent(this, trayCode))
        finish()
    }

    override fun onDestroy() {
        analyzer?.close()
        executor.shutdown()
        super.onDestroy()
    }
}
