package com.anglesgirl.labelscanner.camera

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.anglesgirl.labelscanner.R
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Shared camera workflow for label sampling, live code-page sweeps, and manual photo capture. */
class CaptureActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_OUTPUT_URI = "capture_output_uri"
        const val EXTRA_CAPTURE_MODE = "capture_mode"
    }
    private val captureMode: CameraCaptureMode by lazy {
        val name = intent.getStringExtra(EXTRA_CAPTURE_MODE)
        CameraCaptureMode.values().firstOrNull { it.name == name } ?: CameraCaptureMode.PHOTO_ONLY
    }
    private lateinit var previewView: PreviewView
    private lateinit var tvStatus: TextView
    private lateinit var manualButton: Button
    private lateinit var retryButton: Button
    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var analyzer: LiveLabelAnalyzer? = null
    private var codeAnalyzer: LiveCodePageAnalyzer? = null
    private var trayAnalyzer: LiveTrayAnalyzer? = null
    private var torchControl: com.anglesgirl.labelscanner.util.TorchControl? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var captureStarted = false
    private var timedOut = false
    private var barcodeFound = false
    private var coreFocusUsed = false
    private var sampleRound = 0
    private var evidence: BurstConsensus.Result? = null
    private var usbMode = false
    private var resumed = false
    private var sourceRunning = false
    private var sourceGeneration = 0
    private var usbController: UsbCameraController? = null
    private var lastUsbFrame: android.graphics.Bitmap? = null
    private lateinit var usbView: com.anglesgirl.labelscanner.camera.UsbCameraPreviewView
    private lateinit var sourceButton: Button

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) previewView.post { startCamera() } else fail("需要相机权限")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
        setContentView(R.layout.activity_capture)
        previewView = findViewById(R.id.pvCapture)
        tvStatus = findViewById(R.id.tvCaptureStatus)
        usbView = findViewById(R.id.usbCaptureView)
        sourceButton = findViewById(R.id.btnCaptureSource)
        usbMode = com.anglesgirl.labelscanner.util.CameraPrefs.isUsb(this)
        sourceButton.setOnClickListener { chooseSource() }
        manualButton = findViewById(R.id.btnCapture)
        retryButton = findViewById(R.id.btnCaptureRetry)
        manualButton.text = captureMode.captureButtonText
        if (captureMode == CameraCaptureMode.LIST_CODES) {
            retryButton.text = "整页拍照兜底"
            retryButton.visibility = View.VISIBLE
        }
        findViewById<TextView>(R.id.tvLastSavedSerial).visibility =
            if (captureMode.showLastSavedSerial) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.btnCaptureCancel).setOnClickListener { finish() }
        manualButton.setOnClickListener {
            if (!captureStarted) {
                if (captureMode == CameraCaptureMode.LIST_CODES) finishCodePage()
                else {
                    analyzer?.pause()
                    trayAnalyzer?.pause()
                    savePhoto()
                }
            }
        }
        retryButton.setOnClickListener {
            if (captureMode == CameraCaptureMode.LIST_CODES) {
                if (!captureStarted) {
                    codeAnalyzer?.pause()
                    savePhoto()
                }
            } else if (evidence != null) savePhoto() else restartSampling()
        }
        torchControl = com.anglesgirl.labelscanner.util.TorchControl(this,
            findViewById<View>(R.id.btnTorch), findViewById(R.id.sbTorchBrightness))
        requestCamera.launch(android.Manifest.permission.CAMERA)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (captureMode.showLastSavedSerial) {
            com.anglesgirl.labelscanner.util.LastSavedHint.bind(this, findViewById(R.id.tvLastSavedSerial))
        }
        previewView.post { startCamera() }
    }

    override fun onStop() {
        resumed = false
        stopSource()
        super.onStop()
    }

    private fun chooseSource() {
        if (captureStarted) return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("切换摄像头")
            .setSingleChoiceItems(arrayOf("手机摄像头", "USB 摄像头"), if (usbMode) 1 else 0) { dialog, which ->
                dialog.dismiss()
                val nextUsb = which == 1
                if (nextUsb == usbMode && sourceRunning) return@setSingleChoiceItems
                stopSource()
                usbMode = nextUsb
                com.anglesgirl.labelscanner.util.CameraPrefs.set(this,
                    if (usbMode) com.anglesgirl.labelscanner.util.CameraPrefs.SOURCE_USB
                    else com.anglesgirl.labelscanner.util.CameraPrefs.SOURCE_PHONE)
                restartSampling()
                startCamera()
            }.setNegativeButton("取消", null).show()
    }

    private fun stopSource() {
        sourceGeneration++
        sampleRound++
        sourceRunning = false
        usbController?.stop()
        usbController = null
        analyzer?.close()
        analyzer = null
        codeAnalyzer?.close()
        codeAnalyzer = null
        trayAnalyzer?.close()
        trayAnalyzer = null
        torchControl?.onDestroy()
        cameraProvider?.unbindAll()
        camera = null
        imageCapture = null
        lastUsbFrame?.recycle()
        lastUsbFrame = null
    }

    private fun startCamera() {
        if (!resumed || sourceRunning || captureStarted || isFinishing || isDestroyed) return
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        sourceRunning = true
        val token = ++sourceGeneration
        sourceButton.text = if (usbMode) "USB 摄像头 · 切换" else "手机摄像头 · 切换"
        previewView.visibility = if (usbMode) View.GONE else View.VISIBLE
        usbView.visibility = if (usbMode) View.VISIBLE else View.GONE
        restartSampling()
        if (!usbMode) {
            previewView.post { if (token == sourceGeneration) startPhoneCamera(token) }
            return
        }
        torchControl?.disableForUsbCamera()
        if (captureMode == CameraCaptureMode.LIST_CODES) createCodeAnalyzer(token)
        else if (captureMode == CameraCaptureMode.LIST_TRAY) createTrayAnalyzer(token)
        else if (captureMode.usesLiveSampling) createAnalyzer(token)
        usbController = UsbCameraController(this, usbView,
            onStatus = { message ->
                if (token == sourceGeneration && !captureStarted) {
                    lastUsbFrame?.recycle()
                    lastUsbFrame = null
                    if (usbController?.isConnected != true) restartSampling()
                    tvStatus.text = message
                }
            },
            onFrame = { bitmap ->
                if (token != sourceGeneration || !active()) bitmap.recycle()
                else {
                    lastUsbFrame?.recycle()
                    lastUsbFrame = bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                    when {
                        codeAnalyzer != null -> codeAnalyzer?.analyzeBitmap(bitmap)
                        trayAnalyzer != null -> trayAnalyzer?.analyzeBitmap(bitmap)
                        analyzer != null -> analyzer?.analyzeBitmap(bitmap)
                        else -> bitmap.recycle()
                    }
                }
            }).also { it.start() }
    }

    private fun startPhoneCamera(token: Int) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (token != sourceGeneration || !resumed || usbMode || isFinishing || isDestroyed) return@addListener
            try {
                val provider = future.get()
                cameraProvider = provider
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setResolutionSelector(resolution(3840, 2160))
                    .setJpegQuality(95).build()
                imageCapture = capture
                provider.unbindAll()
                val groupBuilder = UseCaseGroup.Builder().addUseCase(preview).addUseCase(capture)
                if (captureMode.usesLiveSampling || captureMode == CameraCaptureMode.LIST_TRAY) {
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setResolutionSelector(resolution(1920, 1080)).build()
                    val frameAnalyzer = when (captureMode) {
                        CameraCaptureMode.LIST_CODES -> createCodeAnalyzer(token)
                        CameraCaptureMode.LIST_TRAY -> createTrayAnalyzer(token)
                        else -> createAnalyzer(token)
                    }
                    analysis.setAnalyzer(analysisExecutor, frameAnalyzer)
                    groupBuilder.addUseCase(analysis)
                }
                val group = groupBuilder
                    .setViewPort(requireNotNull(previewView.viewPort) { "取景画面尚未就绪，请重新打开相机" }).build()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, group)
                torchControl?.attach(camera)
                tvStatus.text = initialHint()
                // One initial focus plus at most one core-field focus, neither gates recognition.
                handler.postDelayed({ if (token == sourceGeneration && active() && !coreFocusUsed) focusAt(0.5f, 0.5f) }, 450)
            } catch (e: Exception) { fail("相机启动失败：${e.message}") }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun createTrayAnalyzer(token: Int): LiveTrayAnalyzer {
        val live = LiveTrayAnalyzer(
            onProgress = { progress -> runOnUiThread {
                if (token != sourceGeneration || !active()) return@runOnUiThread
                tvStatus.text = when {
                    progress.preferredCode != null -> "托盘候选 ${progress.preferredCode}：稳定确认 ${progress.matchingCount}/${LiveTrayConsensus.REQUIRED_MATCHES} 帧；保持对准…"
                    progress.candidateCount > 0 -> "发现 ${progress.candidateCount} 个托盘候选，暂不能唯一确定；请靠近并对准托盘码，或手动拍照后选择。"
                    else -> "正在实时识别托盘条码；请让托盘码完整入框并保持清晰，识别稳定后会自动拍照。下方可手动拍照兜底。"
                }
            } },
            onSuccess = { code -> runOnUiThread {
                if (token != sourceGeneration || !active()) return@runOnUiThread
                tvStatus.text = "托盘码 $code 已稳定识别，正在自动拍照…"
                savePhoto()
            } },
        )
        trayAnalyzer = live
        return live
    }

    private fun createAnalyzer(token: Int): LiveLabelAnalyzer {
        val live = LiveLabelAnalyzer(
                    onBarcode = { runOnUiThread {
                        if (token != sourceGeneration || !active()) return@runOnUiThread
                        barcodeFound = true
                        val fields = if (captureMode == CameraCaptureMode.LIST_HEADER) "物料编码和生产日期" else "物料、生产日期和序列号"
                        tvStatus.text = "已识别条码，开始采样$fields；相同数据累计 ${LiveSampleConsensus.REQUIRED_MATCHES} 次即确认"
                        // Bounded round even when the user moves away or a native OCR call stalls.
                        val round = sampleRound
                        handler.postDelayed({
                            if (round == sampleRound && active()) analyzer?.requestTimeout()
                        }, 15_000)
                    } },
                    onFocusHint = { x, y -> runOnUiThread {
                        if (token == sourceGeneration && active() && !coreFocusUsed) {
                            coreFocusUsed = true
                            focusAt(x, y)
                        }
                    } },
                    onProgress = { progress -> runOnUiThread {
                        if (token != sourceGeneration || !active()) return@runOnUiThread
                        tvStatus.text = if (captureMode == CameraCaptureMode.LIST_HEADER) {
                            "物料+日期有效读数 ${progress.matchingCount}/${LiveSampleConsensus.REQUIRED_MATCHES} · 已采样 ${progress.sampleCount} 帧\n请同时对准终端物料编号和日期；模糊帧不清零"
                        } else {
                            "正在采样物料、日期和序列号：相同数据 ${progress.matchingCount}/${LiveSampleConsensus.REQUIRED_MATCHES} 次（已采样 ${progress.sampleCount} 帧）；手抖不清零"
                        }
                    } },
                    onSuccess = { result -> runOnUiThread {
                        if (token != sourceGeneration || !active()) return@runOnUiThread
                        evidence = result
                        sampleRound++
                        tvStatus.text = if (captureMode == CameraCaptureMode.LIST_HEADER) {
                            "物料和生产日期已累计 ${LiveSampleConsensus.REQUIRED_MATCHES} 次一致，正在保存…"
                        } else {
                            "同一标签数据已累计 ${LiveSampleConsensus.REQUIRED_MATCHES} 次一致，识别成功，正在保存…"
                        }
                        savePhoto()
                    } },
                    onTimeout = { runOnUiThread { if (token == sourceGeneration && active()) samplingTimedOut() } },
                    requireBarcodeBeforeOcr = captureMode != CameraCaptureMode.LIST_HEADER,
                    useChineseTextRecognition = captureMode == CameraCaptureMode.LIST_HEADER,
                )
        analyzer = live
        return live
    }

    private fun createCodeAnalyzer(token: Int): LiveCodePageAnalyzer {
        val live = LiveCodePageAnalyzer { progress -> runOnUiThread {
            if (token != sourceGeneration || !active()) return@runOnUiThread
            val pending = progress.pending.lastOrNull()
            tvStatus.text = if (pending == null) {
                "本页已确认 ${progress.confirmedCodes.size} 个\n请自上而下慢扫；完成本页后回到总清单"
            } else {
                "本页已确认 ${progress.confirmedCodes.size} 个 · 当前码 ${pending.reads}/${LiveCodePageConsensus.REQUIRED_READS} 次\n请自上而下慢扫；模糊帧不清空已确认码"
            }
        } }
        codeAnalyzer = live
        return live
    }

    private fun finishCodePage() {
        val live = codeAnalyzer ?: return
        captureStarted = true
        manualButton.isEnabled = false
        retryButton.isEnabled = false
        tvStatus.text = "正在整理本页已稳定识别的集成码…"
        live.finish { codes -> runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            if (codes.isEmpty()) {
                captureStarted = false
                manualButton.isEnabled = true
                retryButton.isEnabled = true
                live.reset()
                tvStatus.text = "本页还没有确认到集成码；请自上而下慢速扫描，或用“整页拍照兜底”。"
                return@runOnUiThread
            }
            try {
                val uri = LiveCodePageEvidence.attach(this, codes)
                setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_OUTPUT_URI, uri.toString()))
                finish()
            } catch (e: Exception) {
                captureStarted = false
                manualButton.isEnabled = true
                retryButton.isEnabled = true
                live.reset()
                tvStatus.text = "本页识别结果暂存失败，请重试：${e.message.orEmpty()}"
            }
        } }
    }

    private fun resolution(width: Int, height: Int) =
        androidx.camera.core.resolutionselector.ResolutionSelector.Builder().setResolutionStrategy(
            androidx.camera.core.resolutionselector.ResolutionStrategy(android.util.Size(width, height),
                androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
        ).build()

    private fun initialHint() = captureMode.instruction
    private fun active() = !captureStarted && !timedOut && !isFinishing && !isDestroyed

    private fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        val point = previewView.meteringPointFactory.createPoint(previewView.width * x, previewView.height * y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(3, TimeUnit.SECONDS).build()
        if (cam.cameraInfo.isFocusMeteringSupported(action)) {
            runCatching { cam.cameraControl.startFocusAndMetering(action) }
        }
    }

    private fun samplingTimedOut() {
        if (!active()) return
        timedOut = true
        analyzer?.pause()
        retryButton.text = "重新采样"
        retryButton.visibility = View.VISIBLE
        tvStatus.text = "本轮尚未累计 ${LiveSampleConsensus.REQUIRED_MATCHES} 次相同完整数据，未标记成功。请调整距离或避开反光后重试，也可手动拍照核对"
    }

    private fun restartSampling() {
        sampleRound++
        evidence = null
        captureStarted = false
        timedOut = false
        barcodeFound = false
        coreFocusUsed = false
        manualButton.isEnabled = true
        retryButton.visibility = if (captureMode == CameraCaptureMode.LIST_CODES) View.VISIBLE else View.GONE
        retryButton.isEnabled = true
        tvStatus.text = initialHint()
        analyzer?.reset()
        codeAnalyzer?.reset()
        trayAnalyzer?.reset()
        focusAt(0.5f, 0.5f)
    }

    private fun savePhoto() {
        if (captureStarted || isFinishing || isDestroyed) return
        if (usbMode) {
            saveUsbPhoto()
            return
        }
        val capture = imageCapture ?: return
        captureStarted = true
        manualButton.isEnabled = false
        retryButton.visibility = View.GONE
        analyzer?.pause()
        codeAnalyzer?.pause()
        trayAnalyzer?.pause()
        val stableResult = evidence
        if (stableResult == null) {
            tvStatus.text = if (captureMode.usesLiveSampling) {
                "正在手动拍照，随后请核对识别结果…"
            } else {
                "正在拍照，返回后识别本页内容…"
            }
        }
        val file = File(cacheDir, "captures/capture_${System.currentTimeMillis()}.jpg").also { it.parentFile?.mkdirs() }
        capture.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    if (isDestroyed || isFinishing) return
                    try {
                        // Retain the whole HD image; never crop to an unstable content rectangle.
                        val photo = FileProvider.getUriForFile(this@CaptureActivity, "$packageName.fileprovider", file)
                        val uri = if (stableResult == null) photo else LiveSampleEvidence.attach(this@CaptureActivity, photo, stableResult)
                        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_OUTPUT_URI, uri.toString()))
                        finish()
                    } catch (e: Exception) { saveFailed("采样结果保存失败，请重新扫描") }
                }
                override fun onError(exception: ImageCaptureException) {
                    file.delete()
                    saveFailed("拍照失败，请重试：${exception.message}")
                }
            })
    }

    private fun saveUsbPhoto() {
        val snapshot = lastUsbFrame?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
        if (snapshot == null || usbController?.isConnected != true) {
            saveFailed("USB 摄像头尚未输出画面，请检查连接和授权，或切换手机摄像头")
            return
        }
        captureStarted = true
        manualButton.isEnabled = false
        retryButton.visibility = View.GONE
        analyzer?.pause()
        codeAnalyzer?.pause()
        trayAnalyzer?.pause()
        val stableResult = evidence
        val file = File(cacheDir, "captures/usb_${System.currentTimeMillis()}.jpg").also { it.parentFile?.mkdirs() }
        analysisExecutor.execute {
            val saved = runCatching {
                file.outputStream().use { check(snapshot.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it)) }
            }
            snapshot.recycle()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (saved.isFailure) {
                    file.delete()
                    saveFailed("USB 画面保存失败，请重试")
                } else {
                    try {
                        val photo = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                        val uri = if (stableResult == null) photo else LiveSampleEvidence.attach(this, photo, stableResult)
                        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_OUTPUT_URI, uri.toString()))
                        finish()
                    } catch (_: Exception) {
                        saveFailed("USB 采样结果保存失败，请重试")
                    }
                }
            }
        }
    }

    private fun saveFailed(message: String) {
        captureStarted = false
        timedOut = true
        retryButton.text = if (evidence != null) "重试保存（保留已确认数据）" else "重新采样"
        retryButton.visibility = View.VISIBLE
        manualButton.isEnabled = true
        tvStatus.text = message
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun fail(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    override fun onDestroy() {
        sampleRound++
        handler.removeCallbacksAndMessages(null)
        stopSource()
        analysisExecutor.shutdown()
        camera = null
        super.onDestroy()
    }
}
