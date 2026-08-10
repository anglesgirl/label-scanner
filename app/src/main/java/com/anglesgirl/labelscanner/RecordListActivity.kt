package com.anglesgirl.labelscanner

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import com.anglesgirl.labelscanner.data.RecordStore
import com.anglesgirl.labelscanner.export.Exporter
import com.anglesgirl.labelscanner.model.LabelResult

/**
 * 数据列表页：查看/编辑/删除已保存的记录。
 *
 * 点某条 → EditRecordActivity 编辑；长按 → 删除。
 * 顶部 Spinner 可按托盘码筛选；导出 WMS 仅导出当前筛选的托盘码数据。
 * 返回时把修改后的列表回传给 MainActivity（setResult + finish）。
 */
class RecordListActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var tvEmpty: TextView
    private lateinit var spTrayFilter: Spinner
    private lateinit var btnExportWms: View
    private var records: MutableList<LabelResult> = mutableListOf()
    private var allTrayCodes: List<String> = emptyList()
    private var selectedTrayFilter: String? = null // null = 全部

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge：内容延伸到状态栏/导航栏底下，避免遮挡
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_record_list)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { view, insets ->
            val sysBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(sysBars.left, sysBars.top, sysBars.right, sysBars.bottom)
            insets
        }

        listView = findViewById(R.id.listRecords)
        tvEmpty = findViewById(R.id.tvEmpty)
        spTrayFilter = findViewById(R.id.spTrayFilter)
        btnExportWms = findViewById(R.id.btnExportWms)

        // 加载所有托盘码，设置 Spinner
        allTrayCodes = RecordStore.getAllTrayCodes(this)
        val trayOptions = mutableListOf("全部（无筛选）")
        trayOptions.addAll(allTrayCodes)
        val trayAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, trayOptions)
        trayAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spTrayFilter.adapter = trayAdapter

        spTrayFilter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedTrayFilter = if (position == 0) null else trayOptions[position]
                refreshList()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnExportWms.setOnClickListener {
            exportWmsForSelectedTray()
        }

        refreshList()
    }

    private fun refreshList() {
        records = if (selectedTrayFilter == null) {
            RecordStore.load(this)
        } else {
            RecordStore.loadByTrayCode(this, selectedTrayFilter!!)
        }

        if (records.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            listView.visibility = View.GONE
            return
        }

        tvEmpty.visibility = View.GONE
        listView.visibility = View.VISIBLE

        val adapter = object : ArrayAdapter<LabelResult>(this, 0, records) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = convertView ?: layoutInflater.inflate(
                    android.R.layout.simple_list_item_2, parent, false
                )
                val r = getItem(position)!!
                v.findViewById<TextView>(android.R.id.text1).text =
                    "#${position + 1}  SN: ${r.serialNumber.ifBlank { "（无）" }}${if (r.trayCode.isNotBlank()) "  📦${r.trayCode}" else ""}"
                v.findViewById<TextView>(android.R.id.text2).text =
                    "物料: ${r.materialCode.ifBlank { "—" }}  日期: ${r.productionDate.ifBlank { "—" }}  " +
                        (if (r.ean69.isNotBlank()) "69码: ${r.ean69}" else "")
                return v
            }
        }
        listView.adapter = adapter

        // 点条目 → 编辑
        listView.setOnItemClickListener { _, _, position, _ ->
            val intent = Intent(this, EditRecordActivity::class.java)
            intent.putExtra("index", position)
            intent.putExtra("trayFilter", selectedTrayFilter.orEmpty()) // 传筛选条件，编辑页保存后回到正确筛选
            startActivityForResult(intent, REQ_EDIT)
        }

        // 长按 → 删除
        listView.setOnItemLongClickListener { _, _, position, _ ->
            val toDelete = records[position]
            records.removeAt(position)
            // 持久化：如果筛选了托盘码，只删除该托盘码下的；否则全量保存
            val fullList = RecordStore.load(this)
            val filtered = fullList.filter { it.serialNumber != toDelete.serialNumber }.toMutableList()
            RecordStore.save(this, filtered)
            adapter.notifyDataSetChanged()
            if (records.isEmpty()) {
                tvEmpty.visibility = View.VISIBLE
                listView.visibility = View.GONE
            }
            Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
            true
        }
    }

    private fun exportWmsForSelectedTray() {
        val trayCode = selectedTrayFilter ?: ""
        val data = if (trayCode.isEmpty()) {
            RecordStore.load(this)
        } else {
            RecordStore.loadByTrayCode(this, trayCode)
        }
        if (data.isEmpty()) {
            Toast.makeText(this, "当前筛选下无数据", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = Exporter.exportWms(this, data)
        if (uri != null) {
            Toast.makeText(this, "WMS 导出成功（${data.size} 条）", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "导出失败", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_EDIT && resultCode == RESULT_OK) {
            // 编辑页可能改了数据：重新按当前筛选刷新
            refreshList()
        }
    }

    override fun onBackPressed() {
        // 把最新列表带回主界面
        val data = Intent().putExtra("records_updated", true)
        setResult(RESULT_OK, data)
        finish()
        super.onBackPressed()
    }

    companion object {
        private const val REQ_EDIT = 1001
    }
}