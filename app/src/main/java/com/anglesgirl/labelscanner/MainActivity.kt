package com.anglesgirl.labelscanner

import android.content.Intent
import android.os.Bundle
import androidx.core.view.updatePadding
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 主界面 = 导航页：单条入库 / 单箱入库 / 托盘中心 / 设置。
 * 录入逻辑都在独立 Activity（SingleInbound / SingleBoxInbound），
 * 数据统一落 RecordStore（含 trayCode），托盘中心查询/导出。
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge：与其他页面保持一致，否则首页顶部会被状态栏盖住
        // （首页布局只有 20dp 内边距，不足以避开系统栏）。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        findViewById<Button>(R.id.btnModeSingle).setOnClickListener {
            startActivity(Intent(this, SingleInboundActivity::class.java))
        }
        findViewById<Button>(R.id.btnModeBox).setOnClickListener {
            startActivity(Intent(this, SingleBoxInboundActivity::class.java))
        }
        findViewById<Button>(R.id.btnSplitCode).setOnClickListener {
            startActivity(Intent(this, SplitCodeActivity::class.java))
        }
        findViewById<Button>(R.id.btnGotoCenter).setOnClickListener {
            startActivity(Intent(this, RecordListActivity::class.java))
        }
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        // 防误触提示：托盘中心是唯一出口
        findViewById<Button>(R.id.btnGotoCenter).setOnLongClickListener {
            Toast.makeText(this, "录入完成后在托盘中心勾选托盘导出 WMS", Toast.LENGTH_SHORT).show()
            true
        }
    }
}