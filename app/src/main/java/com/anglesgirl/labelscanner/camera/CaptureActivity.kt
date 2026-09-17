package com.anglesgirl.labelscanner.camera

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.Camera
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.anglesgirl.labelscanner.R
import java.io.File
import java.util.concurrent.TimeUnit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/** 可控拍照页：持续自动对焦，拍照前等待一次对焦结果再保存。 */
class CaptureActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_OUTPUT_URI = "capture_output_uri"
    }

    private lateinit var previewView: PreviewView
    private lateinit var tvStatus: TextView
    private lateinit var alignmentFrame: View
    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var torchControl: com.anglesgirl.labelscanner.util.TorchControl? = null
    private var alignmentAnalyzer: CaptureAlignmentAnalyzer? = null
    private var captureStarted = false

    private val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else fail("需要相机权限")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge：与其它页面保持一致（沉浸式 + 避让系统栏），
        // 否则顶部/底部按钮会被状态栏、导航栏压住。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
        setContentView(R.layout.activity_capture)
        previewView = findViewById(R.id.pvCapture)
        tvStatus = findViewById(R.id.tvCaptureStatus)
        alignmentFrame = findViewById(R.id.captureAlignmentFrame)
        findViewById<Button>(R.id.btnCaptureCancel).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnCapture).setOnClickListener { captureAfterFocus() }
        // 补光灯：手机摄像头可用(有闪光灯时显示)
        torchControl = com.anglesgirl.labelscanner.util.TorchControl(
            this,
            findViewById<android.view.View>(R.id.btnTorch),
            findViewById<android.widget.SeekBar>(R.id.sbTorchBrightness),
        )
        requestCamera.launch(android.Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val capture = ImageCapture.Builder()
                    // 【画质优先】之前为治"出图慢半拍"用了 MINIMIZE_LATENCY，但那会走
                    // "零快门延迟"路径 —— **直接复用预览帧出图**，而预览帧分辨率往往
                    // 只有 1080p 甚至更低，于是照片呈现"像素不够的模糊、低画质"。
                    // 用户对比 ML Kit 文档扫描（系统级、自己出高清图）后指出这一点。
                    //
                    // 改回 MAXIMIZE_QUALITY：多帧合成出真正的高清图。代价是快门后多等
                    // 几百毫秒，但"拍不清"比"慢一点"严重得多，且 ML Kit 那条路也不是瞬出。
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setResolutionSelector(
                        androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                androidx.camera.core.resolutionselector.ResolutionStrategy(
                                    android.util.Size(3840, 2160),
                                    androidx.camera.core.resolutionselector.ResolutionStrategy
                                        .FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                )
                            )
                            .build()
                    )
                    .setJpegQuality(95)
                    .build()
                imageCapture = capture
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                alignmentAnalyzer = CaptureAlignmentAnalyzer(
                    onState = { state -> runOnUiThread { updateAlignmentState(state) } },
                    onStable = {
                        runOnUiThread {
                            if (!captureStarted) captureAfterFocus(auto = true)
                        }
                    },
                ).also { analyzer ->
                    analysis.setAnalyzer(ContextCompat.getMainExecutor(this), analyzer)
                }
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture, analysis
                )
                torchControl?.attach(camera)
                tvStatus.text = "将标签放入框内，保持稳定即可自动拍照"
            } catch (e: Exception) {
                fail("相机启动失败: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun captureAfterFocus(auto: Boolean = false) {
        if (captureStarted) return
        val capture = imageCapture ?: return
        captureStarted = true
        findViewById<Button>(R.id.btnCapture).isEnabled = false
        tvStatus.text = if (auto) "已对齐，正在自动拍照..." else "正在对焦..."
        val point = previewView.meteringPointFactory.createPoint(
            previewView.width / 2f, previewView.height / 2f
        )
        val action = FocusMeteringAction.Builder(point)
            .setAutoCancelDuration(5, TimeUnit.SECONDS)
            .build()
        val focusFuture = camera?.cameraControl?.startFocusAndMetering(action)
        if (focusFuture == null) {
            takePicture(capture)
            return
        }
        focusFuture.addListener(
            { takePicture(capture) },
            ContextCompat.getMainExecutor(this)
        )
    }

    private fun takePicture(capture: ImageCapture) {
        val file = File(cacheDir, "captures/capture_${System.currentTimeMillis()}.jpg")
            .also { it.parentFile?.mkdirs() }
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    // 拿到清晰图后**先矫正成正图**，再交给静态识别。
                    //
                    // 阶段分工（用户明确要求）：
                    //   实时阶段 —— 只做文档模式对准 + 拍照时机，要轻要快（不跑 OCR/不重活）；
                    //   静态阶段 —— 取到图之后才做重活：矫正 → OCR + 扫码。
                    // 所以矫正放在这里（已经拍完了），不影响取景流畅度。
                    tvStatus.text = "正在校正标签..."
                    Thread {
                        // 矫正失败返回 null，此时回退原图继续识别 —— 矫正只为提升识别率，不该阻断流程。
                        val outUri = rectifyToCache(file) ?: uri
                        runOnUiThread {
                            setResult(
                                Activity.RESULT_OK,
                                Intent().putExtra(EXTRA_OUTPUT_URI, outUri.toString())
                            )
                            finish()
                        }
                    }.start()
                }

                override fun onError(exception: ImageCaptureException) {
                    captureStarted = false
                    findViewById<Button>(R.id.btnCapture).isEnabled = true
                    fail("拍照失败: ${exception.message}")
                }
            }
        )
    }

    /**
     * 按 EXIF 方向把图转正。
     *
     * 为什么必须做（用户报"拍照和文档模式现在不处理 y 轴"）：
     * 相机写出的 JPEG 物理像素常常是**传感器方向**（横向），真实方向记在 EXIF 里。
     * `BitmapFactory.decodeFile / decodeFileDescriptor` **不会**读取 EXIF，
     * 于是解码出来的图是横的 —— 取景框看着是竖的，矫正器和识别器拿到的却是横图，
     * 表现为"y 轴方向不对/上下颠倒/裁错位"。
     */
    private fun applyExifRotation(src: File, bmp: android.graphics.Bitmap): android.graphics.Bitmap =
        runCatching {
            val exif = android.media.ExifInterface(src.absolutePath)
            val deg = when (
                exif.getAttributeInt(
                    android.media.ExifInterface.TAG_ORIENTATION,
                    android.media.ExifInterface.ORIENTATION_NORMAL,
                )
            ) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
            if (deg == 0) bmp
            else android.graphics.Bitmap.createBitmap(
                bmp, 0, 0, bmp.width, bmp.height,
                android.graphics.Matrix().apply { postRotate(deg.toFloat()) },
                true,
            )
        }.getOrDefault(bmp)

    /**
     * 自动找边 + 透视矫正，把正图写到 cache 并返回其 URI。
     *
     * 返回 null 表示没矫正成功（比如画面里找不到标签边界）—— 调用方回退用原图，
     * 流程照常继续。矫正只是为了提升后续 OCR/扫码的命中率，不该成为新的失败点。
     */
    private fun rectifyToCache(src: File): Uri? = runCatching {
        val raw = android.graphics.BitmapFactory.decodeFile(src.absolutePath)
            ?: return@runCatching null
        // 先按 EXIF 转正再矫正：否则是在横图上找标签边界，必然找错、裁错。
        val bmp = applyExifRotation(src, raw)
        val result = LabelRectifier.rectify(bmp)
        if (!result.ok) return@runCatching null
        val out = File(cacheDir, "captures/rectified_${System.currentTimeMillis()}.jpg")
            .also { it.parentFile?.mkdirs() }
        out.outputStream().use {
            result.bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it)
        }
        FileProvider.getUriForFile(this, "$packageName.fileprovider", out)
    }.getOrNull()

    private fun fail(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    private fun updateAlignmentState(state: CaptureAlignmentAnalyzer.AlignmentState) {
        if (captureStarted) return
        val ready = state == CaptureAlignmentAnalyzer.AlignmentState.STABLE
        alignmentFrame.setBackgroundResource(
            if (ready) R.drawable.bg_scan_frame_ready else R.drawable.bg_scan_frame
        )
        when (state) {
            CaptureAlignmentAnalyzer.AlignmentState.SEARCHING -> tvStatus.text = "请将标签对准取景框"
            CaptureAlignmentAnalyzer.AlignmentState.MOVE_CLOSER -> tvStatus.text = "请靠近一些，让标签更清晰"
            CaptureAlignmentAnalyzer.AlignmentState.CENTERED -> tvStatus.text = "位置合适，请保持稳定"
            CaptureAlignmentAnalyzer.AlignmentState.STABLE -> tvStatus.text = "已对齐，准备拍照"
        }
    }

    override fun onDestroy() {
        torchControl?.onDestroy()
        torchControl = null
        alignmentAnalyzer?.close()
        alignmentAnalyzer = null
        cameraProvider?.unbindAll()
        camera = null
        super.onDestroy()
    }
}