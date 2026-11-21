package com.anglesgirl.labelscanner

import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.anglesgirl.labelscanner.camera.ImageIn
import com.anglesgirl.labelscanner.camera.PdfSlipRunner
import com.anglesgirl.labelscanner.camera.SlipOcrRunner
import com.anglesgirl.labelscanner.data.DbExecutor
import com.anglesgirl.labelscanner.data.LedgerStore
import com.anglesgirl.labelscanner.data.SlipStore
import com.anglesgirl.labelscanner.model.SlipOcrParser
import com.anglesgirl.labelscanner.util.LocationPrefs

/**
 * 单据批量入库：拍一张免费入库申请单（多行表），OCR 读出每行物料 + 数量，
 * 共用库位 / 工厂 / 日期，核对后一次全部入库（每行一笔流水，无记录自动建账）。
 * 读不到的格子留空手填，不猜；入库不弹库存选择（库位是新填的）。
 * 全离线，中文界面。
 */
class SlipBatchInboundActivity : AppCompatActivity() {

    private data class RowViews(val etMaterial: EditText, val etQty: EditText, val row: LinearLayout)

    private lateinit var imageIn: ImageIn
    private lateinit var tvStatus: TextView
    private lateinit var tvSlipInfo: TextView
    private lateinit var etLocation: EditText
    private lateinit var rowsContainer: LinearLayout
    private lateinit var tvResult: TextView
    private lateinit var btnConfirm: Button

    private val rowViews = mutableListOf<RowViews>()
    private var recognizing = false

