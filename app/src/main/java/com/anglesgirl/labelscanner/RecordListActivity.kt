package com.anglesgirl.labelscanner

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.anglesgirl.labelscanner.data.RecordStore
import com.anglesgirl.labelscanner.model.LabelResult

/**
 * 数据列表页：查看/编辑/删除已保存的记录。
 *
 * 点某条 → EditRecordActivity 编辑；长按 → 删除。
 * 返回时把修改后的列表回传给 MainActivity（setResult + finish）。
 */
class RecordListActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var tvEmpty: TextView
    private var records: MutableList<LabelResult> = mutableListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_record_list)

        listView = findViewById(R.id.listRecords)
        tvEmpty = findViewById(R.id.tvEmpty)

        records = RecordStore.load(this)

        if (records.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            listView.visibility = View.GONE
            return
        }

        val adapter = object : ArrayAdapter<LabelResult>(this, 0, records) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = convertView ?: layoutInflater.inflate(
                    android.R.layout.simple_list_item_2, parent, false
                )
                val r = getItem(position)!!
                v.findViewById<TextView>(android.R.id.text1).text =
                    "#${position + 1}  SN: ${r.serialNumber.ifBlank { "（无）" }}"
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
            startActivityForResult(intent, REQ_EDIT)
        }

        // 长按 → 删除
        listView.setOnItemLongClickListener { _, _, position, _ ->
            records.removeAt(position)
            RecordStore.save(this, records)
            adapter.notifyDataSetChanged()
            if (records.isEmpty()) {
                tvEmpty.visibility = View.VISIBLE
                listView.visibility = View.GONE
            }
            Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
            true
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_EDIT) {
            // 编辑页可能改了数据：原地刷新列表（adapter 持有同一引用）
            val fresh = RecordStore.load(this)
            records.clear()
            records.addAll(fresh)
            if (records.isEmpty()) {
                tvEmpty.visibility = View.VISIBLE
                listView.visibility = View.GONE
            } else {
                tvEmpty.visibility = View.GONE
                listView.visibility = View.VISIBLE
                (listView.adapter as? ArrayAdapter<*>)?.notifyDataSetChanged()
            }
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
