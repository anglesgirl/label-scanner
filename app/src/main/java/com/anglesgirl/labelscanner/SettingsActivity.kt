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

        fun getUrl(context: Context): String =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_URL, "").orEmpty().trim()

        fun getToken(context: Context): String =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_TOKEN, "").orEmpty().trim()
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