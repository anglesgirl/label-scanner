package com.anglesgirl.labelscanner

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.anglesgirl.labelscanner.camera.StaticRecognizer
import com.anglesgirl.labelscanner.data.Barcode69Lookup
import com.anglesgirl.labelscanner.data.RecordStore
import com.anglesgirl.labelscanner.model.BoxParser
import com.anglesgirl.labelscanner.model.LabelResult
import com.anglesgirl.labelscanner.util.TrayPrefs
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import java.io.File

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
    private lateinit var etBox: EditText
    private lateinit var etDate: EditText
    private lateinit var etModel: EditText
    private lateinit var etManualSn: EditText
    private lateinit var etTrayCode: EditText
    private lateinit var llSnList: LinearLayout
    private lateinit var llCodeCandidates: LinearLayout
    private lateinit var tvBoxStatus: TextView

    private val snList = mutableListOf<String>()
    private val codeCandidates = mutableListOf<String>()
    private val lookup69Lazy = lazy { Barcode69Lookup(this) }
    private fun lookup69(): Barcode69Lookup = lookup69Lazy.value

    /** 字段补扫目标字段引用 */
    private var scanTargetField: EditText? = null
    /** 补扫结果加入 SN 列表（而非填单个字段） */
    private var scanAppendToSn = false

    private var pendingPhotoUri: Uri? = null
    private var photoFile: File? = null

    /** 字段补扫：实时扫码相机 → 确认框 → 填目标框（不拍照，自动识别） */
    private val liveScan = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val code = result.data?.getStringExtra(LiveScanActivity.EXTRA_RESULT_CODE)
            if (code != null) onScannedCode(code)
        }
    }

    private fun onScannedCode(code: String) {
        if (scanAppendToSn) {
            if (code !in snList) {
                snList.add(code)
                rebuildSnList()
                tvBoxStatus.text = "✅ 已加入序列号: $code"
            } else {
                tvBoxStatus.text = "⚠️ 序列号已存在: $code"
            }
        } else {
            scanTargetField?.setText(code)
            tvBoxStatus.text = "✅ 已填入: $code（可手动修改）"
        }
    }

    /** 拍照（系统相机） */
    private val takePhoto = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.getStringExtra(com.anglesgirl.labelscanner.camera.CaptureActivity.EXTRA_OUTPUT_URI)
                ?.let { recognizeLabel(Uri.parse(it)) }
        }
    }

    /** 文档扫描（ML Kit，自动找边/裁切/增强） */
    private val scanDoc = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            GmsDocumentScanningResult.fromActivityResultIntent(result.data)?.pages
                ?.firstOrNull()?.imageUri?.let { recognizeLabel(it) }
        }
    }

    /** 相册选图 */
    private val pickGallery = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) recognizeLabel(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge：状态栏/导航栏不留黑色遮罩
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
        setContentView(R.layout.activity_single_box)

        etMaterial = findViewById(R.id.etMaterial)
        etBox = findViewById(R.id.etBox)
        etDate = findViewById(R.id.etDate)
        etModel = findViewById(R.id.etModel)
        etManualSn = findViewById(R.id.etManualSn)
        etTrayCode = findViewById(R.id.etTrayCode)
        llSnList = findViewById(R.id.llSnList)
        llCodeCandidates = findViewById(R.id.llCodeCandidates)
        tvBoxStatus = findViewById(R.id.tvBoxStatus)

        findViewById<Button>(R.id.btnTakePhoto).setOnClickListener { launchCamera() }
        findViewById<Button>(R.id.btnScanDoc).setOnClickListener { launchDocScan() }
        findViewById<Button>(R.id.btnPickGallery).setOnClickListener { pickGallery.launch("image/*") }
        findViewById<Button>(R.id.btnAddSn).setOnClickListener { addManualSn() }
        findViewById<Button>(R.id.btnSaveBox).setOnClickListener { saveBox() }
        findViewById<Button>(R.id.btnResetBox).setOnClickListener { resetBox() }

        // 托盘号：采集开始时填一次，整批沿用（保存/清空都不重置，换托盘时手动改）
        etTrayCode.setText(TrayPrefs.get(this))
        etTrayCode.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                TrayPrefs.set(this@SingleBoxInboundActivity, s?.toString()?.trim().orEmpty())
            }
        })

        // 字段补扫按钮：弹实时扫码相机 → 确认框 → 填入（不拍照后识别）
        val scanMap = mapOf(
            R.id.btnScanTrayCode to (etTrayCode to "托盘号"),
            R.id.btnScanMaterial to (etMaterial to "物料编码"),
            R.id.btnScanBox to (etBox to "箱号"),
            R.id.btnScanDate to (etDate to "生产日期"),
            R.id.btnScanModel to (etModel to "型号"),
        )
        for ((btnId, pair) in scanMap) {
            val field = pair.first
            val label = pair.second
            findViewById<Button>(btnId).setOnClickListener {
                scanAppendToSn = false
                scanTargetField = field
                liveScan.launch(
                    Intent(this, LiveScanActivity::class.java)
                        .putExtra(LiveScanActivity.EXTRA_TITLE, label)
                )
            }
        }
        findViewById<Button>(R.id.btnScanSn).setOnClickListener {
            scanAppendToSn = true
            liveScan.launch(
                Intent(this, LiveScanActivity::class.java)
                    .putExtra(LiveScanActivity.EXTRA_TITLE, "序列号")
            )
        }

        rebuildSnList()
        rebuildCodeCandidates()
        updateStatus()
    }

    /** 系统相机拍照 → 全分辨率存 captures/ → 识别 */
    private fun launchCamera() {
        takePhoto.launch(Intent(this, com.anglesgirl.labelscanner.camera.CaptureActivity::class.java))
    }

    /** ML Kit 文档扫描（GMS；自动找边/裁切/增强，标签拍摄最佳） */
    private fun launchDocScan() {
        try {
            val options = GmsDocumentScannerOptions.Builder()
                .setGalleryImportAllowed(true)   // 也允许从相册导入文档
                .setPageLimit(1)
                // FULL 模式（最强）：自动找边 + 裁切 + 透视矫正 + 图像增强。
                // 对 PDF417 集成码/密集条码至关重要——系统相机拍照有透视变形，
                // 二维堆叠码对透视极敏感，矫正后才能稳定解出。
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                .build()
            GmsDocumentScanning.getClient(options).getStartScanIntent(this)
                .addOnSuccessListener { intentSender ->
                    scanDoc.launch(
                        androidx.activity.result.IntentSenderRequest.Builder(intentSender).build()
                    )
                }
                .addOnFailureListener { e ->
                    Toast.makeText(this, "文档扫描不可用（设备无 Google 服务?）:\n${e.message}", Toast.LENGTH_LONG).show()
                }
        } catch (e: Exception) {
            Toast.makeText(this, "文档扫描不可用: ${e.message}", Toast.LENGTH_LONG).show()
        }
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
                    etBox.setText(box.boxCode)
                    etDate.setText(box.productionDate)
                    etModel.setText(box.model)
                    snList.clear()
                    snList.addAll(box.serialNumbers)
                    rebuildSnList()
                    codeCandidates.clear()
                    codeCandidates.addAll(result.barcodes)
                    rebuildCodeCandidates()

                    val tips = mutableListOf<String>()
                    if (box.materialCode.isBlank()) tips.add("⚠️ 未识别到物料(SAP)，请手动输入")
                    if (box.boxCode.isBlank()) tips.add("⚠️ 未识别到箱号，请手动输入")
                    if (box.productionDate.isBlank()) tips.add("⚠️ 未识别到日期，请手动输入")
                    if (snList.isEmpty()) tips.add("⚠️ 未识别到序列号，请手动添加")
                    tvBoxStatus.text = "✅ 识别完成：物料=${box.materialCode.ifBlank { "?" }} 箱号=${box.boxCode.ifBlank { "?" }} SN×${snList.size}\n${tips.joinToString("\n")}"

                    // 物料空但有条码 → 远程反查（Turso 库）
                    if (box.materialCode.isBlank()) {
                        val ean = result.barcodes.firstOrNull { it.length == 13 && it.startsWith("69") }
                            ?: box.ean69
                        if (ean.isNotBlank()) {
                            lookup69().lookupRemote(ean) { material ->
                                runOnUiThread {
                                    if (material != null && etMaterial.text.toString().isBlank()) {
                                        etMaterial.setText(material)
                                        tvBoxStatus.text = "🔁 69码远程反查物料: $material（可修改）"
                                    }
                                }
                            }
                        }
                    }
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
            rebuildSnList()
            etManualSn.setText("")
            updateStatus()
        } else {
            Toast.makeText(this, "序列号已存在", Toast.LENGTH_SHORT).show()
        }
    }

    /** 保存：每个 SN 展开为一条记录（共享物料/LPN/日期/型号），去重后追加 */
    private fun saveBox() {
        val material = etMaterial.text.toString().trim()
        val box = etBox.text.toString().trim()
        val date = etDate.text.toString().trim()
        val model = etModel.text.toString().trim()
        val tray = etTrayCode.text.toString().trim()

        if (snList.isEmpty()) {
            Toast.makeText(this, "序列号列表为空，无法保存", Toast.LENGTH_SHORT).show()
            return
        }
        if (material.isEmpty()) {
            Toast.makeText(this, "物料编码为空（识别不到请手动输入）", Toast.LENGTH_SHORT).show()
            return
        }
        if (box.isEmpty()) {
            Toast.makeText(this, "箱号为空（识别不到请手动输入）", Toast.LENGTH_SHORT).show()
            return
        }
        if (tray.isEmpty()) {
            Toast.makeText(this, "托盘号必填（扫描或输入托盘码）", Toast.LENGTH_SHORT).show()
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
                boxCode = box,
                trayCode = tray,
            )
        }
        RecordStore.append(this, records)
        tvBoxStatus.text = "✅ 已保存 ${records.size} 条（物料 $material / 箱号 $box）"
        Toast.makeText(this, "已保存 ${records.size} 条记录", Toast.LENGTH_SHORT).show()
        resetBox()
    }

    private fun resetBox() {
        // 托盘号保留（整批沿用），其余清空
        etMaterial.setText("")
        etBox.setText("")
        etDate.setText("")
        etModel.setText("")
        etManualSn.setText("")
        snList.clear()
        codeCandidates.clear()
        rebuildSnList()
        rebuildCodeCandidates()
        updateStatus()
    }

    private fun updateStatus() {
        val n = snList.size
        tvBoxStatus.text = if (n == 0) "序列号 0 个" else "📦 序列号 $n 个，保存后每 SN 一行"
    }

    /** 重建 SN 行列表（LinearLayout 动态加行，避免 RecyclerView 在 ScrollView 内显示不全） */
    private fun rebuildSnList() {
        llSnList.removeAllViews()
        for ((index, sn) in snList.withIndex()) {
            val row = LayoutInflater.from(this).inflate(R.layout.item_sn_row, llSnList, false)
            row.findViewById<TextView>(R.id.tvSnItem).text = "${index + 1}. $sn"
            row.findViewById<Button>(R.id.btnDelSn).setOnClickListener {
                snList.remove(sn)
                rebuildSnList()
                updateStatus()
            }
            llSnList.addView(row)
        }
        updateStatus()
    }

    /** 重建「已识别条码」候选区：点击任一码 → 弹选择用途（修正识别错误） */
    private fun rebuildCodeCandidates() {
        llCodeCandidates.removeAllViews()
        if (codeCandidates.isEmpty()) return
        for ((index, code) in codeCandidates.withIndex()) {
            val row = LayoutInflater.from(this).inflate(R.layout.item_sn_row, llCodeCandidates, false)
            val tv = row.findViewById<TextView>(R.id.tvSnItem)
            tv.text = "$code"
            tv.setTextColor(0xFF1B6EF3.toInt())
            row.findViewById<Button>(R.id.btnDelSn).text = "选"
            row.findViewById<Button>(R.id.btnDelSn).setOnClickListener { showCodeActionDialog(code) }
            row.setOnClickListener { showCodeActionDialog(code) }
            llCodeCandidates.addView(row)
        }
    }

    /** 条码用途选择：修正识别错误的入口 */
    private fun showCodeActionDialog(code: String) {
        AlertDialog.Builder(this)
            .setTitle("条码: $code")
            .setItems(
                arrayOf(
                    "📦 设为箱号",
                    "🏷️ 设为物料编码",
                    "📅 设为生产日期",
                    "➕ 加入序列号列表",
                    "❌ 取消"
                )
            ) { _, which ->
                when (which) {
                    0 -> { etBox.setText(code); Toast.makeText(this, "箱号已设为 $code", Toast.LENGTH_SHORT).show() }
                    1 -> { etMaterial.setText(code); Toast.makeText(this, "物料已设为 $code", Toast.LENGTH_SHORT).show() }
                    2 -> { etDate.setText(code); Toast.makeText(this, "日期已设为 $code", Toast.LENGTH_SHORT).show() }
                    3 -> {
                        if (code !in snList) {
                            snList.add(code)
                            rebuildSnList()
                            Toast.makeText(this, "已加入序列号", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, "序列号已存在", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .show()
    }
}