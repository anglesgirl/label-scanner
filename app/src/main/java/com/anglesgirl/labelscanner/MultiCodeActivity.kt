package com.anglesgirl.labelscanner

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.anglesgirl.labelscanner.camera.ImageIn
import com.anglesgirl.labelscanner.camera.StaticRecognizer
import com.anglesgirl.labelscanner.data.Barcode69Lookup
import com.anglesgirl.labelscanner.data.RecordStore
import com.anglesgirl.labelscanner.model.LabelParser
import com.anglesgirl.labelscanner.model.LabelResult

/**
 * 多码清单模式：一张纸多个条码 → 批量独立入库。
 *
 * 场景：
 *  - 物料标签纸：两列 12 行共 24 个条码，每个条码 = 1 个序列号
 *  - 成品进仓单：卡板条码 + 物料条码，一行一个物料批次
 *
 * 流程：拍照/文档扫描/相册 → 解出【全部条码】+ OCR → 物料/日期自动预填（可改）
 *      → 条码列表逐条可勾选/可编辑 → 保存 = 每个勾选项独立一条记录。
 *
 * 关键点（用户实测反馈）：
 *  - "添加序列号扫这种纸，带逗号的集成码被当成一个序列号" —— 这里不做字段归类，
 *    所有条码原样列出，由用户勾选决定哪些入库，绝不合并。
 */
class MultiCodeActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var etMaterial: EditText
    private lateinit var etDate: EditText
    private lateinit var etEan: EditText
    private lateinit var rvCodes: RecyclerView
    private lateinit var btnSave: Button
    private lateinit var btnSelectAll: Button

    private val lookup69Lazy = lazy { Barcode69Lookup(this) }
    private fun lookup69(): Barcode69Lookup = lookup69Lazy.value

    private var recognizing = false

    /** 取图入口：拍照 / 文档扫描 / 相册 共用一份实现。 */
    private val imageIn by lazy {
        ImageIn(this) { uri -> onImage(uri) }
    }

    private val items = mutableListOf<Item>()
    private val adapter = ItemAdapter { pos, checked -> items[pos].checked = checked }

    private class Item(var value: String, var checked: Boolean = true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_multi_code)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        tvStatus = findViewById(R.id.tvStatus)
        etMaterial = findViewById(R.id.etMaterial)
        etDate = findViewById(R.id.etDate)
        etEan = findViewById(R.id.etEan)
        rvCodes = findViewById(R.id.rvCodes)
        btnSave = findViewById(R.id.btnSave)
        btnSelectAll = findViewById(R.id.btnSelectAll)
        rvCodes.layoutManager = LinearLayoutManager(this)
        rvCodes.adapter = adapter

        findViewById<Button>(R.id.btnTakePhoto).setOnClickListener { imageIn.takePhoto() }
        findViewById<Button>(R.id.btnScanDoc).setOnClickListener {
            if (imageIn.isDocumentScanAvailable()) imageIn.scanDocument()
            else Toast.makeText(this, "本机不支持文档扫描，请用拍照或相册", Toast.LENGTH_LONG).show()
        }
        findViewById<Button>(R.id.btnPickGallery).setOnClickListener { imageIn.pickGallery() }

        btnSave.setOnClickListener { saveChecked() }
        btnSelectAll.setOnClickListener {
            val allChecked = items.all { it.checked }
            items.forEach { it.checked = !allChecked }
            adapter.notifyDataSetChanged()
            btnSelectAll.text = if (allChecked) "全选" else "取消全选"
        }
        updateSaveState()
    }

    /** 拿到图 → 多码识别 → 预填字段 + 列出全部条码。 */
    private fun onImage(uri: Uri) {
        if (recognizing) return
        recognizing = true
        tvStatus.text = "识别中（解出全部条码 + 读文字）…"
        btnSave.isEnabled = false
        StaticRecognizer.recognizeUriMulti(
            resolver = contentResolver,
            uri = uri,
            onResult = { codes, ocrText -> runOnUiThread { showResult(codes, ocrText) } },
            onError = { msg -> runOnUiThread { fail("识别失败：$msg") } },
        )
    }

    private fun showResult(codes: List<String>, ocrText: String) {
        recognizing = false
        // 用现有归类逻辑预填物料/日期/69（LabelParser 只提取字段，不改条码列表）
        val parsed = LabelParser.parse(codes, ocrText) { ean -> lookup69().lookup(ean) }
        if (parsed.materialCode.isNotBlank()) etMaterial.setText(parsed.materialCode)
        if (parsed.productionDate.isNotBlank() && parsed.productionDate != "19000101") {
            etDate.setText(parsed.productionDate)
        }
        if (parsed.ean69.isNotBlank()) etEan.setText(parsed.ean69)

        items.clear()
        codes.forEach { c ->
            // 过滤掉集成码整串（含逗号的多 SN）—— 那是一条"汇总"，不是单序列号；
            // 里面拆出来的单码如果也在列表里，用户直接勾选即可。
            if (!c.contains(',') && !c.contains('，')) items.add(Item(c))
        }
        // 集成码里逗号分隔的单码也摊开列出（用户要的是"12 个序列号"，不想要整串）
        codes.forEach { c ->
            if (c.contains(',') || c.contains('，')) {
                c.split(Regex("[,，;；\\s]+")).map { it.trim() }.filter { it.isNotBlank() }.forEach { part ->
                    if (items.none { it.value == part }) items.add(Item(part))
                }
            }
        }
        adapter.notifyDataSetChanged()

        tvStatus.text = "✅ 识别 ${codes.size} 个码 → 展开 ${items.size} 条（可勾选/编辑），确认后保存"
        updateSaveState()
        if (items.isEmpty()) Toast.makeText(this, "没有解出条码，可换文档扫描或重拍", Toast.LENGTH_LONG).show()
    }

    private fun fail(msg: String) {
        recognizing = false
        tvStatus.text = msg
        updateSaveState()
    }

    /** 保存：勾选的每一项 → 独立一条记录（序列号 = 条码值）。 */
    private fun saveChecked() {
        val material = etMaterial.text.toString().trim()
        val date = etDate.text.toString().trim()
        val ean = etEan.text.toString().trim()
        if (material.isEmpty()) {
            Toast.makeText(this, "物料编码不能为空（可从识别结果自动填，或手动输入）", Toast.LENGTH_LONG).show()
            return
        }
        val checked = items.filter { it.checked && it.value.isNotBlank() }
        if (checked.isEmpty()) {
            Toast.makeText(this, "没有勾选任何条码", Toast.LENGTH_SHORT).show()
            return
        }
        val records = checked.map { it ->
            LabelResult(
                barcodes = listOf(it.value),
                ocrText = "",
                supplier = "NA",
                serialNumber = it.value.trim(),
                materialCode = material,
                productionDate = date.ifBlank { "19000101" },
                ean69 = ean,
            )
        }
        RecordStore.append(this, records)
        Toast.makeText(this, "已保存 ${records.size} 条（序列号已入账）", Toast.LENGTH_LONG).show()
        tvStatus.text = "已保存 ${records.size} 条，可继续拍照或选图"
    }

    private fun updateSaveState() {
        btnSave.isEnabled = items.isNotEmpty()
        btnSave.text = "保存 ${items.count { it.checked }} 条"
    }

    /** 条码列表：每行 = 勾选框 + 可编辑条码值。 */
    private inner class ItemAdapter(
        private val onChecked: (Int, Boolean) -> Unit,
    ) : RecyclerView.Adapter<ItemAdapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_multi_code, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val it = items[position]
            holder.cb.isChecked = it.checked
            holder.et.setText(it.value)
            holder.cb.setOnCheckedChangeListener { _, checked ->
                it.checked = checked
                onChecked(position, checked)
                btnSave.text = "保存 ${items.count { i -> i.checked }} 条"
            }
            holder.et.setOnFocusChangeListener { _, has ->
                if (!has) it.value = holder.et.text.toString().trim()
            }
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val cb: CheckBox = v.findViewById(R.id.cbCode)
            val et: EditText = v.findViewById(R.id.etCode)
        }
    }
}
