package com.anglesgirl.labelscanner.camera

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.Camera
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.anglesgirl.labelscanner.R
import java.io.File
import java.util.concurrent.TimeUnit

/** 可控拍照页：持续自动对焦，拍照前等待一次对焦结果再保存。 */
class CaptureActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_OUTPUT_URI = "capture_output_uri"
    }

    private lateinit var previewView: PreviewView
    private lateinit var tvStatus: TextView
    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var captureStarted = false

    private val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else fail("需要相机权限")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_capture)
        previewView = findViewById(R.id.pvCapture)
        tvStatus = findViewById(R.id.tvCaptureStatus)
        findViewById<Button>(R.id.btnCaptureCancel).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnCapture).setOnClickListener { captureAfterFocus() }
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
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setJpegQuality(95)
                    .build()
                imageCapture = capture
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture
                )
                tvStatus.text = "对准标签，等待清晰后拍照"
            } catch (e: Exception) {
                fail("相机启动失败: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun captureAfterFocus() {
        if (captureStarted) return
        val capture = imageCapture ?: return
        captureStarted = true
        findViewById<Button>(R.id.btnCapture).isEnabled = false
        tvStatus.text = "正在对焦..."
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
                    setResult(
                        Activity.RESULT_OK,
                        Intent().putExtra(EXTRA_OUTPUT_URI, uri.toString())
                    )
                    finish()
                }

                override fun onError(exception: ImageCaptureException) {
                    captureStarted = false
                    findViewById<Button>(R.id.btnCapture).isEnabled = true
                    fail("拍照失败: ${exception.message}")
                }
            }
        )
    }

    private fun fail(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    override fun onDestroy() {
        cameraProvider?.unbindAll()
        camera = null
        super.onDestroy()
    }
}