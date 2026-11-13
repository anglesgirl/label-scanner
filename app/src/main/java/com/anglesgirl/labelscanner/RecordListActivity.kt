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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.anglesgirl.labelscanner.data.DbExecutor
import com.anglesgirl.labelscanner.data.RecordStore
import com.anglesgirl.labelscanner.data.SlipStore
import com.anglesgirl.labelscanner.data.SlipUi
import com.anglesgirl.labelscanner.export.Exporter
import com.anglesgirl.labelscanner.model.LabelResult
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File

/**
 * 🗂️ 托盘中心：按托盘分组 → 多选 → 合并导出 WMS（DATA02 每行标托盘）。
 * 最终目的：查询托盘号获得数据，一次可导出多个托盘号喂 WMS 系统。
 * 单据：勾选的托盘按单号归集，打包成 ZIP（WMS 表 + 单据照片）一起发过去。
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
    private var refreshGen = 0 // 防 onResume/onCreate 重叠加载时旧结果覆盖新结果
    private var pendingSlipPhoto: File? = null
    private var pendingExportRecords: List<LabelResult>? = null // 导出时拍单据：相机返回后继续打包的记录

    /** 导出时补拍单据：拍完输单号 → 绑定勾选托盘 → 打包 ZIP 分享。 */
    private val takeSlipPhoto =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            val tmp = pendingSlipPhoto
            pendingSlipPhoto = null
            val pending = pendingExportRecords
            pendingExportRecords = null
            if (success && tmp != null && tmp.exists() && pending != null) {
                SlipUi.showSlipNumberDialog(this, SlipStore.generateSlipNo()) { slipNo ->
                    val path = SlipStore.storePhoto(this, tmp, slipNo)
                    SlipStore.saveSlip(this, slipNo, path)
                    bindTraysAndShareZip(pending, slipNo)
                }
            } else {
                tmp?.delete()
                Toast.makeText(this, "拍照已取消", Toast.LENGTH_SHORT).show()
            }
        }

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

    /** 全量加载移后台线程；gen 计数丢弃过期结果（如返回本页时旧加载仍在跑）。 */
    private fun refresh() {
        val gen = ++refreshGen
        DbExecutor.run({ RecordStore.load(this@RecordListActivity) }) { result ->
            if (gen != refreshGen) return@run
            result.onSuccess { allRecords = it; renderGroups() }
                .onFailure {
                    Toast.makeText(this@RecordListActivity, "加载失败：${it.message}", Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun renderGroups() {
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
            val slipNos = g.items.map { it.slipNo.trim() }.filter { it.isNotBlank() }.distinct()
            val slipText = when {
                slipNos.size == 1 -> " · 单：${slipNos[0]}"
                slipNos.size > 1 -> " · 单：多个"
                else -> ""
            }
            row.text = if (g.trayCode == "（未分配）") {
                "❓ ${g.trayCode}（${snCount} 条$boxText）$slipText — 长按操作"
            } else {
                "📦 ${g.trayCode}（${snCount} 条$boxText）$slipText — 长按操作"
            }
            row.textSize = 14f
            row.isChecked = g.trayCode in checked
            row.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) checked.add(g.trayCode) else checked.remove(g.trayCode)
                rebuildDetail()
            }
            // 长按 → 托盘级操作（编辑托盘号 / 删除整个托盘）
            row.setOnLongClickListener {
                showTrayActions(g)
                true
            }
            llTrayGroups.addView(row)
        }
    }

    /** 托盘级操作菜单：编辑托盘号 / 补改单号 / 删除整个托盘 */
    private fun showTrayActions(group: TrayGroup) {
        val isUnassigned = group.trayCode == "（未分配）"
        val actions = arrayOf(
            "✏️ ${if (isUnassigned) "分配托盘号" else "编辑托盘号"}",
            "📝 补/改单号",
            "🗑️ 删除托盘（${group.items.size} 条）",
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(if (isUnassigned) "未分配记录" else "托盘 ${group.trayCode}")
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> showEditTrayDialog(group, isUnassigned)
                    1 -> showTraySlipDialog(group, isUnassigned)
                    2 -> showDeleteTrayDialog(group, isUnassigned)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 整托补/改单号：该托盘全部记录记到指定单号，并设为当前单据（之后采集自动跟单）。 */
    private fun showTraySlipDialog(group: TrayGroup, isUnassigned: Boolean) {
        val current = group.items.map { it.slipNo.trim() }.filter { it.isNotBlank() }.distinct()
        val input = android.widget.EditText(this).apply {
            hint = "输入单号（应用到 ${group.items.size} 条）"
            if (current.size == 1) setText(current[0])
            if (text.isNotEmpty()) setSelection(text.length)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(if (isUnassigned) "未分配记录 · 单号" else "托盘 ${group.trayCode} · 单号")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val slipNo = input.text.toString().trim()
                if (slipNo.isEmpty()) {
                    Toast.makeText(this, "单号不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                // 未分配组的真实托盘码是空串，WHERE tray_code = '' 正好就是这组记录。
                val realTray = if (isUnassigned) "" else group.trayCode
                DbExecutor.run({
                    RecordStore.updateTraySlipNo(this@RecordListActivity, realTray, slipNo)
                    SlipStore.setCurrent(this@RecordListActivity, slipNo)
                    group.items.size
                }) { result ->
                    result.onSuccess { count ->
                        Toast.makeText(
                            this@RecordListActivity,
                            "已将 $count 条记录记到单号 $slipNo",
                            Toast.LENGTH_SHORT,
                        ).show()
                        refresh()
                    }.onFailure {
                        Toast.makeText(this@RecordListActivity, "保存失败：${it.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 编辑托盘号：未分配组 = 批量分配；已分配组 = 整体改名 */
    private fun showEditTrayDialog(group: TrayGroup, isUnassigned: Boolean) {
        val input = android.widget.EditText(this).apply {
            hint = if (isUnassigned) {
                "输入托盘号（应用到 ${group.items.size} 条）"
            } else {
                "输入新托盘号（${group.items.size} 条）"
            }
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(if (isUnassigned) "分配托盘号" else "编辑托盘号")
            .setView(input)
            .setPositiveButton(if (isUnassigned) "分配" else "保存") { _, _ ->
                val tray = input.text.toString().trim()
                if (tray.isEmpty()) {
                    Toast.makeText(this, "托盘号不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val oldTray = group.trayCode
                // 改托盘号：加载+修改+全量保存整段在后台完成
                DbExecutor.run({
                    val full = RecordStore.load(this@RecordListActivity).toMutableList()
                    var count = 0
                    for (r in full) {
                        val matches = if (isUnassigned) r.trayCode.isBlank() else r.trayCode == oldTray
                        if (matches) {
                            r.trayCode = tray
                            count++
                        }
                    }
                    RecordStore.save(this@RecordListActivity, full)
                    count
                }) { result ->
                    result.onSuccess { count ->
                        checked.remove(oldTray)
                        Toast.makeText(
                            this@RecordListActivity,
                            if (isUnassigned) "已分配 $count 条记录到托盘 $tray"
                            else "已将 $count 条记录改到托盘 $tray",
                            Toast.LENGTH_SHORT,
                        ).show()
                        refresh()
                    }.onFailure {
                        Toast.makeText(this@RecordListActivity, "保存失败：${it.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 删除整个托盘（含其下全部记录），二次确认防误删 */
    private fun showDeleteTrayDialog(group: TrayGroup, isUnassigned: Boolean) {
        val count = group.items.size
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("删除托盘")
            .setMessage(
                if (isUnassigned) "将删除全部未分配记录（$count 条），此操作不可恢复！"
                else "将删除托盘 ${group.trayCode} 及其全部 $count 条记录，此操作不可恢复！",
            )
            .setPositiveButton("删除") { _, _ ->
                // 加载+过滤+全量保存整段在后台完成，防主线程卡顿
                DbExecutor.run({
                    val full = RecordStore.load(this@RecordListActivity).toMutableList()
                    val kept = full.filterNot {
                        if (isUnassigned) it.trayCode.isBlank() else it.trayCode == group.trayCode
                    }
                    RecordStore.save(this@RecordListActivity, kept)
                }) { result ->
                    result.onSuccess {
                        checked.remove(group.trayCode)
                        Toast.makeText(this@RecordListActivity, "已删除托盘，共 $count 条记录", Toast.LENGTH_SHORT).show()
                        refresh()
                    }.onFailure {
                        Toast.makeText(this@RecordListActivity, "删除失败：${it.message}", Toast.LENGTH_SHORT).show()
                    }
                }
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
            // 删除单条：加载+过滤+保存整段后台完成
            DbExecutor.run({
                val full = RecordStore.load(this@RecordListActivity).toMutableList()
                full.removeAll { it.serialNumber == toDelete.serialNumber && it.trayCode == toDelete.trayCode }
                RecordStore.save(this@RecordListActivity, full)
            }) { result ->
                result.onSuccess {
                    Toast.makeText(this@RecordListActivity, "已删除 ${toDelete.serialNumber}", Toast.LENGTH_SHORT).show()
                    refresh()
                }.onFailure {
                    Toast.makeText(this@RecordListActivity, "删除失败：${it.message}", Toast.LENGTH_SHORT).show()
                }
            }
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

    /** 导出选中托盘：按单号归集打包 ZIP（WMS 表 + 单据照片），多单号就提示分开导。 */
    private fun exportSelected() {
        val selected = groups.filter { it.trayCode in checked }.flatMap { it.items }
        if (selected.isEmpty()) {
            Toast.makeText(this, "请先勾选要导出的托盘", Toast.LENGTH_SHORT).show()
            return
        }
        val slipNos = selected.map { it.slipNo.trim() }.filter { it.isNotBlank() }.distinct()
        when {
            slipNos.size > 1 -> {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("多个单号")
                    .setMessage("勾选的托盘属于多个单号：${slipNos.joinToString("、")}，请按单号分开导出。")
                    .setPositiveButton("知道了", null)
                    .show()
            }
            slipNos.size == 1 -> showZipNameDialog(slipNos[0], selected)
            else -> showNoSlipDialog(selected)
        }
    }

    /** ZIP 名默认单号，可改；文件名里的单号不变（老板按单号核对）。 */
    private fun showZipNameDialog(slipNo: String, selected: List<LabelResult>) {
        val input = android.widget.EditText(this).apply {
            setText(slipNo)
            if (text.isNotEmpty()) setSelection(text.length)
            hint = "压缩包名"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("导出 ZIP（单号 $slipNo）")
            .setView(input)
            .setPositiveButton("打包分享") { _, _ ->
                val zipName = input.text.toString().trim()
                if (zipName.isEmpty()) {
                    Toast.makeText(this, "压缩包名不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                shareZip(selected, slipNo, zipName)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 打包分享 ZIP：WMS 表 + 该单号的单据照片（有照片时）+ 单据附件（表格等单独文件）。 */
    private fun shareZip(selected: List<LabelResult>, slipNo: String, zipName: String) {
        val photoFile = SlipStore.getSlip(this, slipNo)
            ?.photoPath?.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.exists() }
        val extraFiles = SlipStore.getFiles(this, slipNo)
            .map { File(it.filePath) }
            .filter { it.exists() }
        val uri = Exporter.exportWmsZip(this, selected, zipName, slipNo, photoFile, extraFiles)
        if (uri == null) {
            Toast.makeText(this, "ZIP 导出失败", Toast.LENGTH_SHORT).show()
            return
        }
        val trayCount = selected.map { it.trayCode }.distinct().size
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(
                Intent.EXTRA_SUBJECT,
                "WMS库存导入数据（退货单 $slipNo，${trayCount}个托盘 ${selected.size}条）"
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "导出 WMS 数据（ZIP）"))
    }

    /** 勾选的托盘还没单号：输单号打包 / 现拍单据 / 直接导旧格式 xlsx。 */
    private fun showNoSlipDialog(selected: List<LabelResult>) {
        val input = android.widget.EditText(this).apply {
            hint = "输入单号"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("这些托盘还没有单号")
            .setView(input)
            .setPositiveButton("打包") { _, _ ->
                val slipNo = input.text.toString().trim()
                if (slipNo.isEmpty()) {
                    Toast.makeText(this, "请先输入单号或拍单据", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                bindTraysAndShareZip(selected, slipNo)
            }
            .setNeutralButton("拍单据") { _, _ ->
                pendingExportRecords = selected
                val tmp = SlipStore.newTempPhotoFile(this)
                pendingSlipPhoto = tmp
                takeSlipPhoto.launch(
                    FileProvider.getUriForFile(this, "${packageName}.fileprovider", tmp)
                )
            }
            .setNegativeButton("直接导出旧格式") { _, _ -> exportLegacy(selected) }
            .show()
    }

    /** 勾选托盘绑定单号（并设为当前单据），再弹 ZIP 名确认打包。 */
    private fun bindTraysAndShareZip(selected: List<LabelResult>, slipNo: String) {
        val trayCodes = selected.map { it.trayCode }.distinct()
        DbExecutor.run({
            trayCodes.forEach { RecordStore.updateTraySlipNo(this@RecordListActivity, it, slipNo) }
            SlipStore.setCurrent(this@RecordListActivity, slipNo)
        }) { result ->
            result.onSuccess {
                selected.forEach { if (it.slipNo.isBlank()) it.slipNo = slipNo }
                showZipNameDialog(slipNo, selected)
            }.onFailure {
                Toast.makeText(this@RecordListActivity, "绑定单号失败：${it.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 旧格式：单个 WMS xlsx，不带单号（兼容以前的导出方式）。 */
    private fun exportLegacy(selected: List<LabelResult>) {
        val uri = Exporter.exportWms(this, selected)
        if (uri == null) {
            Toast.makeText(this, "WMS 导出失败", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "WMS库存导入数据（${checked.size}个托盘 ${selected.size}条）")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "导出 WMS 数据"))
    }
}