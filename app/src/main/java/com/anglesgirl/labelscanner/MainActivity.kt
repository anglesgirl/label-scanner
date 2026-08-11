package com.anglesgirl.labelscanner

import android.content.Intent
import android.os.Bundle
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
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btnModeSingle).setOnClickListener {
            startActivity(Intent(this, SingleInboundActivity::class.java))
        }
        findViewById<Button>(R.id.btnModeBox).setOnClickListener {
            startActivity(Intent(this, SingleBoxInboundActivity::class.java))
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