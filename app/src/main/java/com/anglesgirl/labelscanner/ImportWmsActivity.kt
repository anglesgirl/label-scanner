package com.anglesgirl.labelscanner

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.anglesgirl.labelscanner.data.SlipStore
import com.anglesgirl.labelscanner.export.Exporter
import com.anglesgirl.labelscanner.model.WmsImportParser
import java.io.File

/**
 * 导入他人 WMS 导入表：选 .xlsx → 改托盘号（DATA02）/ 库存地（DATA07）/
 * 生产日期（DATA08）→ 附加单据 → 导出成新的 WMS 导入表。
 *
 * 规则：第 1/2 行表头原样保留不改，只改第 3 行起的数据；其余列只读显示。
 * 全离线，中文界面。
 */
class ImportWmsActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var layoutBatchEdit: LinearLayout
    private lateinit var layoutSlip: LinearLayout
    private lateinit var tvSlipInfo: TextView
    private lateinit var btnExport: Button
    private lateinit var rvRows: RecyclerView

    private var table: WmsImportParser.Table? = null
    private val rows = mutableListOf<WmsImportParser.Row>()
    private val adapter = RowAdapter()

    /** 本次导入附加的单据：单号 + 主照片 + 其它附件文件。 */
    private var slipNo: String = ""
    private var slipPhotoFile: File? = null
    private val slipExtraFiles = mutableListOf<File>()
    private var pendingSlipPhoto: File? = null

    /** 选 WMS 导入表（.xlsx）：拷临时文件后解析。 */
    private val pickWmsFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            val displayName = queryDisplayName(uri) ?: "wms_import.xlsx"
            val tmp = File(cacheDir, "import_wms_${System.currentTimeMillis()}.xlsx")
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (_: Exception) {
                Toast.makeText(this, "读取文件失败", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
            if (!tmp.exists() || tmp.length() <= 0) {
                Toast.makeText(this, "读取文件失败", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
            tvStatus.text = "正在解析 $displayName…"
            Thread {
                val parsed = WmsImportParser.parse(tmp)
                runOnUiThread {
                    tmp.delete()
                    if (parsed == null || parsed.rows.isEmpty()) {
                        tvStatus.text = "这张表读不到 WMS 数据（表头要含 DATA01~DATA14 或中文列名，数据从第 3 行起）"
                        Toast.makeText(this, "读不到 WMS 数据，请检查表头", Toast.LENGTH_LONG).show()
                        return@runOnUiThread
                    }
                    table = parsed
                    rows.clear()
                    rows.addAll(parsed.rows)
                    adapter.notifyDataSetChanged()
                    layoutBatchEdit.visibility = View.VISIBLE
                    layoutSlip.visibility = View.VISIBLE
                    btnExport.visibility = View.VISIBLE
                    tvStatus.text = "$displayName：${parsed.rows.size} 行（表：${parsed.sheetName}）"
                }
            }.start()
        }

    /** 拍单据：系统相机全分辨率拍照，拍完归档到本次导入的单据。 */
    private val takeSlipPhoto =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            val tmp = pendingSlipPhoto
            pendingSlipPhoto = null
            if (success && tmp != null && tmp.exists()) {
                attachSlipPhoto(tmp)
            } else {
                tmp?.delete()
                Toast.makeText(this, "拍照已取消", Toast.LENGTH_SHORT).show()
            }
        }

    /** 单据从相册选本地图片。 */
    private val pickSlipGallery =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@registerForActivityResult
            val tmp = SlipStore.newTempPhotoFile(this)
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (_: Exception) {
                Toast.makeText(this, "读取图片失败", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
            if (tmp.exists() && tmp.length() > 0) attachSlipPhoto(tmp)
            else Toast.makeText(this, "读取图片失败", Toast.LENGTH_SHORT).show()
        }

    /** 单据附加本地文件（表格等单独文件）。 */
    private val pickSlipFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            val displayName = queryDisplayName(uri) ?: "file_${System.currentTimeMillis()}"
            val tmp = SlipStore.newTempFile(this, displayName)
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (_: Exception) {
                Toast.makeText(this, "读取文件失败", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
            if (!tmp.exists() || tmp.length() <= 0) {
                Toast.makeText(this, "读取文件失败", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
            ensureSlipNo()
            val path = SlipStore.storeFile(this, tmp, slipNo, displayName)
            SlipStore.addFile(this, slipNo, path, displayName)
            slipExtraFiles.add(File(path))
            refreshSlipInfo()
            Toast.makeText(this, "已附加到单据 $slipNo：$displayName", Toast.LENGTH_SHORT).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_import_wms)
        title = "导入 WMS 表"

        tvStatus = findViewById(R.id.tvImportStatus)
        layoutBatchEdit = findViewById(R.id.layoutBatchEdit)
        layoutSlip = findViewById(R.id.layoutSlip)
        tvSlipInfo = findViewById(R.id.tvSlipInfo)
        btnExport = findViewById(R.id.btnExportWms)
        rvRows = findViewById(R.id.rvImportRows)
        rvRows.layoutManager = LinearLayoutManager(this)
        rvRows.adapter = adapter

        findViewById<Button>(R.id.btnPickWmsFile).setOnClickListener {
            pickWmsFile.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        }

        findViewById<Button>(R.id.btnApplyTray).setOnClickListener {
            val v = findViewById<EditText>(R.id.etBatchTray).text.toString().trim()
            if (v.isEmpty()) {
                Toast.makeText(this, "先输入新托盘号", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            rows.forEach { it.setTrayCode(v) }
            adapter.notifyDataSetChanged()
            Toast.makeText(this, "已把 ${rows.size} 行托盘号改成 $v", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnApplyStock).setOnClickListener {
            val v = findViewById<EditText>(R.id.etBatchStock).text.toString().trim()
            if (v.isEmpty()) {
                Toast.makeText(this, "先输入新库存地", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            rows.forEach { it.setStockLocation(v) }
            adapter.notifyDataSetChanged()
            Toast.makeText(this, "已把 ${rows.size} 行库存地改成 $v", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnApplyDate).setOnClickListener {
            val v = findViewById<EditText>(R.id.etBatchDate).text.toString().trim()
            if (v.isEmpty()) {
                Toast.makeText(this, "先输入新生产日期", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            rows.forEach { it.setProductionDate(v) }
            adapter.notifyDataSetChanged()
            Toast.makeText(this, "已把 ${rows.size} 行生产日期改成 $v", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnAttachSlip).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("附加单据")
                .setItems(arrayOf("📷 拍照", "🖼️ 相册选本地图片", "📄 文件（表格等单独文件）")) { _, which ->
                    when (which) {
                        0 -> {
                            val tmp = SlipStore.newTempPhotoFile(this)
                            pendingSlipPhoto = tmp
                            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", tmp)
                            takeSlipPhoto.launch(uri)
                        }
                        1 -> pickSlipGallery.launch("image/*")
                        else -> pickSlipFile.launch(arrayOf("*/*"))
                    }
                }
                .show()
        }

        btnExport.setOnClickListener { exportWms() }
    }

    /** 附加一张单据照片：第一张当主照片，后面的当附件。 */
    private fun attachSlipPhoto(tmp: File) {
        ensureSlipNo()
        if (slipPhotoFile == null) {
            val path = SlipStore.storePhoto(this, tmp, slipNo)
            SlipStore.saveSlip(this, slipNo, path)
            slipPhotoFile = File(path)
        } else {
            val displayName = "单据照片_${System.currentTimeMillis()}.jpg"
            val path = SlipStore.storeFile(this, tmp, slipNo, displayName)
            SlipStore.addFile(this, slipNo, path, displayName)
            slipExtraFiles.add(File(path))
        }
        refreshSlipInfo()
        Toast.makeText(this, "已附加到单据 $slipNo", Toast.LENGTH_SHORT).show()
    }

    private fun ensureSlipNo() {
        if (slipNo.isBlank()) slipNo = SlipStore.generateSlipNo()
    }

    private fun refreshSlipInfo() {
        val photoCount = if (slipPhotoFile != null) 1 else 0
        tvSlipInfo.text = if (slipNo.isBlank()) {
            "未附加单据"
        } else {
            "已附加单据：$slipNo（${photoCount + slipExtraFiles.size} 个文件，导出时一起带上）"
        }
    }

    /** 导出：表头原样 + 改过的数据行；文件名沿用托盘号，没有则加时间戳。 */
    private fun exportWms() {
        val t = table ?: return
        val tray = t.commonTrayCode().replace(Regex("[^A-Za-z0-9_\\-\\u4e00-\\u9fa5]"), "_")
        val fileName = if (tray.isNotEmpty()) "$tray.xlsx" else "wms_import_${System.currentTimeMillis()}.xlsx"
        val uri = Exporter.exportImportedWms(this, t, slipPhotoFile, slipExtraFiles, fileName)
        if (uri != null) {
            Toast.makeText(this, "已导出：$fileName（下载/LabelScanner）", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "导出失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun queryDisplayName(uri: Uri): String? =
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }

    private inner class RowAdapter : RecyclerView.Adapter<RowAdapter.VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_import_wms_row, parent, false)
            return VH(view)
        }

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = rows[position]
            holder.info.text = "物料 ${row.materialCode.ifBlank { "—" }} · 箱号 ${row.boxCode.ifBlank { "—" }} · 数量 ${row.quantity.ifBlank { "—" }}"
            holder.etTray.setText(row.trayCode)
            holder.etStock.setText(row.stockLocation)
            holder.etDate.setText(row.productionDate)
            holder.etTray.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) row.setTrayCode(holder.etTray.text.toString().trim())
            }
            holder.etStock.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) row.setStockLocation(holder.etStock.text.toString().trim())
            }
            holder.etDate.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) row.setProductionDate(holder.etDate.text.toString().trim())
            }
        }

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val info: TextView = view.findViewById(R.id.tvImportRowInfo)
            val etTray: EditText = view.findViewById(R.id.etImportTray)
            val etStock: EditText = view.findViewById(R.id.etImportStock)
            val etDate: EditText = view.findViewById(R.id.etImportDate)
        }
    }
}
