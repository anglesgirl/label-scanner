package com.anglesgirl.labelscanner

import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.anglesgirl.labelscanner.camera.CameraCaptureMode
import com.anglesgirl.labelscanner.camera.ImageIn
import com.anglesgirl.labelscanner.camera.StaticRecognizer
import com.anglesgirl.labelscanner.data.DbExecutor
import com.anglesgirl.labelscanner.data.RecordStore
import com.anglesgirl.labelscanner.model.BatchHeaderParser
import com.anglesgirl.labelscanner.model.LabelParser
import com.anglesgirl.labelscanner.model.LabelResult
import com.anglesgirl.labelscanner.model.TrayCodeParser
import com.anglesgirl.labelscanner.util.LastSavedHint
import com.anglesgirl.labelscanner.util.TrayPrefs

/**
 * 双页清单录入：先识别物料/日期页，再逐页收集每个独立集成码。
 * 每个确认后的集成码单独保存为一条记录，共用同一组物料与生产日期。
 */
class ListBatchInboundActivity : AppCompatActivity() {
    private enum class CaptureStage { TRAY, HEADER, CODES }

    private lateinit var imageIn: ImageIn
    private lateinit var tvStatus: TextView
    private lateinit var tvTrayStatus: TextView
    private lateinit var tvTraySerialCount: TextView
    private lateinit var tvHeaderStatus: TextView
    private lateinit var tvCodesStatus: TextView
    private lateinit var tvCodeCounts: TextView
    private lateinit var etTrayCode: EditText
    private lateinit var etMaterial: EditText
    private lateinit var etDate: EditText
    private lateinit var etManualCode: EditText
    private lateinit var rvCodes: RecyclerView
    private lateinit var btnSave: Button
    private lateinit var btnSelectAll: Button

    private var captureStage = CaptureStage.HEADER
    private var recognizing = false
    private val items = mutableListOf<Item>()
    private val adapter = CodeAdapter()

    private data class Item(var value: String, var checked: Boolean, var saved: Boolean = false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_list_batch)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        // ActivityResult launchers must be registered during onCreate, before the page is resumed.
        imageIn = ImageIn(this) { uri -> onImage(uri, captureStage) }
        tvStatus = findViewById(R.id.tvBatchStatus)
        tvTrayStatus = findViewById(R.id.tvTrayStatus)
        tvTraySerialCount = findViewById(R.id.tvBatchTraySerialCount)
        tvHeaderStatus = findViewById(R.id.tvHeaderStatus)
        tvCodesStatus = findViewById(R.id.tvCodesStatus)
        tvCodeCounts = findViewById(R.id.tvBatchCodeCounts)
        etTrayCode = findViewById(R.id.etBatchTrayCode)
        etMaterial = findViewById(R.id.etBatchMaterial)
        etDate = findViewById(R.id.etBatchDate)
        etManualCode = findViewById(R.id.etManualCode)
        rvCodes = findViewById(R.id.rvBatchCodes)
        btnSave = findViewById(R.id.btnBatchSave)
        btnSelectAll = findViewById(R.id.btnBatchSelectAll)

