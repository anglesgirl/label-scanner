package com.anglesgirl.labelscanner

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.anglesgirl.labelscanner.data.RecordStore
import com.anglesgirl.labelscanner.export.Exporter
import com.anglesgirl.labelscanner.model.LabelResult
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 🗂️ 托盘中心：按托盘分组 → 多选 → 合并导出 WMS（DATA02 每行标托盘）。
 * 最终目的：查询托盘号获得数据，一次可导出多个托盘号喂 WMS 系统。
 */
class RecordListActivity : AppCompatActivity() {

    private lateinit var llTrayGroups: LinearLayout
    private lateinit var listDetail: ListView
    private lateinit var tvEmpty: TextView
    private lateinit var btnExportWms: Button
    private lateinit var btnSelectAll: Button

    private var allRecords: List<LabelResult> = emptyList()
    private var groups: List<TrayGroup> = emptyList()
    private val checked = mutableSetOf<String>() // 勾选的托盘码
    private var selectAllState = false

    data class TrayGroup(val trayCode: String, val items: List<LabelResult>)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_record_list)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        llTrayGroups = findViewById(R.id.llTrayGroups)
        listDetail = findViewById(R.id.listDetail)
        tvEmpty = findViewById(R.id.tvEmpty)
        btnExportWms = findViewById(R.id.btnExportWms)
        btnSelectAll = findViewById(R.id.btnSelectAll)

        btnExportWms.setOnClickListener { exportSelected() }
        btnSelectAll.setOnClickListener { toggleSelectAll() }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh() // 从编辑页返回后刷新
    }

    private fun refresh() {
        allRecords = RecordStore.load(this)
        if (allRecords.isEmpty()) {
            groups = emptyList()
            checked.clear()
            tvEmpty.visibility = View.VISIBLE
            listDetail.visibility = View.GONE
            llTrayGroups.removeAllViews()
            return
        }
        tvEmpty.visibility = View.GONE
        listDetail.visibility = View.VISIBLE

        // 按托盘分组：空托盘码归「未分配」
        val map = LinkedHashMap<String, MutableList<LabelResult>>()
        for (r in allRecords) {
            val key = r.trayCode.ifBlank { "（未分配）" }
            map.getOrPut(key) { mutableListOf() }.add(r)
        }
        groups = map.map { TrayGroup(it.key, it.value) }
        // 去掉已不存在的勾选
        checked.retainAll(groups.map { it.trayCode })

        rebuildTrayRows()
        rebuildDetail()
    }

    private fun rebuildTrayRows() {
        llTrayGroups.removeAllViews()
        for (g in groups) {
            val row = CheckBox(this)
            val snCount = g.items.size
            val boxCount = g.items.map { it.boxCode }.filter { it.isNotBlank() }.distinct().size
            val boxText = if (boxCount > 0) " ${boxCount}箱" else ""
            row.text = if (g.trayCode == "（未分配）") {
                "❓ ${g.trayCode}（${snCount} 条$boxText）— 长按分配托盘号"
            } else {
                "📦 ${g.trayCode}（${snCount} 条$boxText）"
            }
            row.textSize = 14f
            row.isChecked = g.trayCode in checked
            row.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) checked.add(g.trayCode) else checked.remove(g.trayCode)
                rebuildDetail()
            }
            // 未分配组：长按弹窗批量分配托盘号
            if (g.trayCode == "（未分配）") {
                row.setOnLongClickListener {
                    showAssignTrayDialog(g)
                    true
                }
            }
            llTrayGroups.addView(row)
        }
    }

    /** 给未分配记录批量分配托盘号 */
    private fun showAssignTrayDialog(group: TrayGroup) {
        val input = android.widget.EditText(this).apply {
            hint = "输入托盘号（应用到未分配的 ${group.items.size} 条）"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("分配托盘号")
            .setView(input)
            .setPositiveButton("分配") { _, _ ->
                val tray = input.text.toString().trim()
                if (tray.isEmpty()) {
                    Toast.makeText(this, "托盘号不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val full = RecordStore.load(this).toMutableList()
                var count = 0
                for (r in full) {
                    if (r.trayCode.isBlank()) {
                        r.trayCode = tray
                        count++
                    }
                }
                RecordStore.save(this, full)
                Toast.makeText(this, "已分配 $count 条记录到托盘 $tray", Toast.LENGTH_SHORT).show()
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun rebuildDetail() {
        val selected = groups.filter { it.trayCode in checked }.flatMap { it.items }
        if (selected.isEmpty()) {
            listDetail.adapter = null
            (findViewById<TextView>(R.id.tvEmpty)?.let {
                it.visibility = View.GONE
            })
            return
        }
        val adapter = object : android.widget.ArrayAdapter<LabelResult>(this, 0, selected) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val v = convertView ?: layoutInflater.inflate(
                    android.R.layout.simple_list_item_2, parent, false
                )
                val r = getItem(position)!!
                v.findViewById<TextView>(android.R.id.text1).text =
                    "${r.trayCode.ifBlank { "（未分配）" }} | ${r.serialNumber.ifBlank { "（无SN）" }}" +
                        (if (r.boxCode.isNotBlank()) " | 箱:${r.boxCode}" else "")
                v.findViewById<TextView>(android.R.id.text2).text =
                    "物料: ${r.materialCode.ifBlank { "—" }}  日期: ${r.productionDate.ifBlank { "—" }}"
                return v
            }
        }
        listDetail.adapter = adapter
        listDetail.setOnItemClickListener { _, _, position, _ ->
            val r = selected[position]
            val intent = Intent(this, EditRecordActivity::class.java)
            intent.putExtra("index", allRecords.indexOf(r))
            startActivity(intent)
        }
        listDetail.setOnItemLongClickListener { _, _, position, _ ->
            val toDelete = selected[position]
            val full = RecordStore.load(this).toMutableList()
            full.removeAll { it.serialNumber == toDelete.serialNumber && it.trayCode == toDelete.trayCode }
            RecordStore.save(this, full)
            Toast.makeText(this, "已删除 ${toDelete.serialNumber}", Toast.LENGTH_SHORT).show()
            refresh()
            true
        }
    }

    private fun toggleSelectAll() {
        selectAllState = !selectAllState
        if (selectAllState) checked.addAll(groups.map { it.trayCode })
        else checked.clear()
        rebuildTrayRows()
        rebuildDetail()
    }

    /** 导出选中托盘（多托盘合并为一个 WMS 文件，DATA02 每行标托盘） */
    private fun exportSelected() {
        val selected = groups.filter { it.trayCode in checked }.flatMap { it.items }
        if (selected.isEmpty()) {
            Toast.makeText(this, "请先勾选要导出的托盘", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = Exporter.exportWms(this, selected)
        if (uri == null) {
            Toast.makeText(this, "WMS 导出失败", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "WMS库存导入数据（${checked.size}个托盘 ${selected.size}条）")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "导出 WMS 数据"))
    }
}