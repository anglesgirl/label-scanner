package com.anglesgirl.labelscanner.v2

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.anglesgirl.labelscanner.data.v2.TraySessionV2
import com.anglesgirl.labelscanner.databinding.ActivityTraySummaryV2Binding
import com.anglesgirl.labelscanner.export.v2.ExporterV2
import com.anglesgirl.labelscanner.util.Diag

/**
 * 托盘会话的内存登记处。
 *
 * 托盘对象仅在内存中活一次使用周期（采完即导出），用 Parcelable 传递
 * 反而要写一堆样板且容易漏字段；用一个按托盘号索引的单例最直接。
 */
object TrayRegistry {
    private val trays = LinkedHashMap<String, TraySessionV2>()

    fun put(tray: TraySessionV2) {
        trays[tray.trayCode.ifBlank { "_default" }] = tray
    }

    fun get(code: String): TraySessionV2? = trays[code.ifBlank { "_default" }]

    fun remove(code: String) {
        trays.remove(code.ifBlank { "_default" })
    }
}

/**
 * 托盘汇总页：**按物料统计 + 每箱明细 + 导出**。
 *
 * 这里只"展示采集结果"，不设预期清单、不做差值告警 —— 用户要求的是
 * "采到什么就写什么，展示出来检查"。唯一的校验发生在箱内（数量 vs SN 数）。
 */
class TraySummaryActivityV2 : AppCompatActivity() {

    companion object {
        private const val EXTRA_TRAY_CODE = "tray_code"

        fun intent(context: Context, tray: TraySessionV2): Intent {
            TrayRegistry.put(tray)
            return Intent(context, TraySummaryActivityV2::class.java)
                .putExtra(EXTRA_TRAY_CODE, tray.trayCode)
        }
    }

    private lateinit var binding: ActivityTraySummaryV2Binding
    private var tray: TraySessionV2? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTraySummaryV2Binding.inflate(layoutInflater)
        setContentView(binding.root)

        val code = intent.getStringExtra(EXTRA_TRAY_CODE).orEmpty()
        tray = TrayRegistry.get(code)

        val t = tray
        if (t == null) {
            Toast.makeText(this, "托盘数据丢失，请重新采集", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        binding.title.text = "托盘 ${t.trayCode.ifBlank { "(未命名)" }}"
        binding.total.text = "合计：${t.totalBoxes} 箱 / ${t.totalUnits} 件 / 导出 ${t.totalRows} 行"

        // 按物料汇总
        val sb = StringBuilder()
        for (s in t.summary()) {
            sb.appendLine("${s.materialCode}    ${s.boxCount} 箱 / ${s.unitCount} 件")
        }
        binding.summary.text = sb.toString().trim()

        // 每箱明细（有问题的箱明确标出）
        val db = StringBuilder()
        t.allBoxes.forEachIndexed { i, b ->
            val flag = if (b.hasIssue) " ⚠️" else " ✓"
            db.appendLine("箱${i + 1}  ${b.boxCode.ifBlank { "(无箱号)" }}$flag")
            db.appendLine("      ${b.materialCode}  ${b.serialNumbers.size} SN / 数量 ${b.effectiveQty}")
            if (b.hasIssue) b.warnings.forEach { db.appendLine("      ⚠️ $it") }
        }
        binding.detail.text = db.toString().trim()

        binding.btnExport.setOnClickListener { export() }
        binding.btnBack.setOnClickListener { finish() }

        Diag.event(
            "summary_viewed",
            mapOf(
                "tray" to t.trayCode,
                "boxes" to t.totalBoxes,
                "units" to t.totalUnits,
                "rows" to t.totalRows,
                "issues" to t.boxesWithIssues.size,
            ),
        )
    }

    private fun export() {
        val t = tray ?: return
        val uri = ExporterV2.exportTray(this, t)
        if (uri == null) {
            Diag.event("export_failed", mapOf("tray" to t.trayCode))
            Toast.makeText(this, "导出失败", Toast.LENGTH_LONG).show()
            return
        }
        Diag.event(
            "export_done",
            mapOf("tray" to t.trayCode, "rows" to t.totalRows, "boxes" to t.totalBoxes),
        )
        Toast.makeText(this, "已导出：下载/LabelScanner/${t.trayCode}.xlsx", Toast.LENGTH_LONG).show()

        val share = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // 常用场景是把表发给同事/自己，直接给出分享面板
            setPackage(null)
        }
        try {
            startActivity(Intent.createChooser(share, "发送表格"))
        } catch (_: Throwable) {
            // 分享失败不影响已导出的文件
        }
    }

    override fun onDestroy() {
        // 导出后不再需要这份内存数据（用户若返回重采会新建托盘）
        tray?.let { TrayRegistry.remove(it.trayCode) }
        super.onDestroy()
    }
}