        LastSavedHint.bind(this, findViewById(R.id.tvBatchLastSaved))
        etTrayCode.setText(TrayPrefs.get(this))
        etTrayCode.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                TrayPrefs.set(this@ListBatchInboundActivity, s?.toString()?.trim().orEmpty())
                updateTraySerialCount()
            }
        })
        rvCodes.layoutManager = LinearLayoutManager(this)
        rvCodes.adapter = adapter

        bindImageButtons(CaptureStage.TRAY, R.id.btnTrayPhoto, R.id.btnTrayDoc, R.id.btnTrayGallery)
        bindImageButtons(CaptureStage.HEADER, R.id.btnHeaderPhoto, R.id.btnHeaderDoc, R.id.btnHeaderGallery)
        bindImageButtons(CaptureStage.CODES, R.id.btnCodesPhoto, R.id.btnCodesDoc, R.id.btnCodesGallery)

        findViewById<Button>(R.id.btnAddManualCode).setOnClickListener { addManualCode() }
        etManualCode.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP
            if (actionId == EditorInfo.IME_ACTION_DONE || enter) {
                addManualCode()
                true
            } else false
        }
        btnSelectAll.setOnClickListener {
            val pendingItems = items.filterNot { it.saved }
            val allChecked = pendingItems.isNotEmpty() && pendingItems.all { it.checked }
            pendingItems.forEach { it.checked = !allChecked }
            adapter.notifyDataSetChanged()
            updateCounts()
        }
        btnSave.setOnClickListener { saveChecked() }
        updateCounts()
    }

    override fun onResume() {
        super.onResume()
        if (::etTrayCode.isInitialized) updateTraySerialCount()
    }

    private fun updateTraySerialCount() {
        if (!::tvTraySerialCount.isInitialized || !::etTrayCode.isInitialized) return
        val trayCode = etTrayCode.text.toString().trim()
        if (trayCode.isBlank()) {
            tvTraySerialCount.text = "当前托盘序列号：请先填写托盘号"
            return
        }
        tvTraySerialCount.text = "托盘 $trayCode：正在查询已保存序列号…"
        DbExecutor.run({ RecordStore.countSerialNumbersByTrayCode(this, trayCode) }) { result ->
            // 输入变化后忽略排队中的旧查询结果，避免旧托盘计数覆盖当前托盘。
            if (etTrayCode.text.toString().trim() != trayCode) return@run
            result.onSuccess { count ->
                tvTraySerialCount.text = "托盘 $trayCode：已有序列号 $count 个（含单箱入库）"
            }.onFailure {
                tvTraySerialCount.text = "托盘 $trayCode：序列号数查询失败"
            }
        }
    }

    private fun bindImageButtons(stage: CaptureStage, photoId: Int, docId: Int, galleryId: Int) {
        findViewById<Button>(photoId).setOnClickListener {
            captureStage = stage
            imageIn.takePhoto(cameraMode(stage))
        }
        findViewById<Button>(docId).setOnClickListener {
            captureStage = stage
            imageIn.scanDocument()
        }
        findViewById<Button>(galleryId).setOnClickListener {
            captureStage = stage
            imageIn.pickGallery()
        }
    }

    private fun cameraMode(stage: CaptureStage) = when (stage) {
        CaptureStage.TRAY -> CameraCaptureMode.LIST_TRAY
        CaptureStage.HEADER -> CameraCaptureMode.LIST_HEADER
        CaptureStage.CODES -> CameraCaptureMode.LIST_CODES
    }

    private fun onImage(uri: Uri, stage: CaptureStage) {
        if (recognizing) return
        recognizing = true
        if (stage == CaptureStage.HEADER) {
            etMaterial.setText("")
            etDate.setText("")
        }
        tvStatus.text = if (stage == CaptureStage.HEADER) {
            "正在识别物料与日期…"
        } else if (stage == CaptureStage.TRAY) {
            "正在识别托盘信息页上的条码…"
        } else {
            "正在识别本页全部条码…"
        }
        StaticRecognizer.recognizeUriMulti(
            resolver = contentResolver,
            uri = uri,
            onResult = { codes, ocrText -> runOnUiThread { showResult(stage, codes, ocrText) } },
            onEvidence = { evidence -> runOnUiThread {
                showResult(stage, evidence.barcodes, evidence.ocrText, evidence.frameCount)
            } },
            onError = { message -> runOnUiThread {
                recognizing = false
                tvStatus.text = "识别失败：$message"
                Toast.makeText(this, "识别失败：$message", Toast.LENGTH_LONG).show()
            } },
        )
    }

    private fun showResult(
        stage: CaptureStage,
        codes: List<String>,
        ocrText: String,
        frameCount: Int? = null,
    ) {
        recognizing = false
        if (stage == CaptureStage.TRAY) {
            val candidates = TrayCodeParser.candidates(codes, ocrText)
            if (candidates.isEmpty()) {
                tvTrayStatus.text = "未识别到托盘条码；可重新拍摄、从相册导入，或用扫码枪直接输入"
                tvStatus.text = "托盘码未识别，请手动补录后再保存。"
                return
            }
            val preferred = TrayCodeParser.preferred(candidates)
            if (preferred != null) {
                etTrayCode.setText(preferred)
                tvTrayStatus.text = if (candidates.size == 1) {
                    "已识别托盘码：$preferred（请核对）"
                } else {
                    "已优先填入托盘/SSCC 候选：$preferred；另有 ${candidates.size - 1} 个码未选，请核对"
                }
            } else {
                val ordered = TrayCodeParser.prioritize(candidates)
                AlertDialog.Builder(this)
                    .setTitle("请选择托盘码")
                    .setItems(ordered.toTypedArray()) { _, which ->
                        val selected = ordered[which]
                        etTrayCode.setText(selected)
                        tvTrayStatus.text = "已选择托盘码：$selected（请核对）"
                    }
                    .setNegativeButton("取消", null)
                    .show()
                tvTrayStatus.text = "识别到 ${candidates.size} 个候选码，请选择托盘码"
            }
            tvStatus.text = "托盘码已更新。确认托盘码后，再核对物料与生产日期并收集集成码。"
            return
        }
        if (stage == CaptureStage.HEADER) {
            val parsed = LabelParser.parse(codes, ocrText)
            val material = BatchHeaderParser.extractMaterialCode(ocrText)
            val date = BatchHeaderParser.extractProductionDate(ocrText)
                ?: parsed.productionDate.takeIf { it.isNotBlank() && it != "19000101" }
            if (!material.isNullOrBlank()) etMaterial.setText(material)
            if (!date.isNullOrBlank()) etDate.setText(date)
            tvHeaderStatus.text = when {
                !material.isNullOrBlank() && !date.isNullOrBlank() -> "已识别：$material · $date（请核对）"
                !material.isNullOrBlank() -> "已识别物料：$material；请确认或补填生产日期"
                !date.isNullOrBlank() -> "已识别日期：$date；请确认或补填物料编码"
                else -> "未能可靠识别字段，请手动填写物料编码和生产日期"
            }
            tvStatus.text = "基础信息已更新。确认物料和日期后，扫描集成码页；日期可手动修正。"
            return
        }

        val material = etMaterial.text.toString().trim()
        val newCodes = codes.map(String::trim).filter(String::isNotBlank).distinct()
        var added = 0
        var metadata = 0
        for (code in newCodes) {
            if (items.any { it.value.trim() == code }) continue
            val isMetadata = BatchHeaderParser.isLikelyHeaderCode(code, material)
            if (isMetadata) metadata++
            items += Item(code, checked = !isMetadata)
            added++
        }
        adapter.notifyDataSetChanged()
        updateCounts()
        val sampleNote = frameCount?.takeIf { it > 1 }?.let { "本次依据 $it 帧采样结果" }
        tvCodesStatus.text = if (newCodes.isEmpty()) {
            "本页没有解出条码；可换文档扫描、重新拍照，或用下方扫码枪/手工输入"
        } else {
            "本页读到 ${newCodes.size} 个不同码，新增 $added 个；清单总数 ${items.size} 个。自动勾选序列号候选，$metadata 个疑似辅助码默认未勾选，请核对。" +
                (sampleNote?.let { "\n$it" } ?: "")
        }
        tvStatus.text = if (added > 0) {
            "清单累计 ${items.size} 个码。可继续添加下一页，也可手动补码。"
        } else if (newCodes.isEmpty()) {
            "没有新增条码；可以调整拍摄角度或用扫码枪/手工输入。"
        } else {
            "本页的码已在清单中，未重复添加。"
        }
    }

    private fun addManualCode() {
        val code = etManualCode.text.toString().trim()
        if (code.isBlank()) {
            Toast.makeText(this, "请先扫描或输入集成码", Toast.LENGTH_SHORT).show()
            return
        }
        val existing = items.indexOfFirst { it.value.trim() == code }
        if (existing >= 0) {
            if (items[existing].saved) {
                tvStatus.text = "该码已在本批保存，未重复添加。"
            } else {
                items[existing].checked = true
                adapter.notifyItemChanged(existing)
                tvStatus.text = "该码已在清单中，已重新勾选。"
            }
        } else {
            items += Item(code, checked = true)
            adapter.notifyItemInserted(items.lastIndex)
            rvCodes.scrollToPosition(items.lastIndex)
            tvStatus.text = "已手工加入 1 个集成码。"
        }
        etManualCode.setText("")
        updateCounts()
    }

    private fun saveChecked() {
        val trayCode = etTrayCode.text.toString().trim()
        val material = etMaterial.text.toString().trim()
        val dateInput = etDate.text.toString().trim()
        val date = LabelParser.normalizeDate(dateInput)
        if (trayCode.isBlank()) {
            Toast.makeText(this, "托盘码不能为空；请先扫描托盘信息页或手动填写", Toast.LENGTH_LONG).show()
            return
        }
        if (material.isBlank()) {
            Toast.makeText(this, "物料编码不能为空；请先识别基础页或手动填写", Toast.LENGTH_LONG).show()
            return
        }
        if (dateInput.isBlank() || date == "19000101" || !Regex("^\\d{8}$").matches(date)) {
            Toast.makeText(this, "生产日期格式不正确，请填写有效日期（如 20260919 或 2026/09/19）", Toast.LENGTH_LONG).show()
            return
        }
        val checked = items.filter { it.checked && !it.saved && it.value.isNotBlank() }
        if (checked.isEmpty()) {
            Toast.makeText(this, "没有待保存的已勾选集成码", Toast.LENGTH_SHORT).show()
            return
        }
        val savedSerialNumbers = items.filter { it.saved }
            .flatMap { IntegratedCode.serialNumbers(it.value) }
            .toSet()
        val serialNumbers = IntegratedCode.serialNumbersToSave(
            values = checked.map { it.value },
            alreadySaved = savedSerialNumbers,
        )
        if (serialNumbers.isEmpty()) {
            Toast.makeText(this, "勾选内容中没有可保存的序列号", Toast.LENGTH_SHORT).show()
            return
        }
        val records = serialNumbers.map { serial ->
            LabelResult(
                barcodes = listOf(serial),
                ocrText = "",
                supplier = "NA",
                serialNumber = serial,
                materialCode = material,
                quantity = 1,
                productionDate = date,
                trayCode = trayCode,
            )
        }
        val selectedIntegratedCodeCount = checked.count { IntegratedCode.isPayload(it.value) }
        btnSave.isEnabled = false
        DbExecutor.run({ RecordStore.append(this@ListBatchInboundActivity, records) }) { result ->
            btnSave.isEnabled = true
            result.onSuccess {
                // Keep the final scan list visible, but never save these rows twice.
                checked.forEach { it.saved = true }
                adapter.notifyDataSetChanged()
                updateCounts()
                LastSavedHint.refresh(this@ListBatchInboundActivity, findViewById(R.id.tvBatchLastSaved))
                updateTraySerialCount()
                Toast.makeText(
                    this@ListBatchInboundActivity,
                    "已保存 ${records.size} 个序列号（来自 ${checked.size} 个勾选码，其中集成码 $selectedIntegratedCodeCount 个）",
                    Toast.LENGTH_LONG,
                ).show()
                tvStatus.text = "已保存 ${records.size} 个独立序列号；托盘码已写入每条记录。清单保留本批内容，可继续逐页扫描。"
            }.onFailure { error ->
                Toast.makeText(this@ListBatchInboundActivity, "保存失败：${error.message}", Toast.LENGTH_LONG).show()
                tvStatus.text = "保存失败：${error.message}"
            }
        }
    }

    private fun updateCounts() {
        val integratedCodeCount = items.count { IntegratedCode.isPayload(it.value.trim()) }
        val allSerialNumbers = items.flatMap { IntegratedCode.serialNumbers(it.value) }.distinct()
        val savedSerialNumbers = items.filter { it.saved }
            .flatMap { IntegratedCode.serialNumbers(it.value) }.distinct()
        val pendingSerialNumbers = items.filter { it.checked && !it.saved }
            .flatMap { IntegratedCode.serialNumbers(it.value) }
            .filterNot { it in savedSerialNumbers }
            .distinct()
        tvCodeCounts.text = "已识别集成码 $integratedCodeCount 个 · 拆分后 ${allSerialNumbers.size} 个序列号\n已保存 ${savedSerialNumbers.size} 个序列号 · 待保存 ${pendingSerialNumbers.size} 个"
        btnSave.text = "保存 ${pendingSerialNumbers.size} 个序列号"
        btnSave.isEnabled = pendingSerialNumbers.isNotEmpty()
        val pendingItems = items.filterNot { it.saved }
        val allChecked = pendingItems.isNotEmpty() && pendingItems.all { it.checked }
        btnSelectAll.text = if (allChecked) "取消全选" else "全选"
    }

    private inner class CodeAdapter : RecyclerView.Adapter<CodeAdapter.VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_multi_code, parent, false)
            return VH(view)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.checkBox.setOnCheckedChangeListener(null)
            holder.checkBox.isChecked = item.checked
            holder.checkBox.isEnabled = !item.saved
            holder.code.setText(item.value)
            holder.code.setSingleLine(false)
            holder.code.maxLines = 3
            holder.meta.text = codeSummary(item)
            holder.code.isEnabled = !item.saved
            holder.itemView.alpha = if (item.saved) 0.58f else 1f
            holder.code.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    item.value = holder.code.text.toString().trim()
                    holder.meta.text = codeSummary(item)
                    updateCounts()
                }
            }
            holder.checkBox.setOnCheckedChangeListener { _, checked ->
                item.checked = checked
                updateCounts()
            }
        }

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val checkBox: CheckBox = view.findViewById(R.id.cbCode)
            val code: EditText = view.findViewById(R.id.etCode)
            val meta: TextView = view.findViewById(R.id.tvCodeMeta)
        }
    }

    private fun codeSummary(item: Item): String {
        val serialCount = IntegratedCode.serialNumbers(item.value).size
        val kind = if (IntegratedCode.isPayload(item.value)) "集成码 · 拆分 $serialCount 个 SN" else "单码 · $serialCount 个 SN"
        return "$kind · ${if (item.saved) "已保存" else "待保存"}"
    }
}
