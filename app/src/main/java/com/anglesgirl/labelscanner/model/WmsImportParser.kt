package com.anglesgirl.labelscanner.model

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.zip.ZipFile
import kotlin.math.abs
import kotlin.math.floor

/**
 * 导入他人 WMS 导入表（.xlsx）：读第一张表，按表头识别 DATA01~DATA14 列。
 *
 * 模板约定（与 Exporter.kt 的 WMS 导出一致）：
 *   第 1 行：DATA01~DATA14（第 1 列常为空或 "1"）；第 2 行：中文列名；
 *   数据从第 3 行起。第 1/2 行表头原样保留不改，导出时照原样写回。
 *
 * 列名兼容：DATAxx（大小写不限）或中文名（库位/卡板/托盘/物料编码/箱号/
 * 数量/工厂/库存地/生产日期/销售公司/销售订单/供应商/特别加工指示书/
 * WCS库位/SN码）。都认不出时按位置兜底：左起第一个非空列当 DATA01。
 *
 * 全程手写 OOXML 解析，不引 Apache POI（与 Exporter 同款做法）。
 */
object WmsImportParser {

    /** 一行导入数据：14 列 DATA01~DATA14（下标 0~13），原样字符串；可直接改下标改值。 */
    data class Row(val cells: Array<String>) {
        val location: String get() = cells.getOrElse(0) { "" }       // DATA01 库位
        val trayCode: String get() = cells.getOrElse(1) { "" }       // DATA02 托盘号
        val materialCode: String get() = cells.getOrElse(2) { "" }   // DATA03 物料编码
        val boxCode: String get() = cells.getOrElse(3) { "" }        // DATA04 箱号
        val quantity: String get() = cells.getOrElse(4) { "" }       // DATA05 数量
        val factory: String get() = cells.getOrElse(5) { "" }        // DATA06 工厂
        val stockLocation: String get() = cells.getOrElse(6) { "" }  // DATA07 库存地
        val productionDate: String get() = cells.getOrElse(7) { "" } // DATA08 生产日期
        val salesCompany: String get() = cells.getOrElse(8) { "" }   // DATA09 销售公司
        val salesOrder: String get() = cells.getOrElse(9) { "" }     // DATA10 销售订单|行号
        val supplier: String get() = cells.getOrElse(10) { "" }      // DATA11 供应商
        val specialNote: String get() = cells.getOrElse(11) { "" }   // DATA12 特别加工指示书
        val wcsLocation: String get() = cells.getOrElse(12) { "" }   // DATA13 WCS库位
        val serialNumber: String get() = cells.getOrElse(13) { "" }  // DATA14 SN码

        fun setTrayCode(v: String) { if (cells.size > 1) cells[1] = v }
        fun setStockLocation(v: String) { if (cells.size > 6) cells[6] = v }
        fun setProductionDate(v: String) { if (cells.size > 7) cells[7] = v }
    }

    /** 一张导入表：表头行（列号→原值，原样保留）+ DATA01 所在列 + 数据行。 */
    data class Table(
        val sheetName: String,
        val headerRow1: Map<Int, String>,
        val headerRow2: Map<Int, String>,
        val dataColBase: Int, // DATA01 所在 0 基列号（模板是 1，即 B 列）
        val rows: List<Row>,
    ) {
        /** 最常见的托盘号（导出文件名用），没有则为空。 */
        fun commonTrayCode(): String =
            rows.map { it.trayCode.trim() }.filter { it.isNotEmpty() }
                .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: ""
    }

    /** 解析 .xlsx 第一张表；读不到 / 没有可用数据行时返回 null。 */
    fun parse(file: File): Table? {
        if (!file.exists() || file.length() <= 0) return null
        return runCatching {
            val zip = ZipFile(file)
            try {
                val sharedStrings = readSharedStrings(zip)
                val (sheetName, sheetPath) = readFirstSheet(zip) ?: return null
                val grid = readSheetGrid(zip, sheetPath, sharedStrings)
                buildTable(sheetName, grid)
            } finally {
                zip.close()
            }
        }.getOrNull()
    }

