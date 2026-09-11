package com.anglesgirl.labelscanner

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
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
import com.anglesgirl.labelscanner.util.BarcodeGenerator
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import java.io.File

/**
 * 🧩 集成码拆分：扫/粘贴集成码（多个 SN 逗号分隔）→ 按逗号拆分 →
 * 每个 SN 生成独立【条码】（Code128，下方自带内容文本）→ 保存到
 * 相册供打印贴标。
 *
 * 识别入口与单箱入库一致：拍照 / 文档扫描 / 相册 三选 + 手动粘贴兜底。
 * 拆分规则与 BoxParser 一致：逗号/分号/空白 分隔，去重，短 token 丢弃。
 */
class SplitCodeActivity : AppCompatActivity() {

    private lateinit var etManualCode: EditText
    private lateinit var tvSourceCode: TextView
    private lateinit var llSplitResult: LinearLayout
    private lateinit var tvSplitStatus: TextView

    /** 已拆分的 SN 列表（有序去重） */
    private val snList = mutableListOf<String>()

    private var pendingPhotoUri: Uri? = null

    /** 多箱拆模式（每箱一行，全部完成后再出结果）。 */
    /** 多箱模式下每箱的集成码。 */
    private lateinit var llBoxes: LinearLayout
    private var btnAddBox: android.view.View? = null

    /**
     * 挑码取码：调起 LiveScanActivity 的挑码模式 —— 相机实时扫到多个码后
     * **列出让用户点选**，而不是像 bulkMode 那样扫到就自动返回。
     * 集成码常与其它码同框出现，必须让用户点，否则极易取错码。
     */
    /** 扫入指定的一箱（用普通单码扫描：扫到即返回，直接填进该行输入框）。 */
    private val scanInto = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        // 兼容挑码页整批返回与单码返回两种形式
        val list = data.getStringArrayListExtra(LiveScanActivity.EXTRA_RESULT_CODES)
            ?: data.getStringExtra(LiveScanActivity.EXTRA_RESULT_CODE)?.let { arrayListOf(it) }
            ?: return@registerForActivityResult
        if (list.isEmpty()) return@registerForActivityResult

