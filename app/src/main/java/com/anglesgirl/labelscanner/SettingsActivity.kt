package com.anglesgirl.labelscanner

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.anglesgirl.labelscanner.data.Turso69Client
import java.util.concurrent.Executors

/**
 * ⚙️ 设置：69 码反查数据库（Turso libsql）连接参数。
 * 内部使用不对外：连接地址 + token 由用户手动填写（不内置在 App）。
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        const val PREFS = "turso_config"
        const val KEY_URL = "turso_url"
        const val KEY_TOKEN = "turso_token"

        /**
         * 内置默认反查库（内部使用，长期维护 69 码→物料映射）。
         * 设置页未填写时使用内置值 → 反查开箱即用，无需手动配置。
         * 设置页可覆盖（换库/测试用）。
         */
        private const val DEFAULT_URL =
            "https://5omr56cb5ywl5bqt-anglesgirl.aws-ap-northeast-1.turso.io"
        private const val DEFAULT_TOKEN =
            "eyJhbGciOiJFZERTQSIsInR5cCI6IkpXVCJ9.eyJhIjoicnciLCJpYXQiOjE3ODY0MzI2MDAsImlkIjoiMDE5ZmVmYWQtNzcwMS03OTdiLThlNDMtOTAyNzNhYWI1OTNjIiwia2lkIjoidWFjWFBDcGRfRmRwLS1DSTA1ckdfYlM2enBEVm5NU1B3U0oxWFhHRUt3ayIsInJpZCI6ImJmMDU5MjA3LWNhMjMtNDkzOS04YWViLTEyYzFkZjNhY2ZkMiJ9.FUrHSAb-64nHNVqe1GNXcB-8w5dTx0efxXy9srdFHTjeTQwMjLHy8Y82HghmlIKZnLw5ItZf5Rs1_JM4IeQzAg"

        fun getUrl(context: Context): String {
            val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_URL, "").orEmpty().trim()
            return saved.ifEmpty { DEFAULT_URL }
        }

        fun getToken(context: Context): String {
            val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_TOKEN, "").orEmpty().trim()
            return saved.ifEmpty { DEFAULT_TOKEN }
        }
    }

    private lateinit var etUrl: EditText
    private lateinit var etToken: EditText
    private lateinit var tvStatus: TextView
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        etUrl = findViewById(R.id.etTursoUrl)
        etToken = findViewById(R.id.etTursoToken)
        tvStatus = findViewById(R.id.tvTursoStatus)

        etUrl.setText(getUrl(this))
        etToken.setText(getToken(this))

        findViewById<Button>(R.id.btnSaveTurso).setOnClickListener { saveConfig() }
        findViewById<Button>(R.id.btnTestTurso).setOnClickListener { testConnection() }
        findViewById<Button>(R.id.btnInsertSample).setOnClickListener { insertSample() }
        findViewById<Button>(R.id.btnTestRecognize).setOnClickListener { testRecognizeLauncher.launch("image/*") }
    }

    /** 测试识别：相册选图 → 打印原始条码 + OCR（诊断用，不解析不保存） */
    private val testRecognizeLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            tvStatus.text = "测试识别中..."
            com.anglesgirl.labelscanner.camera.StaticRecognizer.recognizeUri(
                resolver = contentResolver,
                uri = uri,
                lookup69 = null,
                onResult = { result ->
                    runOnUiThread {
                        val barcodes = result.barcodes.joinToString("\n").ifEmpty { "（无）" }
                        val ocr = result.ocrText.ifBlank { "（无 OCR 文本）" }
                        tvStatus.text = "识别完成：条码 ${result.barcodes.size} 个"
                        androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("🔬 测试识别结果")
                            .setMessage("【原始条码 ${result.barcodes.size} 个】\n$barcodes\n\n【原始 OCR】\n$ocr")
                            .setPositiveButton("复制", { _, _ ->
                                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                cm.setPrimaryClip(android.content.ClipData.newPlainText(
                                    "识别结果", "条码:\n$barcodes\n\nOCR:\n$ocr"))
                                Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
                            })
                            .setNeutralButton("关闭", null)
                            .show()
                    }
                },
                onError = { msg ->
                    runOnUiThread { tvStatus.text = "识别失败：$msg" }
                }
            )
        }
    }

    private fun saveConfig() {
        val url = etUrl.text.toString().trim()
        val token = etToken.text.toString().trim()
        if (url.isEmpty() || token.isEmpty()) {
            tvStatus.text = "⚠️ 连接地址和 token 都要填"
            return
        }
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_URL, url)
            .putString(KEY_TOKEN, token)
            .apply()
        tvStatus.text = "✅ 已保存"
        Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show()
    }

    private fun testConnection() {
        saveConfig()
        val url = etUrl.text.toString().trim()
        val token = etToken.text.toString().trim()
        tvStatus.text = "测试连接中..."
        executor.execute {
            val result = Turso69Client.testConnection(url, token)
            runOnUiThread {
                tvStatus.text = if (result == null) "✅ 连接正常（可查询）" else "❌ 连接失败：$result"
            }
        }
    }

    /** 录入一条示例数据（内部测试用：6936358033251 → 201051012201） */
    private fun insertSample() {
        saveConfig()
        val url = etUrl.text.toString().trim()
        val token = etToken.text.toString().trim()
        tvStatus.text = "写入示例数据..."
        executor.execute {
            val err = Turso69Client.upsert(url, token, "6936358033251", "201051012201", "TO-401H 硒鼓")
            runOnUiThread {
                tvStatus.text = if (err == null) "✅ 示例数据已写入（查询 6936358033251 验证）" else "❌ 写入失败：$err"
            }
        }
    }
}