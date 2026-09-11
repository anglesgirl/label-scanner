package com.anglesgirl.labelscanner.v2

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.anglesgirl.labelscanner.camera.v2.SmartTrackAnalyzer
import com.anglesgirl.labelscanner.data.v2.BoxRecordV2
import com.anglesgirl.labelscanner.data.v2.TraySessionV2
import com.anglesgirl.labelscanner.databinding.ActivityCollectV2Binding
import com.anglesgirl.labelscanner.model.v2.LabelParserV2
import com.anglesgirl.labelscanner.util.Diag
import java.io.File
import java.util.concurrent.Executors

/**
 * 采集页：**一个界面走完一箱的采集**。
 *
 * 设计目标（针对旧版的三个体验问题）：
 *  - 旧版每扫一次弹一次确认框 → 这里改为**实时累加 + 常驻列表**，不打断
 *  - 旧版靠检测文档四边形定位 → 这里用**条码 boundingBox 追踪**（稳）
 *  - 旧版"稳定即拍"时机与眼睛错位 → 这里稳定后**自动拍照**，但参数全部上报日志，
 *    便于按真机手感回调阈值
 *
 * 所有关键环节都写 [Diag]，用户实际使用后可直接从日志定位问题（不必口述）。
 */
class CollectActivityV2 : AppCompatActivity() {

    companion object {
        private const val TAG = "CollectV2"
        private const val REQ_CAMERA = 1001

        const val EXTRA_TRAY_CODE = "tray_code"

        fun intent(context: Context, trayCode: String): Intent =
            Intent(context, CollectActivityV2::class.java).putExtra(EXTRA_TRAY_CODE, trayCode)
    }

    private lateinit var binding: ActivityCollectV2Binding
    private lateinit var tray: TraySessionV2
    private var analyzer: SmartTrackAnalyzer? = null
    private var imageCapture: ImageCapture? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    /** 当前这一箱累计到的原始码（跨帧累加，去重由解析器负责）。 */
    private val codesThisBox = linkedSetOf<String>()
    /** 当前这一箱累计到的 OCR 文本行。 */
    private val ocrThisBox = linkedSetOf<String>()

    /** 是否允许自动拍照（用户可关，避免连续误拍）。 */
    private var autoCapture = true
    /** 拍照进行中，避免稳定判定连发。 */
    private var capturing = false
    /** 帧计数，用于 scan_frame 采样上报。 */
    private var frameCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCollectV2Binding.inflate(layoutInflater)
        setContentView(binding.root)

        val trayCode = intent.getStringExtra(EXTRA_TRAY_CODE).orEmpty()
        tray = TraySessionV2(trayCode)
        Diag.event("collect_open", mapOf("tray" to trayCode))

