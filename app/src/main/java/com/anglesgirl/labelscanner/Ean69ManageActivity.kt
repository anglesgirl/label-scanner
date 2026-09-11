package com.anglesgirl.labelscanner

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.anglesgirl.labelscanner.data.Barcode69Sync
import com.anglesgirl.labelscanner.data.Turso69Client
import java.util.concurrent.Executors

/**
 * 📋 69码→物料 映射管理（远程 Turso 库）：
 * 查看全部映射 / 新增 / 删除。长期维护反查数据用。
 */
class Ean69ManageActivity : AppCompatActivity() {

    private lateinit var llList: LinearLayout
    private lateinit var tvStatus: TextView
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge：状态栏/导航栏不留黑色遮罩
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
        setContentView(R.layout.activity_ean69_manage)

        llList = findViewById(R.id.llEan69List)
        tvStatus = findViewById(R.id.tvEan69Status)

        findViewById<Button>(R.id.btnEan69Add).setOnClickListener { showAddDialog() }
        // 每次打开先与云端同步，再展示（用户要求：先拉云端 → 比对 → 互相同步 → 冲突待人工修）
        findViewById<Button>(R.id.btnEan69Refresh).setOnClickListener { syncThenLoad() }
        syncThenLoad()
    }

    /** 远程加载全部映射 */
    /** 打开/刷新时：先同步（拉云端 → 比对 → 互相同步），再展示列表。 */
    private fun syncThenLoad() {
        tvStatus.text = "正在与云端同步…"
        Barcode69Sync.syncAsync(this) { r ->
            if (!r.ok) {
                // 未配置或网络不通：不算错误，退回纯本地列表，不影响使用
                tvStatus.text = r.error
                loadList()
                return@syncAsync
            }
            tvStatus.text = r.summary()
            loadList()
            if (r.conflicts.isNotEmpty()) showConflict(r.conflicts, 0)
        }
    }

    /**
     * 逐个裁决冲突。**不自动改数据** —— 由用户决定哪个是对的
     * （两边时间字段语义不同，自动按时间选会把顺序判反，而这是主数据）。
     */
    private fun showConflict(list: List<Barcode69Sync.Conflict>, index: Int) {
        if (index >= list.size) {
            loadList()
            tvStatus.text = "冲突已处理完，共 ${list.size} 条"
            return
        }
        val c = list[index]
        AlertDialog.Builder(this)
            .setTitle("⚠️ 冲突 ${index + 1}/${list.size}")
            .setMessage("69 码：${c.ean}\n\n本地记录：${c.localMaterial}\n云端记录：${c.remoteMaterial}\n\n哪一个是正确的？")
            .setCancelable(false)
            .setPositiveButton("用本地") { _, _ ->
                Barcode69Sync.resolve(this, c, useRemote = false)
                showConflict(list, index + 1)
            }
            .setNeutralButton("用云端") { _, _ ->
                Barcode69Sync.resolve(this, c, useRemote = true)
                showConflict(list, index + 1)
            }
            .setNegativeButton("手动输入") { _, _ -> promptManual(c, list, index) }
            .show()
    }

    /** 冲突双方都不对时，允许直接输入正确值（本地与云端一同改成它）。 */
    private fun promptManual(
        c: Barcode69Sync.Conflict,
        list: List<Barcode69Sync.Conflict>,
        index: Int,
    ) {
        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(c.localMaterial)
            hint = "正确的物料编码"
        }
        AlertDialog.Builder(this)
            .setTitle("输入正确的物料编码（69 码 ${c.ean}）")
            .setView(et)
            .setPositiveButton("确定") { _, _ ->
                val v = et.text.toString().trim()
                if (v.isNotEmpty()) {
                    Barcode69Sync.resolveWith(this, c.ean, v)
                    Toast.makeText(this, "已修正为 $v", Toast.LENGTH_SHORT).show()
                }
                showConflict(list, index + 1)
            }
            .setNegativeButton("跳过") { _, _ -> showConflict(list, index + 1) }
            .show()
    }

    private fun loadList() {
        tvStatus.text = "加载中..."
        val url = SettingsActivity.getUrl(this)
        val token = SettingsActivity.getToken(this)
        executor.execute {
            val list = Turso69Client.listAll(url, token)
            runOnUiThread {
                rebuild(list)
                tvStatus.text = "共 ${list.size} 条映射（云端）"
            }
        }
    }

    private fun rebuild(entries: List<Triple<String, String, String>>) {
        llList.removeAllViews()
        if (entries.isEmpty()) {
            val tv = TextView(this).apply {
                text = "（暂无映射，点 ➕ 新增）"
                setTextColor(cc(R.color.ls_text_dim))
                setPadding(8, 16, 8, 16)
            }
            llList.addView(tv)
            return
        }
        for ((ean, code, name) in entries) {
            val row = LayoutInflater.from(this).inflate(R.layout.item_ean69_row, llList, false)
            row.findViewById<TextView>(R.id.tvEan69Code).text = ean
            row.findViewById<TextView>(R.id.tvEan69Material).text =
                if (name.isBlank()) "→ $code" else "→ $code（$name）"
            row.findViewById<Button>(R.id.btnEan69Del).setOnClickListener { confirmDelete(ean) }
            llList.addView(row)
        }
    }

    private fun confirmDelete(ean: String) {
        AlertDialog.Builder(this)
            .setTitle("删除映射")
            .setMessage("确定删除 $ean 的反查映射？\n（手机本地已缓存的映射不受影响）")
            .setPositiveButton("删除") { _, _ -> deleteEntry(ean) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteEntry(ean: String) {
        val url = SettingsActivity.getUrl(this)
        val token = SettingsActivity.getToken(this)
        executor.execute {
            val err = Turso69Client.delete(url, token, ean)
            runOnUiThread {
                if (err == null) {
                    Toast.makeText(this, "已删除 $ean", Toast.LENGTH_SHORT).show()
                    loadList()
                } else {
                    tvStatus.text = "❌ 删除失败：$err"
                }
            }
        }
    }

    /** 新增/修改映射（同 69 码重复保存 = 覆盖） */
    private fun showAddDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 24, 56, 8)
        }
        fun field(hint: String, isPassword: Boolean = false) = EditText(this).apply {
            this.hint = hint
            textSize = 15f
            inputType = if (isPassword) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT
        }
        val etEan = field("69 商品码（13 位）")
        val etMat = field("物料编码（SAP 号）")
        val etName = field("物料名称（可选）")
        layout.addView(etEan)
        layout.addView(etMat)
        layout.addView(etName)

        AlertDialog.Builder(this)
            .setTitle("➕ 新增 69码→物料 映射")
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                val ean = etEan.text.toString().trim()
                val mat = etMat.text.toString().trim()
                if (ean.isEmpty() || mat.isEmpty()) {
                    tvStatus.text = "⚠️ 69码和物料编码必填"
                    return@setPositiveButton
                }
                upsertEntry(ean, mat, etName.text.toString().trim())
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun upsertEntry(ean: String, mat: String, name: String) {
        val url = SettingsActivity.getUrl(this)
        val token = SettingsActivity.getToken(this)
        executor.execute {
            val err = Turso69Client.upsert(url, token, ean, mat, name.ifEmpty { null })
            runOnUiThread {
                if (err == null) {
                    tvStatus.text = "✅ 已保存 $ean → $mat"
                    loadList()
                } else {
                    tvStatus.text = "❌ 保存失败：$err"
                }
            }
        }
    }

    /** 取主题色（跟随深浅模式）。 */
    private fun cc(resId: Int): Int = androidx.core.content.ContextCompat.getColor(this, resId)
}