        val idx = scanTargetRow
        if (idx >= 0 && idx < rows.size) {
            rows[idx].input.setText(list[0])
            rows[idx].check.isChecked = true
        } else {
            // 没有指定目标：依次填进空白箱，多出来的自动新增
            for (code in list) {
                val empty = rows.firstOrNull { it.input.text.isNullOrBlank() }
                if (empty != null) empty.input.setText(code) else addBoxRow().input.setText(code)
            }
        }
        tvSplitStatus.text = "已扫入，可继续扫下一箱"
    }

    /** 一箱一行：勾选 + 输入框。 */
    private inner class BoxRow(val check: CheckBox, val input: EditText)

    private val rows = mutableListOf<BoxRow>()

    /** 当前"扫"按钮要填的目标行下标。 */
    private var scanTargetRow = -1

    /** 新增一箱（返回该行，便于链式填值）。 */
    private fun addBoxRow(): BoxRow {
        val dp = resources.displayMetrics.density
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = (6 * dp).toInt() }
        }
        val check = CheckBox(this).apply { isChecked = true }
        val input = EditText(this).apply {
            hint = "集成码 ${rows.size + 1}（可扫可粘贴）"
            textSize = 13f
            // 集成码可能含几十个逗号分隔的 SN，铺开会把整个界面撑开。
            // 这里强制单行 + 中间省略，点一下弹窗看完整内容。
            setSingleLine(true)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            isFocusable = false          // 避免一点就弹软键盘挡住内容
            isClickable = true
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { showFullCode(this) }
        }
        val btnScan = Button(this).apply { text = "扫"; textSize = 12f }
        val btnDel = Button(this).apply { text = "✕"; textSize = 12f }

        val item = BoxRow(check, input)
        btnScan.setOnClickListener {
            scanTargetRow = rows.indexOf(item)
            scanInto.launch(
                Intent(this, LiveScanActivity::class.java)
                    .putExtra(LiveScanActivity.EXTRA_TITLE, "扫第 ${scanTargetRow + 1} 箱的集成码")
                    // 关键：声明本页要的是集成码。标签上同时有 1D 条码和 2D 集成码时，
                    // 1D 总被先解出来，不声明就只能靠手遮住别的码才扫得到集成码。
                    .putExtra(LiveScanActivity.EXTRA_WANT_INTEGRATED, true),
            )
        }
        btnDel.setOnClickListener {
            if (rows.size <= 1) {
                Toast.makeText(this, "至少保留一箱", Toast.LENGTH_SHORT).show()
            } else {
                rows.remove(item)
                llBoxes.removeView(container)
                renumberHints()
            }
        }
        container.addView(check)
        container.addView(input)
        container.addView(btnScan)
        container.addView(btnDel)
        llBoxes.addView(container)

        rows.add(item)
        renumberHints()
        return item
    }



    /** 取主题色（自动适配深浅模式）。 */
    private fun c(resId: Int): Int = androidx.core.content.ContextCompat.getColor(this, resId)

    /** 点输入框查看完整集成码（内容可能几十个 SN，单行显示放不下）。 */
    private fun showFullCode(edit: EditText) {
        val full = edit.text.toString()
        if (full.isBlank()) {
            Toast.makeText(this, "这一箱还没有集成码", Toast.LENGTH_SHORT).show()
            return
        }
        val count = splitCodes(listOf(full)).size
        AlertDialog.Builder(this)
            .setTitle("集成码（拆出 $count 个 SN）")
            .setMessage(full)
            .setPositiveButton("复制") { _, _ ->
                val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("集成码", full))
                Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 删箱后把提示语里的序号重排。 */
    private fun renumberHints() {
        rows.forEachIndexed { i, r -> r.input.hint = "集成码 ${i + 1}（可扫可粘贴）" }
    }

    /** 拆解选中的箱：按箱顺序汇总各自拆出的 SN，再逐个生成独立条码。 */
    private fun splitChecked() {
        val codes = rows.filter { it.check.isChecked }
            .map { it.input.text.toString().trim() }
            .filter { it.isNotEmpty() }
            .toMutableList()
        // 底部粘贴框若填了内容，也算作一箱（方便从别处整段粘贴）
        etManualCode.text.toString().trim().takeIf { it.isNotEmpty() }?.let { codes.add(it) }
        if (codes.isEmpty()) {
            Toast.makeText(this, "请至少在一个箱里填入集成码", Toast.LENGTH_SHORT).show()
            return
        }
        snList.clear()
        // 逐箱独立拆分：绝不把多箱拼成一个字符串再拆。
        // 集成码末位不带逗号，若直接首尾相接（"…尾" + "头…"）会粘成一个不存在的号，
        // 且拆分结果看起来"正常"，属于最难发现的那种错。将来若确需拼接，
        // 一律用 joinToString(",") 显式插入分隔符，禁止字符串相加。
        for (c in codes) snList.addAll(splitCodes(listOf(c)))
        tvSourceCode.text = "共 ${codes.size} 箱，拆出 ${snList.size} 个 SN"
        tvSourceCode.visibility = TextView.VISIBLE
        rebuildResultList()
        tvSplitStatus.text = "🧩 ${codes.size} 箱 → ${snList.size} 个 SN（已逐个生成独立条码）"
    }


    /** 拍照（系统相机） */
    private val takePhoto = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pendingPhotoUri?.let { recognizeLabel(it) }
        }
    }

    /** 文档扫描（ML Kit） */
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
        setContentView(R.layout.activity_split_code)

        etManualCode = findViewById(R.id.etManualCode)
        tvSourceCode = findViewById(R.id.tvSourceCode)
        llSplitResult = findViewById(R.id.llSplitResult)
        tvSplitStatus = findViewById(R.id.tvSplitStatus)
        llBoxes = findViewById(R.id.llBoxes)

        // 每箱一行：勾选 + 集成码输入框 + 扫 + 删（行由代码生成，默认给一箱）
        findViewById<Button>(R.id.btnAddBox).setOnClickListener { addBoxRow() }
        findViewById<Button>(R.id.btnSplit).setOnClickListener { splitChecked() }
        findViewById<Button>(R.id.btnSaveAll).setOnClickListener { saveAll() }
        findViewById<Button>(R.id.btnResetSplit).setOnClickListener { resetAll() }

        addBoxRow()
        tvSplitStatus.text = "每箱扫一个集成码；勾选要拆的箱，点「拆解选中的」一起拆"
    }

    /** 系统相机拍照 → captures/ → 识别 */





    private fun launchCamera() {
        try {
            val dir = File(cacheDir, "captures").apply { mkdirs() }
            val file = File(dir, "split_photo_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            pendingPhotoUri = uri
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            takePhoto.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "无法启动相机: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** ML Kit 文档扫描 */
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

    /** 识别图片 → 取条码 → 拆分 */
    private fun recognizeLabel(uri: Uri) {
        tvSplitStatus.text = "识别中..."
        StaticRecognizer.recognizeUri(
            resolver = contentResolver,
            uri = uri,
            lookup69 = null,
            onResult = { result ->
                runOnUiThread {
                    if (result.barcodes.isEmpty()) {
                        tvSplitStatus.text = "⚠️ 未识别到条码，请换图重试"
                        return@runOnUiThread
                    }
                    val source = result.barcodes.joinToString(",")
                    val sns = splitCodes(result.barcodes)
                    if (sns.isEmpty()) {
                        tvSplitStatus.text = "⚠️ 识别到条码但未拆分出有效 SN：\n$source"
                        return@runOnUiThread
                    }
                    applySplit(source, sns)
                }
            },
            onError = { msg ->
                runOnUiThread {
                    tvSplitStatus.text = "识别失败：$msg"
                    Toast.makeText(this, "识别失败：$msg", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    /** 手动粘贴拆分 */
    private fun splitManual() {
        val text = etManualCode.text.toString().trim()
        if (text.isEmpty()) {
            Toast.makeText(this, "请先粘贴或输入集成码", Toast.LENGTH_SHORT).show()
            return
        }
        val sns = splitText(text)
        if (sns.isEmpty()) {
            tvSplitStatus.text = "⚠️ 未拆分出有效 SN（请用逗号分隔）"
            return
        }
        applySplit(text, sns)
    }

    /**
     * 拆分：逗号/分号/空白 分隔 → 去重 → 丢弃过短 token。
     * 与 BoxParser 多值码规则一致；长度下限放宽到 4（SN 可能较短，
     * 保留给用户人工确认，可在列表里看到全部）。
     */
    private fun splitCodes(codes: List<String>): List<String> =
        splitText(codes.joinToString(","))

    private fun splitText(text: String): List<String> {
        val seen = mutableSetOf<String>()
        val out = mutableListOf<String>()
        text.split(Regex("[,;，；\\s]+")).forEach { token ->
            val t = token.trim()
            if (t.length >= 4 && seen.add(t)) out.add(t)
        }
        return out
    }

    /** 应用拆分结果：显示原文 + 重建二维码列表 */
    private fun applySplit(source: String, sns: List<String>) {
        snList.clear()
        snList.addAll(sns)
        tvSourceCode.text = "集成码原文：$source"
        tvSourceCode.visibility = TextView.VISIBLE
        rebuildResultList()
        tvSplitStatus.text = "🧩 拆出 ${snList.size} 个 SN，已生成独立条码（下方带内容）"
    }

    /** 重建结果列表（每行：SN + 条码图(下方带内容文本) + 保存按钮） */
    private fun rebuildResultList() {
        llSplitResult.removeAllViews()
        for ((index, sn) in snList.withIndex()) {
            val row = LayoutInflater.from(this).inflate(R.layout.item_split_row, llSplitResult, false)
            val bmp = BarcodeGenerator.generate(sn)
            if (bmp != null) {
                row.findViewById<ImageView>(R.id.ivBarcode).setImageBitmap(bmp)
            } else {
                // 二维码/条码图必须是浅色底才能被识别，深浅模式下都用浅底
                row.findViewById<ImageView>(R.id.ivBarcode).setBackgroundColor(android.graphics.Color.WHITE)
            }
            row.findViewById<TextView>(R.id.tvSplitSn).text = (index + 1).toString()
            row.findViewById<Button>(R.id.btnSaveOne).setOnClickListener {
                val ok = bmp?.let { saveBarcodeToGallery(it, index + 1, sn) } == true
                Toast.makeText(
                    this,
                    if (ok) "✅ 已保存: $sn" else "❌ 保存失败: $sn",
                    Toast.LENGTH_SHORT
                ).show()
            }
            llSplitResult.addView(row)
        }
    }

    /** 全部保存到相册 */
    private fun saveAll() {
        if (snList.isEmpty()) {
            Toast.makeText(this, "没有可保存的条码", Toast.LENGTH_SHORT).show()
            return
        }
        var okCount = 0
        var failCount = 0
        for ((index, sn) in snList.withIndex()) {
            val bmp = BarcodeGenerator.generate(sn)
            if (bmp != null && saveBarcodeToGallery(bmp, index + 1, sn)) okCount++ else failCount++
        }
        val msg = if (failCount == 0) {
            "✅ 已保存 $okCount 张条码到相册（Pictures/LabelScanner）"
        } else {
            "⚠️ 成功 $okCount 张，失败 $failCount 张"
        }
        tvSplitStatus.text = msg
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    /**
     * 条码 PNG 存入系统相册（Pictures/LabelScanner/）。
     * API 29+ 用 MediaStore（免权限，卸载不删）；26-28 fallback 到
     * App 专属外部目录（免权限）。
     */
    private fun saveBarcodeToGallery(bmp: Bitmap, index: Int, sn: String): Boolean {
        val safeName = sn.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "sn" }
        val fileName = "SN_${index}_$safeName.png"
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/LabelScanner")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
                contentResolver.openOutputStream(uri)?.use {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                } ?: return false
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
                true
            } else {
                val dir = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "LabelScanner").apply { mkdirs() }
                File(dir, fileName).outputStream().use {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun resetAll() {
        // 清空所有箱，恢复成"一箱空白"
        rows.clear()
        llBoxes.removeAllViews()
        addBoxRow()
        etManualCode.setText("")
        snList.clear()
        llSplitResult.removeAllViews()
        tvSourceCode.visibility = TextView.GONE
        tvSplitStatus.text = "每箱扫一个集成码；勾选要拆的箱，点「拆解选中的」一起拆"
    }
}