        binding.trayCodeText.text = trayCode.ifBlank { "(未命名托盘)" }
        binding.btnConfirm.setOnClickListener { confirmBox() }
        binding.btnRescan.setOnClickListener { resetCurrentBox("用户点重扫") }
        binding.btnManual.setOnClickListener { showManualInput() }
        binding.switchAuto.setOnCheckedChangeListener { _, checked ->
            autoCapture = checked
            Diag.event("auto_capture_toggle", mapOf("on" to checked))
        }
        binding.btnFinish.setOnClickListener { finishTray() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            Toast.makeText(this, "需要相机权限才能采集", Toast.LENGTH_LONG).show()
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            cameraProviderRef = provider
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            // 分析帧不需要高分辨率：识别靠静图，实时帧只用于追踪与粗筛，
            // 降一档能显著降低 CPU/延迟（旧版卡顿的一个来源）。
            val resolution = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        android.util.Size(1280, 720),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                    )
                ).build()
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(resolution)
                .build()

            // 拍照用最高质量：强通道(zxing-cpp)靠的是这张静图
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()
            imageCapture = capture

            val track = SmartTrackAnalyzer(
                getZoomState = { provider.getCameraInfo(CameraSelector.DEFAULT_BACK_CAMERA).zoomState.value },
                getCameraControl = { cameraControlHolderOrNull },
                onStable = {
                    if (autoCapture) runOnUiThread { takePicture() }
                },
                onFrame = { barcodes, lines, box ->
                    runOnUiThread { onFrame(barcodes, lines, box) }
                },
            )
            analyzer = track
            analysis.setAnalyzer(cameraExecutor, track)

            try {
                val camera = provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis, capture,
                )
                cameraControlHolderOrNull = camera.cameraControl
                Diag.event("camera_started", mapOf("zoom_max" to camera.cameraInfo.zoomState.value?.maxZoomRatio))
            } catch (t: Throwable) {
                Log.e(TAG, "相机启动失败", t)
                Diag.event("camera_failed", mapOf("err" to t.message))
                Toast.makeText(this, "相机启动失败：${t.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** bindToLifecycle 之后才有值；分析器通过 lambda 惰性读取。 */
    @Volatile
    private var cameraControlHolderOrNull: androidx.camera.core.CameraControl? = null

    /** 每帧回调：累加码与文本，刷新预览信息。 */
    private fun onFrame(barcodes: List<String>, lines: List<String>, box: android.graphics.Rect?) {
        var added = 0
        for (c in barcodes) {
            if (codesThisBox.add(c)) added++
        }
        for (l in lines) ocrThisBox.add(l)

        frameCount++
        // scan_frame 采样上报：每 10 帧或本帧有新增码时
        if (frameCount % 10 == 0 || added > 0) {
            val areaRatio = box?.let {
                (it.width().toFloat() * it.height()) / (1280f * 720f)
            } ?: 0f
            Diag.event(
                "scan_frame",
                mapOf(
                    "barcodes" to barcodes.size,
                    "added" to added,
                    "codes_total" to codesThisBox.size,
                    "box_area" to String.format("%.3f", areaRatio),
                    "zoom" to zoomRatioForLog(),
                ),
            )
        }
        if (added > 0) beep()
        refreshParsedPreview()
    }

    private var cameraProviderRef: ProcessCameraProvider? = null

    /** 当前变焦倍数（用于日志）。 */
    private fun zoomRatioForLog(): String {
        val z = cameraProviderRef
            ?.getCameraInfo(CameraSelector.DEFAULT_BACK_CAMERA)
            ?.zoomState?.value?.zoomRatio
        return String.format("%.2f", z ?: 0f)
    }

    /** 用已测过的解析内核实时预览"这一箱现在解析成什么样"。 */
    private fun refreshParsedPreview() {
        val result = LabelParserV2.parseAll(codesThisBox.toList(), ocrThisBox.toList())
        binding.textParsed.text = buildString {
            appendLine("物料  ${result.materialCode.ifBlank { "—" }}")
            appendLine("序列号  ${if (result.serialNumbers.isEmpty()) "—" else "${result.serialNumbers.size} 个"}")
            appendLine("日期  ${result.productionDate.ifBlank { "—" }}")
            appendLine("箱号  ${result.boxCode.ifBlank { "—" }}")
            if (result.warnings.isNotEmpty()) {
                appendLine()
                result.warnings.forEach { appendLine("⚠️ $it") }
            }
        }
        binding.textParsed.setTextColor(
            if (result.warnings.isEmpty()) 0xFF1B5E20.toInt() else 0xFFB71C1C.toInt()
        )
    }

    private fun takePicture() {
        val capture = imageCapture ?: return
        if (capturing) return
        capturing = true
        val t0 = System.currentTimeMillis()
        val file = File(cacheDir, "box_${System.currentTimeMillis()}.jpg")

        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                    val cost = System.currentTimeMillis() - t0
                    Diag.event(
                        "photo_taken",
                        mapOf("ms" to cost, "bytes" to file.length(), "codes" to codesThisBox.size),
                    )
                    runOnUiThread {
                        capturing = false
                        // 静图交给强通道再跑一遍（实时帧会漏小码/斜角）
                        recognizeStill(file)
                    }
                }

                override fun onError(e: ImageCaptureException) {
                    capturing = false
                    Diag.event("photo_error", mapOf("err" to e.message))
                    Log.w(TAG, "拍照失败", e)
                }
            },
        )
    }

    /**
     * 静图识别：走上层的强通道（zxing-cpp 3× + ML Kit）。
     * 具体实现放在 StillRecognizerBridge，避免本文件与相机库耦合。
     */
    private fun recognizeStill(file: File) {
        StillRecognizerBridge.recognize(
            file,
            onDone = { extra ->
                var added = 0
                for (c in extra) if (codesThisBox.add(c)) added++
                if (added > 0) beep()
                refreshParsedPreview()
                Diag.event(
                    "still_recognized",
                    mapOf("found" to extra.size, "added" to added, "codes_total" to codesThisBox.size),
                )
                file.delete()
            },
            onFail = { msg ->
                Diag.event("still_failed", mapOf("err" to msg))
                file.delete()
            },
        )
    }

    /** 确认入库：把当前累计的码交给解析内核归组，加入托盘。 */
    private fun confirmBox() {
        val result = LabelParserV2.parseAll(codesThisBox.toList(), ocrThisBox.toList())
        if (!result.hasData) {
            Toast.makeText(this, "还没有识别到内容", Toast.LENGTH_SHORT).show()
            return
        }
        val record = BoxRecordV2.from(result)
        tray.addBox(record)
        Diag.event(
            "box_confirmed",
            mapOf(
                "material" to record.materialCode,
                "box" to record.boxCode,
                "sn_count" to record.serialNumbers.size,
                "qty" to record.effectiveQty,
                "warnings" to record.warnings.joinToString(";"),
                "tray_boxes" to tray.totalBoxes,
                "tray_units" to tray.totalUnits,
            ),
        )
        Toast.makeText(
            this,
            "已入库（本托盘 ${tray.totalBoxes} 箱 / ${tray.totalUnits} 件）",
            Toast.LENGTH_SHORT,
        ).show()
        resetCurrentBox("入库后自动清空")
        refreshTrayLine()
    }

    private fun resetCurrentBox(reason: String) {
        codesThisBox.clear()
        ocrThisBox.clear()
        analyzer?.restartStability()
        refreshParsedPreview()
        Diag.event("box_reset", mapOf("reason" to reason))
        binding.textParsed.text = "对准标签，自动识别…"
        binding.textParsed.setTextColor(0xFF37474F.toInt())
    }

    private fun refreshTrayLine() {
        binding.trayStat.text = "本托盘：${tray.totalBoxes} 箱 / ${tray.totalUnits} 件"
    }

    private fun showManualInput() {
        val input = android.widget.EditText(this)
        AlertDialog.Builder(this)
            .setTitle("手工补录（扫不到时用）")
            .setMessage("每行一个值：物料编码 / 序列号 / 日期 / 箱号 都可以")
            .setView(input)
            .setPositiveButton("加入") { _, _ ->
                val lines = input.text.toString().split('\n', ',', ';')
                    .map { it.trim() }.filter { it.isNotEmpty() }
                var added = 0
                for (l in lines) if (codesThisBox.add(l)) added++
                Diag.event("manual_input", mapOf("lines" to lines.size, "added" to added))
                refreshParsedPreview()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun finishTray() {
        tray.let {
            Diag.event(
                "tray_finished",
                mapOf("boxes" to it.totalBoxes, "units" to it.totalUnits, "rows" to it.totalRows),
            )
        }
        startActivity(TraySummaryActivityV2.intent(this, tray))
        finish()
    }

    private fun beep() {
        try {
            val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as Vibrator
            }
            vib.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Throwable) {
        }
    }

    override fun onDestroy() {
        analyzer?.close()
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}
