package com.anglesgirl.labelscanner

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors

/**
 * 连续采样识码：相机连续取帧做 OCR，同一物料编码在多帧里出现就计数，
 * 多次命中的标出来，用户手动点选一个填回登记表单。
 *
 * 适用场景：来料标识卡只有印的 SAP 编码、没有条码，条码扫码识别不出来。
 * 识别全离线（ML Kit 本地 latin 识别器，数字码够用），不联网、不调云端 OCR。
 *
 * 链路：点选 → CandidateNormalizer.applyWithCheck 校验/修改 →
 * RESULT_OK + LiveScanActivity.EXTRA_RESULT_CODE（单个值）→
 * LedgerRegisterActivity 的 scanLauncher 现成链路接住并查库存。
 */
class OcrPickActivity : AppCompatActivity() {

    private data class CandidateRow(val code: String, val count: Int)

    companion object {
        /** OCR 节流：约每 450ms 分析一帧，别每帧都 OCR 拖垮手机。 */
        private const val ANALYZE_INTERVAL_MS = 450L
        /** 命中次数达到该值即标为多次命中。 */
        private const val MULTI_HIT_THRESHOLD = 3
        /** OCR 常见误认归一（只认这几种，不多 invent）。 */
        private val CONFUSION: Map<Char, Char> = mapOf(
            'O' to '0',
            'o' to '0',
            'l' to '1',
            'I' to '1',
            'S' to '5',
            'Z' to '2',
        )
        /** 候选编码：连续 10-13 位数字或易混淆字母。 */
        private val CODE_RUN = Regex("[0-9OoIlSZ]{10,13}")
    }

    private lateinit var previewView: PreviewView
    private lateinit var tvFrames: TextView
    private lateinit var tvHint: TextView
    private lateinit var listView: ListView
    private val rows = mutableListOf<CandidateRow>()
    private lateinit var adapter: ArrayAdapter<CandidateRow>

    /** 归一化编码 → 跨帧累计次数（LinkedHashMap 保持首次出现顺序）。 */
    private val counts = LinkedHashMap<String, Int>()
    private var frameCount = 0
    private var lastAnalyzeMs = 0L
    private var processing = false

    private var recognizer: TextRecognizer? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            Toast.makeText(this, "需要相机权限", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_ocr_pick)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        previewView = findViewById(R.id.pvOcr)
        tvFrames = findViewById(R.id.tvOcrFrames)
        tvHint = findViewById(R.id.tvOcrHint)
        listView = findViewById(R.id.lvOcrCandidates)

        adapter = object : ArrayAdapter<CandidateRow>(this, 0, rows) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: LayoutInflater.from(context)
                    .inflate(R.layout.item_ocr_candidate, parent, false)
                val row = getItem(position) ?: return view
                val tvCode = view.findViewById<TextView>(R.id.tvCandidateCode)
                val tvCount = view.findViewById<TextView>(R.id.tvCandidateCount)
                tvCode.text = row.code
                if (row.count >= MULTI_HIT_THRESHOLD) {
                    tvCount.text = "✓ 多次命中 · ${row.count} 次"
                    tvCount.setTextColor(ContextCompat.getColor(context, R.color.ls_ok))
                } else {
                    tvCount.text = "${row.count} 次"
                    tvCount.setTextColor(ContextCompat.getColor(context, R.color.ls_text_dim))
                }
                return view
            }
        }
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            val row = rows.getOrNull(position) ?: return@setOnItemClickListener
            CandidateNormalizer.applyWithCheck(this, row.code, "material") { value ->
                setResult(
                    RESULT_OK,
                    Intent().putExtra(LiveScanActivity.EXTRA_RESULT_CODE, value),
                )
                finish()
            }
        }

        findViewById<Button>(R.id.btnOcrCancel).setOnClickListener { finish() }

        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestCamera.launch(android.Manifest.permission.CAMERA)
        }
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
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExecutor) { imageProxy ->
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
        val now = SystemClock.elapsedRealtime()
        if (processing || now - lastAnalyzeMs < ANALYZE_INTERVAL_MS) {
            imageProxy.close()
            return
        }
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val recognizer = this.recognizer
        if (recognizer == null) {
            imageProxy.close()
            return
        }
        processing = true
        lastAnalyzeMs = now
        val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        try {
            recognizer.process(inputImage).addOnCompleteListener { task ->
                try {
                    if (task.isSuccessful) {
                        val found = extractCandidates(task.result?.text.orEmpty())
                        // 同一归一化值每帧最多计 1 次。
                        for (code in found) {
                            counts[code] = (counts[code] ?: 0) + 1
                        }
                    }
                } finally {
                    frameCount++
                    if (!isFinishing && !isDestroyed) {
                        runOnUiThread { refreshList() }
                    }
                    imageProxy.close()
                    processing = false
                }
            }
        } catch (e: Exception) {
            imageProxy.close()
            processing = false
        }
    }

    /**
     * 每帧 OCR 文本里提取候选编码：连续 10-13 位数字/易混淆字母串，
     * 误认归一后再用 CandidateNormalizer.validate 校验。
     * 只有 10/12 位纯数字才算物料候选；13 位（含 69 开头）按 69 码处理，不当物料。
     */
    private fun extractCandidates(text: String): Set<String> {
        val out = LinkedHashSet<String>()
        for (match in CODE_RUN.findAll(text)) {
            val mapped = match.value.map { CONFUSION[it] ?: it }.joinToString("")
            if ((mapped.length == 10 || mapped.length == 12) &&
                CandidateNormalizer.validate(mapped, "material") == null
            ) {
                out.add(mapped)
            }
        }
        return out
    }

    /** 列表按次数降序实时刷新（stable 排序，次数相同按首次出现顺序）。 */
    private fun refreshList() {
        tvFrames.text = "已采样 $frameCount 帧"
        val sorted = counts.entries.sortedByDescending { it.value }
        rows.clear()
        rows.addAll(sorted.map { CandidateRow(it.key, it.value) })
        adapter.notifyDataSetChanged()
        tvHint.visibility = if (counts.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onDestroy() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        recognizer?.close()
        recognizer = null
        analysisExecutor.shutdownNow()
        super.onDestroy()
    }
}
