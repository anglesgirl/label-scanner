package com.anglesgirl.labelscanner.v2

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.View
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
import com.anglesgirl.labelscanner.camera.v2.LabelRectifier
import com.anglesgirl.labelscanner.camera.v2.SingleShotAnalyzer
import com.anglesgirl.labelscanner.data.v2.BoxRecordV2
import com.anglesgirl.labelscanner.data.v2.TraySessionV2
import com.anglesgirl.labelscanner.databinding.ActivityCollectV2Binding
import com.anglesgirl.labelscanner.model.v2.BoxParseResultV2
import com.anglesgirl.labelscanner.model.v2.LabelParserV2
import com.anglesgirl.labelscanner.util.Diag
import java.io.File
import java.util.concurrent.Executors

/**
 * 采集页：**一箱一张，拍完核对，再进下一箱**。
 *
 * 设计原则（用户明确要求）：
 *   进入托盘 → 对准标签 → 拍照（一张）→ 核对数据 → 对则保存 / 不对则重拍 → 下一张
 *
 * 为什么不做成"实时连续识别"：
 *  1. **费电** —— 相机与分析器需持续工作；
 *  2. 实时多帧累加会让人不知道"什么时候算识别完"，且误识别的值会混进来；
 *  3. 把"按下快门的时机"交给算法判断，必然与人的直觉错位。
 *   改成单张后：相机只在拍摄那一下工作，结果确定，核对独立成一步。
 *
 * 状态机：PREVIEW（取景）→ REVIEW（核对）→ 保存后回 PREVIEW
 * 核对期间相机不采集（省电）。
 */
class CollectActivityV2 : AppCompatActivity() {

    companion object {
        private const val TAG = "CollectV2"
        private const val REQ_CAMERA = 1001
        const val EXTRA_TRAY_CODE = "tray_code"

        /** 对准后是否自动拍一张（可在界面上切换；关掉就完全手动）。 */
        private const val AUTO_CAPTURE_DEFAULT = true

        /** 拍完静图后最长等多久拿识别结果（超时则按"没识别到"处理）。 */
        private const val STILL_TIMEOUT_MS = 6000L

        fun intent(context: Context, trayCode: String): Intent =
            Intent(context, CollectActivityV2::class.java).putExtra(EXTRA_TRAY_CODE, trayCode)
    }

    private enum class Phase { PREVIEW, REVIEW }

    private lateinit var binding: ActivityCollectV2Binding
    private lateinit var tray: TraySessionV2
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private var provider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    /** bindToLifecycle 之后才有值（分析器通过 lambda 惰性读取）。 */
    @Volatile
    private var cameraControlOrNull: androidx.camera.core.CameraControl? = null
    private var analyzer: SingleShotAnalyzer? = null

    private var phase = Phase.PREVIEW
    private var autoCapture = AUTO_CAPTURE_DEFAULT
    private var capturing = false
    private var pendingFile: File? = null

    // 本张标签的识别结果（单张，不跨张累加）
    private var codesThisShot = emptyList<String>()
    private var ocrThisShot = ""
    private var parsed: BoxParseResultV2? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCollectV2Binding.inflate(layoutInflater)
        setContentView(binding.root)

        val trayCode = intent.getStringExtra(EXTRA_TRAY_CODE).orEmpty()
        tray = TraySessionV2(trayCode)
        Diag.event("collect_open", mapOf("tray" to trayCode))

        binding.trayCodeText.text = trayCode.ifBlank { "(未命名托盘)" }
        binding.switchAuto.isChecked = autoCapture
        binding.switchAuto.setOnCheckedChangeListener { _, checked ->
            autoCapture = checked
            Diag.event("auto_capture_toggle", mapOf("on" to checked))
        }

        binding.btnShutter.setOnClickListener { captureNow("manual") }
        binding.btnConfirm.setOnClickListener { saveCurrentShot() }
        binding.btnRescan.setOnClickListener { backToPreview("用户点重拍") }
        binding.btnManual.setOnClickListener { showManualInput() }
        binding.btnFinish.setOnClickListener { finishTray() }

        refreshTrayLine()
        refreshPreviewHint()

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
            Toast.makeText(this, "需要相机权限；也可以点「补录」手工填", Toast.LENGTH_LONG).show()
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val p = future.get()
            provider = p

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            // 分析帧只用于"对准了没有"的判断，分辨率压到 720p 省电
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

            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()
            imageCapture = capture

            val a = SingleShotAnalyzer(
                getZoomState = {
                    p.getCameraInfo(CameraSelector.DEFAULT_BACK_CAMERA).zoomState.value
                },
                getCameraControl = { cameraControlOrNull },
                onAligned = {
                    // 对准且稳定：只在自动模式、且当前处于取景态时拍一张
                    if (autoCapture && phase == Phase.PREVIEW && !capturing) {
                        runOnUiThread { captureNow("aligned") }
                    }
                },
                onProgress = { aligned, areaRatio ->
                    runOnUiThread { showAlignment(aligned, areaRatio) }
                },
            )
            analyzer = a
            analysis.setAnalyzer(cameraExecutor, a)