    /** PDF 单据：选 PDF → 逐页 OCR 原文 → 多行表解析。 */
    private val pickPdf =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            val tmp = SlipStore.newTempFile(this, "slip.pdf")
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (_: Exception) {
                Toast.makeText(this, "读取 PDF 失败", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
            if (!tmp.exists() || tmp.length() <= 0) {
                Toast.makeText(this, "读取 PDF 失败", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
            setRecognizing(true)
            PdfSlipRunner.runText(tmp) { text, opened ->
                setRecognizing(false)
                if (isFinishing || isDestroyed) return@runText
                if (!opened) {
                    Toast.makeText(this, "PDF 打不开（可能已加密或损坏）", Toast.LENGTH_LONG).show()
                    return@runText
                }
                handleOcrText(text)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        imageIn = ImageIn(this) { uri -> onImage(uri) }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        root.addView(TextView(this).apply {
            text = "单据批量入库"
            textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "拍一张免费入库申请单，自动读出每行物料和数量，核对后一次全部入库"
            textSize = 13f
            setPadding(0, dp(4), 0, dp(12))
        })

        // 取图按钮：拍照 / 文档扫描 / 相册 / PDF。
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        fun imgBtn(label: String, onClick: () -> Unit): Button =
            Button(this).apply {
                text = label
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { onClick() }
            }
        btnRow.addView(imgBtn("拍照") { imageIn.takePhoto() })
        btnRow.addView(imgBtn("文档扫描") { imageIn.scanDocument() })
        btnRow.addView(imgBtn("相册") { imageIn.pickGallery() })
        btnRow.addView(imgBtn("选 PDF") { pickPdf.launch(arrayOf("application/pdf")) })
        root.addView(btnRow)

        tvStatus = TextView(this).apply {
            text = "先拍单据，或手动加行"
            textSize = 14f
            setPadding(0, dp(12), 0, 0)
        }
        root.addView(tvStatus)

        tvSlipInfo = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(4), 0, 0)
        }
        root.addView(tvSlipInfo)

        // 共用库位（可改，入库后沿用给下次）。
        val locRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        locRow.addView(TextView(this).apply {
            text = "库位"
            textSize = 15f
            setPadding(0, dp(12), dp(8), 0)
        })
        etLocation = EditText(this).apply {
            hint = "如 3511"
            setText(LocationPrefs.get(this@SlipBatchInboundActivity))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        locRow.addView(etLocation)
        root.addView(locRow)

        // 明细行容器。
        rowsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        val scroll = ScrollView(this).apply {
            addView(rowsContainer)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        root.addView(scroll)

        val addRow = Button(this).apply {
            text = "手动加一行"
            setOnClickListener { addRow("", null) }
        }
        root.addView(addRow)

        tvResult = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(8), 0, 0)
        }
        root.addView(tvResult)

        btnConfirm = Button(this).apply {
            text = "确认入库"
            setOnClickListener { confirmInbound() }
        }
        root.addView(btnConfirm)

        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        // 先给一行空行，方便直接手填。
        addRow("", null)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun setRecognizing(v: Boolean) {
        recognizing = v
        tvStatus.text = if (v) "正在识别单据…" else tvStatus.text
        btnConfirm.isEnabled = !v
    }

    /** 取图回调：Uri → 临时文件 → OCR 原文 → 多行表解析。 */
    private fun onImage(uri: Uri) {
        val tmp = SlipStore.newTempFile(this, "slip.jpg")
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (_: Exception) {
            Toast.makeText(this, "读取图片失败", Toast.LENGTH_SHORT).show()
            return
        }
        if (!tmp.exists() || tmp.length() <= 0) {
            Toast.makeText(this, "读取图片失败", Toast.LENGTH_SHORT).show()
            return
        }
        setRecognizing(true)
        SlipOcrRunner.runText(tmp) { text ->
            setRecognizing(false)
            if (isFinishing || isDestroyed) return@runText
            handleOcrText(text)
        }
    }

    /** OCR 原文 → 多行表解析 → 填行 + 共用字段；读不到就留空手填。 */
    private fun handleOcrText(text: String) {
        if (text.isBlank()) {
            tvStatus.text = "单据上没有读到文字，重拍或手动加行"
            return
        }
        val table = runCatching { SlipOcrParser.parseInboundTable(text) }
            .getOrDefault(SlipOcrParser.TableResult(emptyList(), "", "", "", ""))
        if (!table.hasAny) {
            tvStatus.text = "没读到物料行，请重拍清楚一点，或手动加行"
            return
        }
        // 识别到的行替换现有行（手填的空行丢掉，避免混在一起）。
        clearRows()
        for (row in table.rows) addRow(row.materialCode, row.quantity)
        if (table.location.isNotBlank()) etLocation.setText(table.location)
        val info = buildList {
            if (table.department.isNotBlank()) add("退仓部门 ${table.department}")
            if (table.factory.isNotBlank()) add("工厂 ${table.factory}")
            if (table.date.isNotBlank()) add("交货日期 ${table.date}")
        }.joinToString(" · ")
        tvSlipInfo.text = info
        val missingQty = table.rows.count { it.quantity == null }
        tvStatus.text = "读到 ${table.rows.size} 行" +
            (if (missingQty > 0) "，其中 $missingQty 行数量没读到，请手填" else "") +
            "，核对后点确认入库"
        tvResult.text = ""
    }

    private fun clearRows() {
        rowViews.clear()
        rowsContainer.removeAllViews()
    }

    private fun addRow(material: String, qty: Int?) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val etMaterial = EditText(this).apply {
            hint = "物料编码"
            setText(material)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val etQty = EditText(this).apply {
            hint = "数量"
            inputType = InputType.TYPE_CLASS_NUMBER
            qty?.let { setText(it.toString()) }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.6f)
        }
        val btnDel = Button(this).apply {
            text = "删"
            setOnClickListener {
                rowViews.removeIf { it.row == row }
                rowsContainer.removeView(row)
            }
        }
        row.addView(etMaterial)
        row.addView(etQty)
        row.addView(btnDel)
        rowsContainer.addView(row)
        rowViews.add(RowViews(etMaterial, etQty, row))
    }

    /** 逐行校验 → 后台逐行入库（每行一笔流水，无记录自动建账）→ 汇总结果。 */
    private fun confirmInbound() {
        if (recognizing) return
        val location = etLocation.text.toString().trim()
        if (location.isEmpty()) {
            Toast.makeText(this, "请填写库位", Toast.LENGTH_SHORT).show()
            return
        }
        val items = rowViews.mapNotNull { rv ->
            val mat = rv.etMaterial.text.toString().trim()
            val qty = rv.etQty.text.toString().trim().toIntOrNull()
            if (mat.isEmpty()) null else mat to qty
        }
        if (items.isEmpty()) {
            Toast.makeText(this, "没有可入库的行", Toast.LENGTH_SHORT).show()
            return
        }
        val badQty = items.firstOrNull { it.second == null || it.second!! <= 0 }
        if (badQty != null) {
            Toast.makeText(this, "物料 ${badQty.first} 的数量不对，请手填", Toast.LENGTH_LONG).show()
            return
        }
        btnConfirm.isEnabled = false
        tvResult.text = "正在入库…"
        DbExecutor.run({
            val ok = mutableListOf<Triple<String, Int, Int>>()
            val failed = mutableListOf<Pair<String, String>>()
            for ((mat, qty) in items) {
                try {
                    val mv = LedgerStore.applyMovement(
                        this@SlipBatchInboundActivity,
                        LedgerStore.TYPE_IN, mat, qty!!, location, "",
                    )
                    ok.add(Triple(mat, qty, mv.balanceAfter))
                } catch (e: Exception) {
                    failed.add(mat to (e.message ?: "失败"))
                }
            }
            ok to failed
        }) { result ->
            btnConfirm.isEnabled = true
            result.onSuccess { (ok, failed) ->
                LocationPrefs.set(this@SlipBatchInboundActivity, location)
                val lines = mutableListOf<String>()
                for ((mat, qty, bal) in ok) lines.add("✓ $mat +$qty，结存 $bal")
                for ((mat, err) in failed) lines.add("✗ $mat：$err")
                tvResult.text = lines.joinToString("\n")
                tvStatus.text = "入库完成：成功 ${ok.size} 行" +
                    (if (failed.isNotEmpty()) "，失败 ${failed.size} 行" else "")
                if (failed.isEmpty()) {
                    Toast.makeText(this, "已入库 ${ok.size} 行，库位 $location", Toast.LENGTH_SHORT).show()
                    clearRows()
                    addRow("", null)
                } else {
                    Toast.makeText(this, "有 ${failed.size} 行失败，改好再确认", Toast.LENGTH_LONG).show()
                }
            }.onFailure {
                tvResult.text = "入库失败：${it.message}"
            }
        }
    }
}
