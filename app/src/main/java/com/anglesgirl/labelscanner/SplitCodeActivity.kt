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
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
    private var multiMode = false
    /** 多箱模式下每箱的集成码。 */
    private val boxCodes = mutableListOf<String>()
    private var llBoxes: android.widget.LinearLayout? = null
    private var btnAddBox: android.view.View? = null

    /**
     * 挑码取码：调起 LiveScanActivity 的挑码模式 —— 相机实时扫到多个码后
     * **列出让用户点选**，而不是像 bulkMode 那样扫到就自动返回。
     * 集成码常与其它码同框出现，必须让用户点，否则极易取错码。
     */
    private val pickCode = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        // 挑码页按扫描顺序返回（序号 = 第几箱）；单箱模式下只会有一个
        val list = data.getStringArrayListExtra(LiveScanActivity.EXTRA_RESULT_CODES)
            ?: data.getStringExtra(LiveScanActivity.EXTRA_RESULT_CODE)?.let { arrayListOf(it) }
            ?: return@registerForActivityResult
        if (list.isEmpty()) return@registerForActivityResult

        if (multiMode || list.size > 1) {
            boxCodes.clear()
            boxCodes.addAll(list)
            renderBoxes()
            applyAllBoxes()
        } else {
            onPickedCode(list[0])
        }
    }

    /**
     * 一起拆：把每一箱的集成码拆成 SN，按箱顺序汇总，逐个生成独立条码。
     * 这是多箱模式的收尾动作（用户：全部扫完之后按拆解）。
     */
    private fun applyAllBoxes() {
        snList.clear()
        for (c in boxCodes) snList.addAll(splitCodes(listOf(c)))
        tvSourceCode.text = "共 ${boxCodes.size} 箱，拆出 ${snList.size} 个 SN"
        tvSourceCode.visibility = TextView.VISIBLE
        rebuildResultList()
        tvSplitStatus.text = "🧩 ${boxCodes.size} 箱 → ${snList.size} 个 SN（已逐个生成独立条码）"
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

        findViewById<Button>(R.id.btnTakePhoto).setOnClickListener { launchCamera() }
        // 挑码取码：集成码不适合拍照识别，改为"扫到后点选"
        findViewById<Button>(R.id.btnScanPick).setOnClickListener { startPick() }
        llBoxes = findViewById(R.id.llBoxes)
        btnAddBox = findViewById(R.id.btnAddBox)
        // 单箱拆 / 多箱拆
        findViewById<Button>(R.id.btnModeSingle).setOnClickListener { setMultiMode(false) }
        findViewById<Button>(R.id.btnModeMulti).setOnClickListener { setMultiMode(true) }
        // 多箱模式：进相机后连续扫，每点一个码就记一箱，最后点"一起拆"
        findViewById<Button>(R.id.btnAddBox).setOnClickListener { startPick() }
        findViewById<Button>(R.id.btnScanDoc).setOnClickListener { launchDocScan() }
        findViewById<Button>(R.id.btnPickGallery).setOnClickListener { pickGallery.launch("image/*") }
        findViewById<Button>(R.id.btnSplit).setOnClickListener { splitManual() }
        findViewById<Button>(R.id.btnSaveAll).setOnClickListener { saveAll() }
        findViewById<Button>(R.id.btnResetSplit).setOnClickListener { resetAll() }
    }

    /** 系统相机拍照 → captures/ → 识别 */

    /** 调起挑码相机：扫到多个码后由用户点选。 */
    private fun startPick() {
        try {
            pickCode.launch(Intent(this, LiveScanActivity::class.java)
                .putExtra(LiveScanActivity.EXTRA_TITLE, "扫集成码（扫到后点选）")
                .putExtra(LiveScanActivity.EXTRA_PICK_MODE, true))
        } catch (t: Throwable) {
            Toast.makeText(this, "相机启动失败：${t.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** 切换单箱/多箱：多箱显示每箱一行与"添加一箱"，单箱隐藏这些。 */
    private fun setMultiMode(multi: Boolean) {
        multiMode = multi
        val vis = if (multi) android.view.View.VISIBLE else android.view.View.GONE
        llBoxes?.visibility = vis
        btnAddBox?.visibility = vis
        findViewById<Button>(R.id.btnModeMulti).alpha = if (multi) 1f else 0.6f
        findViewById<Button>(R.id.btnModeSingle).alpha = if (multi) 0.6f else 1f
        snList.clear()
        rebuildResultList()
        if (multi) {
            renderBoxes()
            tvSplitStatus.text = "多箱拆：每箱扫一个集成码，扫完全部后自动汇总"
        } else {
            tvSplitStatus.text = "单箱拆：扫到集成码立即拆分出序列号"
        }
    }

    /** 挑到的码：单箱模式直接拆；多箱模式作为新的一箱加入列表。 */
    private fun onPickedCode(code: String) {
        if (multiMode) {
            boxCodes.add(code)
            renderBoxes()
        } else {
            etManualCode.setText(code)
            splitManual()   // 单箱：直接出拆解结果
        }
    }

    /**
     * 多箱列表：每箱一行（第几箱 / 拆出的 SN 个数 / 删除）。
     * 所有箱都能拆出 SN 后，一次性汇总所有箱的单箱条码。
     */
    private fun renderBoxes() {
        val box = llBoxes ?: return
        box.removeAllViews()
        val dp = resources.displayMetrics.density
        var totalSns = 0

        boxCodes.forEachIndexed { index, code ->
            val sns = splitCodes(listOf(code))
            totalSns += sns.size

            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                setBackgroundColor(0xFFFFFFFF.toInt())
                val p = (10 * dp).toInt()
                setPadding(p, p, p, p)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = (6 * dp).toInt() }
            }
            val label = android.widget.TextView(this).apply {
                text = "箱${index + 1}  拆出 ${sns.size} 个 SN"
                textSize = 14f
                setTextColor(if (sns.isEmpty()) 0xFFD32F2F.toInt() else 0xFF1B5E20.toInt())
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val del = android.widget.Button(this).apply {
                text = "删"
                textSize = 12f
                setOnClickListener {
                    boxCodes.removeAt(index)
                    renderBoxes()
                }
            }
            row.addView(label)
            row.addView(del)
            box.addView(row)
        }

        // 全部完成 → 汇总列出所有箱的单箱条码
        snList.clear()
        for (c in boxCodes) snList.addAll(splitCodes(listOf(c)))
        rebuildResultList()
        tvSplitStatus.text = if (boxCodes.isEmpty()) {
            "多箱拆：每箱扫一个集成码，扫完全部后自动汇总"
        } else {
            "${boxCodes.size} 箱，共拆出 ${totalSns} 个 SN" +
                if (totalSns == 0) "（有箱没拆出内容，请检查）" else ""
        }
    }

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
                row.findViewById<ImageView>(R.id.ivBarcode).setBackgroundColor(0xFFEEEEEE.toInt())
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
        // 多箱状态一并清空，避免残留上一次的箱
        boxCodes.clear()
        renderBoxes()
        etManualCode.setText("")
        snList.clear()
        llSplitResult.removeAllViews()
        tvSourceCode.visibility = TextView.GONE
        tvSplitStatus.text = ""
    }
}