            try {
                val cam = p.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis, capture)
                cameraControlOrNull = cam.cameraControl
                Diag.event("camera_started", emptyMap())
            } catch (t: Throwable) {
                Log.e(TAG, "相机启动失败", t)
                Diag.event("camera_failed", mapOf("err" to t.message))
                Toast.makeText(this, "相机启动失败：${t.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ===== 取景态 =====

    private fun showAlignment(aligned: Boolean, areaRatio: Float) {
        if (phase != Phase.PREVIEW) return
        binding.hint.text = if (aligned) {
            if (autoCapture) "✅ 已对准，正在拍摄…" else "✅ 已对准，按快门"
        } else {
            "把标签放进取景框（当前占 ${(areaRatio * 100).toInt()}%）"
        }
    }

    private fun refreshPreviewHint() {
        binding.hint.text = if (autoCapture) "对准标签，自动拍摄一张" else "对准标签后按快门"
    }

    private fun captureNow(reason: String) {
        val capture = imageCapture ?: return
        if (phase != Phase.PREVIEW || capturing) return
        capturing = true
        val t0 = System.currentTimeMillis()
        val file = File(cacheDir, "shot_${System.currentTimeMillis()}.jpg")
        Diag.event("capture_start", mapOf("reason" to reason))

        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                    val shotMs = System.currentTimeMillis() - t0
                    Diag.event("photo_taken", mapOf("ms" to shotMs, "bytes" to file.length()))
                    pendingFile = file
                    // 先在静图上做透视矫正得到"正图"，再用正图识别。
                    // 静图只检测一次，不存在实时预览那种边框乱跳。
                    rectifyThenRecognize(file, shotMs)
                }

                override fun onError(e: ImageCaptureException) {
                    capturing = false
                    Diag.event("photo_error", mapOf("err" to e.message))
                    runOnUiThread { Toast.makeText(this@CollectActivityV2, "拍照失败：${e.message}", Toast.LENGTH_SHORT).show() }
                }
            },
        )
    }

    /**
     * 把矫正结果存到相册 Pictures/LabelScanner，方便肉眼核对矫正是否正常。
     * 同时保留原始照片（同一目录，后缀 _raw），便于对比。
     */
    private fun saveRectifiedPreview(bmp: Bitmap, baseName: String) {
        try {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "${baseName}_rectified.jpg")
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/LabelScanner")
            }
            val uri = contentResolver.insert(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            ) ?: return
            contentResolver.openOutputStream(uri)?.use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
            Diag.event("rectified_saved", mapOf("name" to "${baseName}_rectified.jpg"))
        } catch (t: Throwable) {
            Diag.event("rectified_save_failed", mapOf("err" to t.message))
        }
    }

    /**
     * 先矫正（自动找标签四角做透视变换），再用正图识别。
     * 矫正失败不影响流程：退回原图继续识别。
     */
    private fun rectifyThenRecognize(file: File, shotMs: Long) {
        cameraExecutor.execute {
            val src = BitmapFactory.decodeFile(file.absolutePath)
            if (src == null) {
                runOnUiThread {
                    capturing = false
                    Toast.makeText(this, "照片解码失败，请重拍", Toast.LENGTH_SHORT).show()
                }
                return@execute
            }
            val tRect = System.currentTimeMillis()
            val rect = LabelRectifier.rectify(src)
            val rectMs = System.currentTimeMillis() - tRect
            Diag.event(
                "rectify",
                mapOf(
                    "ok" to rect.ok,
                    "note" to rect.note,
                    "ms" to rectMs,
                    "in_wh" to "${src.width}x${src.height}",
                    "out_wh" to "${rect.bitmap.width}x${rect.bitmap.height}",
                    "corners" to (rect.corners?.joinToString("|") { "${it.x.toInt()},${it.y.toInt()}" } ?: "-"),
                ),
            )
            // 把矫正后的正图落盘到相册：矫正效果只能靠肉眼看，
            // 若透视算错把图裁坏，识别必然为空，而数字上看不出原因。
            saveRectifiedPreview(rect.bitmap, file.nameWithoutExtension)

            // 用矫正后的图识别（条码通道 + OCR 通道都在这里跑）
            StillRecognizerBridge.recognizeBitmap(
                bitmap = rect.bitmap,
                onDone = { codes, ocrText ->
                    codesThisShot = codes
                    ocrThisShot = ocrText
                    parsed = parseShot()
                    Diag.event(
                        "still_recognized",
                        mapOf(
                            "codes" to codes.size,
                            "codes_raw" to codes.joinToString("|").take(300),
                            "ocr_len" to ocrText.length,
                            "ocr_head" to ocrText.replace("\n", " ").take(200),
                            "rectified" to rect.ok,
                            "material" to parsed?.materialCode.orEmpty(),
                            "sn_count" to (parsed?.serialNumbers?.size ?: 0),
                            "date" to parsed?.productionDate.orEmpty(),
                            "box" to parsed?.boxCode.orEmpty(),
                            "warnings" to parsed?.warnings?.joinToString(";").orEmpty(),
                        ),
                    )
                    runOnUiThread { enterReview() }
                },
                onFail = { msg ->
                    Diag.event("still_failed", mapOf("err" to msg))
                    runOnUiThread {
                        capturing = false
                        Toast.makeText(this, "识别失败：$msg（可重拍）", Toast.LENGTH_SHORT).show()
                    }
                },
            )
        }
    }

    /**
     * 静图识别 → 进入核对态。
     * 这是**唯一**的识别入口：一箱只识别一次，结果确定，不存在"还在扫"的中间态。
     */

    /** 单张解析（集成码分流：含分隔符的走 qrPayloads）。 */
    private fun parseShot(): BoxParseResultV2 {
        val qr = codesThisShot.filter { it.contains(',') || it.contains(';') }
        val plain = codesThisShot.filterNot { it in qr }
        val ocrLines = ocrThisShot.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        return LabelParserV2.parse(codes = plain, ocrLines = ocrLines, qrPayloads = qr)
    }

    // ===== 核对态 =====

    private fun enterReview() {
        phase = Phase.REVIEW
        capturing = false
        beep()

        // 核对期间不再需要实时分析：停掉分析器省电（预览画面保留）
        setAnalysisEnabled(false)

        val r = parsed
        binding.textParsed.text = buildString {
            appendLine("物料编码   ${r?.materialCode?.ifBlank { "—" } ?: "—"}")
            appendLine("序列号     ${r?.serialNumbers?.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "—"}")
            appendLine("生产日期   ${r?.productionDate?.ifBlank { "—" } ?: "—"}")
            appendLine("箱号       ${r?.boxCode?.ifBlank { "—" } ?: "—"}")
            val warns = r?.warnings.orEmpty()
            if (warns.isNotEmpty()) {
                appendLine()
                warns.forEach { appendLine("⚠️ $it") }
            }
            appendLine()
            append(if (r?.hasData == true) "核对无误后点「保存这一箱」" else "没读到内容，请点「重拍」或「补录」")
        }
        binding.textParsed.setTextColor(
            if (r?.warnings.isNullOrEmpty() && r?.hasData == true) 0xFF1B5E20.toInt() else 0xFFB71C1C.toInt()
        )
        binding.hint.text = "核对识别结果"
        setReviewButtons(true)
    }

    private fun backToPreview(reason: String) {
        val f = pendingFile
        if (f != null && f.exists()) f.delete()
        pendingFile = null
        codesThisShot = emptyList()
        ocrThisShot = ""
        parsed = null
        phase = Phase.PREVIEW
        capturing = false
        analyzer?.restart()
        setAnalysisEnabled(true)
        setReviewButtons(false)
        refreshPreviewHint()
        Diag.event("shot_discarded", mapOf("reason" to reason))
    }

    /** 核对态下：只留「保存 / 重拍 / 补录」，隐藏快门。 */
    private fun setReviewButtons(inReview: Boolean) {
        binding.btnShutter.visibility = if (inReview) View.GONE else View.VISIBLE
        binding.btnConfirm.visibility = if (inReview) View.VISIBLE else View.GONE
        binding.btnRescan.visibility = if (inReview) View.VISIBLE else View.GONE
    }

    private fun saveCurrentShot() {
        val r = parsed
        if (r == null || !r.hasData) {
            Toast.makeText(this, "没有可保存的数据", Toast.LENGTH_SHORT).show()
            return
        }
        val record = BoxRecordV2.from(r)
        tray.addBox(record)
        Diag.event(
            "box_saved",
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
        pendingFile?.let { if (it.exists()) it.delete() }
        pendingFile = null
        refreshTrayLine()
        Toast.makeText(this, "已保存（本托盘 ${tray.totalBoxes} 箱 / ${tray.totalUnits} 件）", Toast.LENGTH_SHORT).show()
        backToPreview("保存后进入下一张")
    }

    private fun refreshTrayLine() {
        binding.trayStat.text = "本托盘：${tray.totalBoxes} 箱 / ${tray.totalUnits} 件"
    }

    private fun setAnalysisEnabled(enabled: Boolean) {
        // 核对期间停分析（省电）；预览画面保持不动，便于对照实物。
        val a = analyzer ?: return
        if (enabled) a.resume() else a.pause()
    }

    private fun showManualInput() {
        val input = android.widget.EditText(this)
        AlertDialog.Builder(this)
            .setTitle("手工补录")
            .setMessage("每行一个值：物料编码 / 序列号 / 日期 / 箱号 都可以")
            .setView(input)
            .setPositiveButton("用这些值核对") { _, _ ->
                codesThisShot = input.text.toString()
                    .split('\n', ',', ';').map { it.trim() }.filter { it.isNotEmpty() }
                ocrThisShot = ""
                parsed = parseShot()
                Diag.event("manual_input", mapOf("lines" to codesThisShot.size))
                enterReview()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun finishTray() {
        Diag.event(
            "tray_finished",
            mapOf("boxes" to tray.totalBoxes, "units" to tray.totalUnits, "rows" to tray.totalRows),
        )
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
        pendingFile?.let { if (it.exists()) it.delete() }
        analyzer?.close()
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}
