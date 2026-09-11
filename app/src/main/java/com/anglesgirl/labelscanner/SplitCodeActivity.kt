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
import android.view.View
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

    private lateinit var tvSourceCode: TextView
    private lateinit var llSplitResult: LinearLayout
    private lateinit var tvSplitStatus: TextView

    /** 已拆分的 SN 列表（有序去重） */
    private val snList = mutableListOf<String>()

    /**
     * 取图入口：统一走 camera.ImageIn。
     *
     * 三种取图方式（拍照 / 文档扫描 / 相册）共用同一份实现与参数。
     * 这里原来是"系统相机 ACTION_IMAGE_CAPTURE + FileProvider"自己的一套 ——
     * 同一个"拍照"在采集页和拆分页走的却是不同相机、不同参数。
     */
    // ⚠️ 必须在这里（Activity 构造阶段）就创建，**不能用 by lazy**：
    // registerForActivityResult 要求在当前状态仍为 CREATED 时注册，
    // 延迟到点击按钮时才初始化会抛异常，表现为"相机入口点不进去"。
    private val imageIn = com.anglesgirl.labelscanner.camera.ImageIn(this) { uri -> recognizeLabel(uri) }

    /** 多箱拆模式（每箱一行，全部完成后再出结果）。 */
    /** 多箱模式下每箱的集成码。 */
    private lateinit var llBoxes: LinearLayout
    /** 「待拆解的箱」标题：有箱时才显示，避免一屏空占位。 */
    private lateinit var tvBoxTitle: TextView
    /** 结果区与输入区之间的分隔线。 */
    private lateinit var dividerResult: android.view.View

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

        // 采集层已"全量收下"。这里的分工是用户明确要求的：
        //   **自动填是主力**（减轻劳动强度），**手选只是填错时的修补**。
        // 所以集成码照常自动进目标箱，不因为"可能填错"就改成让用户逐个手点。
        val idx = scanTargetRow
        val integrated = list.filter { it.contains(',') || it.contains('\uFF0C') }
        val others = list.filterNot { it.contains(',') || it.contains('\uFF0C') }

        var autoFilled = 0
        if (integrated.isNotEmpty()) {
            var first = true
            for (code in integrated) {
                if (first && idx >= 0 && idx < rows.size) {
                    rows[idx].input.setText(code)
                    rows[idx].check.isChecked = true
                    first = false
                } else {
                    val empty = rows.firstOrNull { it.input.text.isNullOrBlank() }
                    if (empty != null) {
                        empty.input.setText(code); empty.check.isChecked = true
                    } else {
                        addBoxRow().input.setText(code)
                        rows.last().check.isChecked = true
                    }
                }
                autoFilled++
            }
        } else if (list.size == 1 && idx >= 0 && idx < rows.size) {
            // 没集成码、只有一个码：仍然自动填，让用户看着改，而不是空手。
            rows[idx].input.setText(list[0])
            rows[idx].check.isChecked = true
            autoFilled = 1
        }

        updateBoxTitle()

        tvSplitStatus.text = when {
            autoFilled > 0 && others.isEmpty() -> "已自动填入 ${autoFilled} 箱"
            autoFilled > 0 -> "已自动填入 ${autoFilled} 箱（另有 ${others.size} 个非集成码未使用）"
            else -> "扫到 ${list.size} 个码，未识别到集成码，如不对请重扫"
        }
    }

    /** 长码只显示头尾，完整内容在箱里点一下可看（避免把界面撑开）。 */
    private fun summarize(code: String): String =
        if (code.length <= 26) code else code.take(14) + "…" + code.takeLast(8)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /**
     * 扫一箱：扫到的集成码**自动填进一箱**（自动填是主力，不增加操作）。
     */
    private fun scanNewBox() {
        scanTargetRow = -1
        scanInto.launch(
            Intent(this, LiveScanActivity::class.java)
                .putExtra(LiveScanActivity.EXTRA_TITLE, "扫一箱集成码")
                // 声明要集成码：扫描页因此只走 zxing-cpp 强通道，不让 ML Kit 参与。
                .putExtra(LiveScanActivity.EXTRA_WANT_INTEGRATED, true),
        )
    }

    /**
     * 粘贴一箱：整箱集成码常是从别处复制来的；支持一次粘贴多箱（一行一箱）。
     */
    private fun pasteNewBox() {
        val et = EditText(this).apply {
            hint = "SN1,SN2,SN3"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
        }
        AlertDialog.Builder(this)
            .setTitle("粘贴集成码")
            .setMessage("整箱的集成码，多个序列号用逗号分隔；多箱可一行一箱")
            .setView(et)
            .setPositiveButton("加入") { _, _ ->
                val boxes = et.text.toString()
                    .split('\n', '\r')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                if (boxes.isEmpty()) return@setPositiveButton
                var added = 0
                for (b in boxes) {
                    val empty = rows.firstOrNull { it.input.text.isNullOrBlank() }
                    if (empty != null) {
                        empty.input.setText(b); empty.check.isChecked = true
                    } else {
                        addBoxRow().also { it.input.setText(b); it.check.isChecked = true }
                    }
                    added++
                }
                updateBoxTitle()
                tvSplitStatus.text = "已加入 $added 箱，点「拆解选中的」开始"
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 箱标题与结果区可见性：有内容才显示，避免一屏空占位。 */
    private fun updateBoxTitle() {
        val filled = rows.count { !it.input.text.isNullOrBlank() }
        tvBoxTitle.visibility = View.VISIBLE
        tvBoxTitle.text = if (filled > 0) "待拆解的箱（$filled）" else "待拆解的箱"
        val hasResult = snList.isNotEmpty()
        dividerResult.visibility = if (hasResult) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.btnSaveAll).visibility = if (hasResult) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.btnResetSplit).visibility = if (hasResult) View.VISIBLE else View.GONE
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
        val btnDel = Button(this).apply { text = "✕"; textSize = 12f }

        val item = BoxRow(check, input)
        // 行内不再放「扫」—— 输入只在顶部一个地方（扫一箱 / 粘贴一箱）；
        // 想改某一箱，点它的内容即可（弹窗可看全、可改）。
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

        tvSourceCode = findViewById(R.id.tvSourceCode)
        llSplitResult = findViewById(R.id.llSplitResult)
        tvSplitStatus = findViewById(R.id.tvSplitStatus)
        llBoxes = findViewById(R.id.llBoxes)
        tvBoxTitle = findViewById(R.id.tvBoxTitle)
        dividerResult = findViewById(R.id.dividerResult)

        // 输入只有一个地方：顶部「扫一箱 / 粘贴一箱」。
        // 之前每行箱里还有「扫」、下面又有个独立粘贴框，同一件事三个入口 —— 已统一。
        findViewById<Button>(R.id.btnScanBox).setOnClickListener { scanNewBox() }
        findViewById<Button>(R.id.btnPhotoBox).setOnClickListener { imageIn.takePhoto() }
        findViewById<Button>(R.id.btnPasteBox).setOnClickListener { pasteNewBox() }
        findViewById<Button>(R.id.btnSplit).setOnClickListener { splitChecked() }
        findViewById<Button>(R.id.btnSaveAll).setOnClickListener { saveAll() }
        findViewById<Button>(R.id.btnResetSplit).setOnClickListener { resetAll() }

        addBoxRow()
        tvSplitStatus.text = "每箱扫一个集成码；勾选要拆的箱，点「拆解选中的」一起拆"
    }

    /** 识别图片里的集成码 → 拆成单个序列号 → 填进结果区 */
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
        snList.clear()
        llSplitResult.removeAllViews()
        tvSourceCode.visibility = TextView.GONE
        tvSplitStatus.text = "每箱扫一个集成码；勾选要拆的箱，点「拆解选中的」一起拆"
    }
}