    // ---------- 读共享字符串表（与 Exporter 同款） ----------

    private fun readSharedStrings(zip: ZipFile): List<String> {
        val entry = zip.getEntry("xl/sharedStrings.xml") ?: return emptyList()
        val list = mutableListOf<String>()
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(zip.getInputStream(entry).bufferedReader(Charsets.UTF_8))
        var inSi = false
        var current: StringBuilder? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "si" -> { inSi = true; current = StringBuilder() }
                        "t" -> if (inSi) current?.append(parser.nextText())
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "si") {
                        list.add(current?.toString() ?: "")
                        inSi = false
                        current = null
                    }
                }
            }
            event = parser.next()
        }
        return list
    }

    /** 读第一张表的（表名，部件路径）。 */
    private fun readFirstSheet(zip: ZipFile): Pair<String, String>? {
        val rels = mutableMapOf<String, String>()
        zip.getEntry("xl/_rels/workbook.xml.rels")?.let { entry ->
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setInput(zip.getInputStream(entry).bufferedReader(Charsets.UTF_8))
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "Relationship") {
                    val id = parser.getAttributeValue(null, "Id")
                    val target = parser.getAttributeValue(null, "Target")
                    if (id != null && target != null) rels[id] = target
                }
                event = parser.next()
            }
        }
        zip.getEntry("xl/workbook.xml")?.let { entry ->
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setInput(zip.getInputStream(entry).bufferedReader(Charsets.UTF_8))
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "sheet") {
                    val name = parser.getAttributeValue(null, "name") ?: ""
                    val rId = parser.getAttributeValue(
                        "http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id"
                    ) ?: parser.getAttributeValue(null, "id")
                    val target = rId?.let { rels[it] } ?: ""
                    if (name.isNotEmpty() && target.isNotEmpty()) {
                        val path = when {
                            target.startsWith("/") -> target.removePrefix("/")
                            target.startsWith("xl/") -> target
                            else -> "xl/$target"
                        }
                        return name to path
                    }
                }
                event = parser.next()
            }
        }
        return null
    }

    /** 把一张表 XML 读成（行号→列号→值）。行号 1 基，列号 0 基。 */
    private fun readSheetGrid(
        zip: ZipFile,
        sheetPath: String,
        sharedStrings: List<String>,
    ): Map<Int, Map<Int, String>> {
        val entry = zip.getEntry(sheetPath) ?: return emptyMap()
        val grid = mutableMapOf<Int, MutableMap<Int, String>>()
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(zip.getInputStream(entry).bufferedReader(Charsets.UTF_8))
        var rowNum = -1
        var colIdx = -1
        var cellType = ""
        var inValue = false
        var valueText: StringBuilder? = null
        var inInlineStr = false
        var inlineText: StringBuilder? = null
        var inT = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "row" -> rowNum = parser.getAttributeValue(null, "r")?.toIntOrNull() ?: -1
                    "c" -> {
                        colIdx = columnIndex(parser.getAttributeValue(null, "r") ?: "")
                        cellType = parser.getAttributeValue(null, "t") ?: ""
                        valueText = null
                        inlineText = null
                        inInlineStr = false
                    }
                    "v" -> { inValue = true; valueText = StringBuilder() }
                    "is" -> { inInlineStr = true; inlineText = StringBuilder() }
                    "t" -> { inT = true }
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text
                    if (inValue) valueText?.append(text)
                    if (inInlineStr && inT) inlineText?.append(text)
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "v" -> inValue = false
                    "t" -> inT = false
                    "is" -> inInlineStr = false
                    "c" -> {
                        if (rowNum > 0 && colIdx >= 0) {
                            val resolved = resolveCellValue(cellType, valueText?.toString(), inlineText?.toString(), sharedStrings)
                            if (resolved != null) {
                                grid.getOrPut(rowNum) { mutableMapOf() }[colIdx] = resolved
                            }
                        }
                        colIdx = -1
                        cellType = ""
                    }
                }
            }
            event = parser.next()
        }
        return grid
    }

    /** 单元格引用 "B3" → 列号 1（0 基）。 */
    private fun columnIndex(ref: String): Int {
        var col = 0
        for (ch in ref) {
            when {
                ch in 'A'..'Z' -> col = col * 26 + (ch - 'A' + 1)
                ch in 'a'..'z' -> col = col * 26 + (ch - 'a' + 1)
                else -> break
            }
        }
        return col - 1
    }

    /** 解一格的值：共享字符串 / 内联字符串 / 公式缓存值 / 数字。空值返回 null。 */
    private fun resolveCellValue(type: String, v: String?, inline: String?, sharedStrings: List<String>): String? {
        return when {
            inline != null -> inline
            type == "s" -> {
                val idx = v?.toIntOrNull() ?: return null
                if (idx in sharedStrings.indices) sharedStrings[idx] else null
            }
            type == "str" -> v
            v != null -> normalizeNumber(v)
            else -> null
        }
    }

    /** 数字格：整数去掉 .0；超大数原样返回（Double 会丢精度）。 */
    private fun normalizeNumber(v: String): String {
        val d = v.toDoubleOrNull() ?: return v
        return if (d == floor(d) && abs(d) < 1e15) d.toLong().toString() else v
    }

    // ---------- 表头识别 ----------

    private val DATA_NAME = Regex("^DATA\\s*0*(\\d{1,2})$", RegexOption.IGNORE_CASE)

    /** 中文列名 → DATA 序号（1~14）。 */
    private val LABEL_TO_DATA = mapOf(
        "库位" to 1, "卡板" to 2, "托盘" to 2, "卡板/托盘" to 2,
        "物料编码" to 3, "物料" to 3, "箱号" to 4, "数量" to 5, "工厂" to 6,
        "库存地" to 7, "生产日期" to 8, "销售公司" to 9,
        "销售订单" to 10, "销售订单|行号" to 10, "销售订单/行号" to 10,
        "供应商" to 11, "特别加工指示书编号" to 12, "特别加工指示书" to 12,
        "WCS库位" to 13, "WCS" to 13, "SN码" to 14, "SN" to 14,
    )

    /** 一格表头文字 → DATA 序号（1~14），认不出返回 null。 */
    private fun headerToDataIndex(raw: String): Int? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        DATA_NAME.find(text)?.let { m ->
            val n = m.groupValues[1].toIntOrNull()
            if (n != null && n in 1..14) return n
        }
        LABEL_TO_DATA[text]?.let { return it }
        // 含关键字的也认（如“托盘号”“库存地点”“生产日期(yyyymmdd)”）
        for ((label, n) in LABEL_TO_DATA) {
            if (label.length >= 2 && text.contains(label)) return n
        }
        return null
    }

    private fun buildTable(sheetName: String, grid: Map<Int, Map<Int, String>>): Table? {
        val row1 = grid[1] ?: emptyMap()
        val row2 = grid[2] ?: emptyMap()
        // 列号 → DATA 序号：第 1 行优先，第 2 行补缺。
        val colToData = mutableMapOf<Int, Int>()
        for ((col, value) in row1) {
            headerToDataIndex(value)?.let { colToData[col] = it }
        }
        for ((col, value) in row2) {
            if (col !in colToData) headerToDataIndex(value)?.let { colToData[col] = it }
        }
        var base = colToData.entries.firstOrNull { it.value == 1 }?.key
        if (base == null) {
            // 没认出 DATA01，用已认出的列反推（如 DATA02 所在列 -1）。
            val any = colToData.entries.minByOrNull { it.value }
            if (any != null) base = any.key - (any.value - 1)
        }
        if (base == null || base < 0) {
            // 兜底：左起第一个非空列当 DATA01。
            val firstCol = (row1.keys + row2.keys).minOrNull() ?: return null
            base = firstCol
        }
        val rows = mutableListOf<Row>()
        for ((rowNum, cells) in grid) {
            if (rowNum < 3) continue
            val values = Array(14) { i -> cells[base + i] ?: "" }
            if (values.all { it.isBlank() }) continue
            rows.add(Row(values))
        }
        if (rows.isEmpty()) return null
        return Table(sheetName, row1, row2, base, rows)
    }
}
