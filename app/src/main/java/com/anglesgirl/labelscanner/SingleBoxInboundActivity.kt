package com.anglesgirl.labelscanner

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.anglesgirl.labelscanner.camera.StaticRecognizer
import com.anglesgirl.labelscanner.data.RecordStore
import com.anglesgirl.labelscanner.model.BoxParser
import com.anglesgirl.labelscanner.model.LabelResult

/**
 * 📦 单箱入库：一个外箱（LPN）对应多个序列号。
 *
 * 流程：相册选标签图 → 自动识别（物料=SAP号 / LPN=CA开头 / 日期 / 型号 / 全部 SN）
 * → 人工确认/增删 SN → 保存（每 SN 展开为一条记录，共享物料/箱号/日期）。
 *
 * 规则见 BoxParser（2026-08-11 真实标签 DL-5120P 校准）。
 */
class SingleBoxInboundActivity : AppCompatActivity() {

    private lateinit var etMaterial: EditText
    private lateinit var etTray: EditText
    private lateinit var etDate: EditText
    private lateinit var etModel: EditText
    private lateinit var etManualSn: EditText
    private lateinit var rvSnList: RecyclerView
    private lateinit var tvBoxStatus: TextView

    private val snList = mutableListOf<String>()
    private lateinit var snAdapter: SnAdapter

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) recognizeLabel(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_single_box)

        etMaterial = findViewById(R.id.etMaterial)
        etTray = findViewById(R.id.etTray)
        etDate = findViewById(R.id.etDate)
        etModel = findViewById(R.id.etModel)
        etManualSn = findViewById(R.id.etManualSn)
        rvSnList = findViewById(R.id.rvSnList)
        tvBoxStatus = findViewById(R.id.tvBoxStatus)

        findViewById<Button>(R.id.btnRecognize).setOnClickListener {
            pickImage.launch("image/*")
        }
        findViewById<Button>(R.id.btnAddSn).setOnClickListener { addManualSn() }
        findViewById<Button>(R.id.btnSaveBox).setOnClickListener { saveBox() }
        findViewById<Button>(R.id.btnResetBox).setOnClickListener { resetBox() }

        snAdapter = SnAdapter(snList) { sn -> snList.remove(sn); snAdapter.notifyDataSetChanged(); updateStatus() }
        rvSnList.layoutManager = LinearLayoutManager(this)
        rvSnList.adapter = snAdapter
        updateStatus()
    }

    /** 相册选图 → 静态识别（ML Kit + ZXing 双解码）→ BoxParser 解析填表 */
    private fun recognizeLabel(uri: Uri) {
        tvBoxStatus.text = "识别中..."
        StaticRecognizer.recognizeUri(
            resolver = contentResolver,
            uri = uri,
            lookup69 = null,
            onResult = { result ->
                runOnUiThread {
                    val box = BoxParser.parse(result.barcodes, result.ocrText)
                    if (!box.hasData) {
                        tvBoxStatus.text = "⚠️ 未识别到内容，请换图重试"
                        return@runOnUiThread
                    }
                    etMaterial.setText(box.materialCode)
                    etTray.setText(box.trayCode)
                    etDate.setText(box.productionDate)
                    etModel.setText(box.model)
                    snList.clear()
                    snList.addAll(box.serialNumbers)
                    snAdapter.notifyDataSetChanged()

                    val tips = mutableListOf<String>()
                    if (box.materialCode.isBlank()) tips.add("⚠️ 未识别到物料(SAP)，请手动输入")
                    if (box.trayCode.isBlank()) tips.add("⚠️ 未识别到外箱LPN，请手动输入")
                    if (box.productionDate.isBlank()) tips.add("⚠️ 未识别到日期，请手动输入")
                    if (snList.isEmpty()) tips.add("⚠️ 未识别到序列号，请手动添加")
                    tvBoxStatus.text = "✅ 识别完成：物料=${box.materialCode.ifBlank { "?" }} LPN=${box.trayCode.ifBlank { "?" }} SN×${snList.size}\n${tips.joinToString("\n")}"
                }
            },
            onError = { msg ->
                runOnUiThread {
                    tvBoxStatus.text = "识别失败：$msg"
                    Toast.makeText(this, "识别失败：$msg", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun addManualSn() {
        val sn = etManualSn.text.toString().trim()
        if (sn.isEmpty()) {
            Toast.makeText(this, "请输入序列号", Toast.LENGTH_SHORT).show()
            return
        }
        if (sn !in snList) {
            snList.add(sn)
            snAdapter.notifyDataSetChanged()
            etManualSn.setText("")
            updateStatus()
        } else {
            Toast.makeText(this, "序列号已存在", Toast.LENGTH_SHORT).show()
        }
    }

    /** 保存：每个 SN 展开为一条记录（共享物料/LPN/日期/型号），去重后追加 */
    private fun saveBox() {
        val material = etMaterial.text.toString().trim()
        val tray = etTray.text.toString().trim()
        val date = etDate.text.toString().trim()
        val model = etModel.text.toString().trim()

        if (snList.isEmpty()) {
            Toast.makeText(this, "序列号列表为空，无法保存", Toast.LENGTH_SHORT).show()
            return
        }
        if (material.isEmpty()) {
            Toast.makeText(this, "物料编码为空（识别不到请手动输入）", Toast.LENGTH_SHORT).show()
            return
        }
        if (tray.isEmpty()) {
            Toast.makeText(this, "外箱 LPN 为空（识别不到请手动输入）", Toast.LENGTH_SHORT).show()
            return
        }

        val records = snList.map { sn ->
            LabelResult(
                barcodes = listOf(sn),
                serialNumber = sn,
                materialCode = material,
                quantity = 1,
                productionDate = date,
                model = model,
                trayCode = tray,
            )
        }
        RecordStore.append(this, records)
        tvBoxStatus.text = "✅ 已保存 ${records.size} 条（物料 $material / LPN $tray）"
        Toast.makeText(this, "已保存 ${records.size} 条记录", Toast.LENGTH_SHORT).show()
        resetBox()
    }

    private fun resetBox() {
        etMaterial.setText("")
        etTray.setText("")
        etDate.setText("")
        etModel.setText("")
        etManualSn.setText("")
        snList.clear()
        snAdapter.notifyDataSetChanged()
        updateStatus()
    }

    private fun updateStatus() {
        val n = snList.size
        tvBoxStatus.text = if (n == 0) "序列号 0 个" else "📦 序列号 $n 个，保存后每 SN 一行"
    }

    /** SN 列表适配器：单行文本 + 删除按钮 */
    class SnAdapter(
        private val items: MutableList<String>,
        private val onRemove: (String) -> Unit,
    ) : RecyclerView.Adapter<SnAdapter.VH>() {

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val tvSn: TextView = view.findViewById(R.id.tvSnItem)
            val btnDel: Button = view.findViewById(R.id.btnDelSn)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_sn_row, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val sn = items[position]
            holder.tvSn.text = "${position + 1}. $sn"
            holder.btnDel.setOnClickListener { onRemove(sn) }
        }

        override fun getItemCount() = items.size
    }
}