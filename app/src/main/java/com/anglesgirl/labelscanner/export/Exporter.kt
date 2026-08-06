package com.anglesgirl.labelscanner.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.anglesgirl.labelscanner.model.LabelResult
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 导出：保存的标签列表 → CSV（系统版剔除 69 开头 / 自有版全保留）。
 *
 * 后续可升级为 xlsx（Apache POI 或手写 XML）。
 * CSV 编码 GBK（Windows Excel 兼容）或 UTF-8 BOM。
 */
object Exporter {

    /** 库存导入模板列（第 1 行 DATA01-14 对应） */
    private val columns = listOf(
        "A" to "1", "B" to "2", "C" to "3", "D" to "4", "E" to "5",
        "F" to "6", "G" to "7", "H" to "8", "I" to "9", "J" to "10",
        "K" to "11", "L" to "12", "M" to "13", "N" to "14", "O" to "15",
    )

    /**
     * 导出两份：
     *  - system: 剔除 69 开头物料码（公司系统不认）
     *  - own: 全保留（App 自有数据）
     */
    fun export(context: Context, results: List<LabelResult>): Uri? {
        if (results.isEmpty()) return null

        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

        // 自有版（全保留）
        val ownFile = File(dir, "标签数据_自有_$ts.csv")
        writeCsv(ownFile, results, filter69 = false)

        // 系统版（剔除 69 开头）
        val sysFile = File(dir, "标签数据_系统_$ts.csv")
        writeCsv(sysFile, results, filter69 = true)

        // 分享自有版（或系统版）
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            ownFile
        )
        return uri
    }

    private fun writeCsv(file: File, results: List<LabelResult>, filter69: Boolean) {
        val sb = StringBuilder()
        // 第 1 行：DATA01-14 列名（导入必须）
        sb.append("1,DATA01,DATA02,DATA03,DATA04,DATA05,DATA06,DATA07,DATA08,DATA09,DATA10,DATA11,DATA12,DATA13,DATA14\n")
        // 第 2 行：表头（业务说明，占位）
        sb.append("Column Name,库位,卡板,物料编码,箱号,数量,工厂,库存地,生产日期,销售公司,销售订单,供应商,特别加工指示书编号,WCS,SN码\n")
        // 数据行
        for (r in results) {
            if (filter69 && r.materialCode.startsWith("69")) continue
            val row = mutableListOf<String>()
            row.add(""); row.add(""); row.add("") // A B C
            row.add(r.materialCode)          // D
            row.add(r.serialNumber)          // E
            row.add(r.quantity.toString())   // F
            row.add(""); row.add("")         // G H
            row.add(r.productionDate)        // I
            row.add(""); row.add(""); row.add(""); row.add(""); row.add("") // J K L M N
            row.add(r.serialNumber)          // O
            sb.append(row.joinToString(",") { csvEscape(it) }).append("\n")
        }
        file.writeText(sb.toString(), Charsets.UTF_8)
    }

    private fun csvEscape(s: String): String =
        if (s.contains(',') || s.contains('"') || s.contains('\n')) {
            "\"" + s.replace("\"", "\"\"") + "\""
        } else s
}
