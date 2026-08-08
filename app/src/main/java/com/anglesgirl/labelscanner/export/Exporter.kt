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
 * 导出：保存的标签列表 → CSV。
 *
 * 双版本：
 *  - 系统版（标签数据_系统_<ts>.csv）：库存导入模板 DATA01~14（D/E/F/I/O），
 *    剔除 69 开头物料码（公司系统不认）
 *  - 自有版（标签数据_自有_<ts>.csv）：SAP 9 段码 + 附加字段全保留
 *    （型号/颜色/硒鼓/OCR原文），供迁移/对账/反查积累
 *
 * CSV 编码 UTF-8（Excel 乱码则改 GBK）。
 */
object Exporter {

    /**
     * 导出两份 CSV，返回自有版 Uri 供分享。
     */
    fun export(context: Context, results: List<LabelResult>): Uri? {
        if (results.isEmpty()) return null

        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

        // 系统版（库存导入模板，剔除 69 开头）
        val sysFile = File(dir, "标签数据_系统_$ts.csv")
        writeSystemCsv(sysFile, results)

        // 自有版（SAP 9 段码全保留）
        val ownFile = File(dir, "标签数据_自有_$ts.csv")
        writeOwnCsv(ownFile, results)

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            ownFile
        )
        return uri
    }

    /** 系统版：库存导入模板 DATA01~14 列 */
    private fun writeSystemCsv(file: File, results: List<LabelResult>) {
        val sb = StringBuilder()
        // 第 1 行：DATA01-14 列名（导入必须）
        sb.append("1,DATA01,DATA02,DATA03,DATA04,DATA05,DATA06,DATA07,DATA08,DATA09,DATA10,DATA11,DATA12,DATA13,DATA14\n")
        // 第 2 行：表头（业务说明）
        sb.append("Column Name,库位,卡板,物料编码,箱号,数量,工厂,库存地,生产日期,销售公司,销售订单,供应商,特别加工指示书编号,WCS,SN码\n")
        // 数据行
        for (r in results) {
            if (r.materialCode.startsWith("69")) continue
            val row = mutableListOf<String>()
            row.add(""); row.add(""); row.add("") // A B C
            row.add(r.materialCode)          // D
            row.add(r.serialNumber)          // E
            row.add(r.quantity.toString())   // F
            row.add(""); row.add("")          // G H
            row.add(r.productionDate)        // I
            row.add(""); row.add(""); row.add(""); row.add(""); row.add("") // J K L M N
            row.add(r.serialNumber)          // O
            sb.append(row.joinToString(",") { csvEscape(it) }).append("\n")
        }
        file.writeText(sb.toString(), Charsets.UTF_8)
    }

    /** 自有版：SAP 9 段码 + 附加字段全保留 */
    private fun writeOwnCsv(file: File, results: List<LabelResult>) {
        val sb = StringBuilder()
        sb.append("序号,SAP9段码,供应商,箱号,物料编码,数量,日期,69商品码,型号,颜色,硒鼓,OCR原文\n")
        for ((i, r) in results.withIndex()) {
            val row = listOf(
                (i + 1).toString(),
                r.toSapCode(),
                r.supplier,
                r.serialNumber,
                r.materialCode,
                r.quantity.toString(),
                r.productionDate,
                r.ean69,
                r.model,
                r.color,
                r.tonerModel,
                r.ocrText,
            )
            sb.append(row.joinToString(",") { csvEscape(it) }).append("\n")
        }
        file.writeText(sb.toString(), Charsets.UTF_8)
    }

    private fun csvEscape(s: String): String =
        if (s.contains(',') || s.contains('"') || s.contains('\n')) {
            "\"" + s.replace("\"", "\"\"") + "\""
        } else s
}
