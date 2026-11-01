package com.anglesgirl.labelscanner

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.ViewCompat
import androidx.core.view.updatePadding
import com.anglesgirl.labelscanner.util.CrashHandler

/**
 * 主界面导航：扫描录入、托盘中心、台账管理与设置。
 * 台账与扫描录入保持独立入口，数据分别由 RecordStore / LedgerStore 管理。
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 全局崩溃捕获：写日志文件，下次启动弹窗展示。
        CrashHandler.install(applicationContext)
        // Edge-to-edge：与其他页面保持一致，避免首页内容被系统栏遮挡。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        // 上次崩溃日志：弹窗展示，可一键复制用于排查。
        CrashHandler.read(this)?.let { log ->
            AlertDialog.Builder(this)
                .setTitle("上次运行崩溃")
                .setMessage(log.take(4000))
                .setPositiveButton("复制日志") { _, _ ->
                    val cm = getSystemService(android.content.ClipboardManager::class.java)
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", log))
                    Toast.makeText(this, "崩溃日志已复制", Toast.LENGTH_SHORT).show()
                    CrashHandler.clear(this)
                }
                .setNegativeButton("清除") { _, _ -> CrashHandler.clear(this) }
                .setNeutralButton("关闭", null)
                .setCancelable(false)
                .show()
        }

        findViewById<Button>(R.id.btnModeSingle).setOnClickListener {
            startActivity(Intent(this, SingleInboundActivity::class.java))
        }
        findViewById<Button>(R.id.btnModeBox).setOnClickListener {
            startActivity(Intent(this, SingleBoxInboundActivity::class.java))
        }
        findViewById<Button>(R.id.btnModeListBatch).setOnClickListener {
            startActivity(Intent(this, ListBatchInboundActivity::class.java))
        }
        findViewById<Button>(R.id.btnSplitCode).setOnClickListener {
            startActivity(Intent(this, SplitCodeActivity::class.java))
        }
        findViewById<Button>(R.id.btnGotoCenter).setOnClickListener {
            startActivity(Intent(this, RecordListActivity::class.java))
        }
        findViewById<Button>(R.id.btnLedger).setOnClickListener {
            startActivity(Intent(this, LedgerActivity::class.java))
        }
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // 防误触提示：托盘中心是唯一的 WMS 批量导出入口。
        findViewById<Button>(R.id.btnGotoCenter).setOnLongClickListener {
            Toast.makeText(this, "录入完成后在托盘中心勾选托盘导出 WMS", Toast.LENGTH_SHORT).show()
            true
        }
    }
}
