package com.anglesgirl.labelscanner.export.v2

import com.anglesgirl.labelscanner.data.v2.TraySessionV2
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * WMS 库存导入模板的**纯逻辑**生成（无任何 Android 依赖）。
 *
 * 单独拆出来的原因有两个：
 *  1. **可离线回归测试** —— 列错位/行数不对这类问题必须在上机之前发现
 *     （历史上曾因列左移被用户当场纠正"填歪了"）。
 *  2. 职责清晰：这里只负责"数据 → xlsx 字节"，写文件/分享交给 ExporterV2。
 *
 * 列映射（公司模板固定，第 1/2 行不可改）：
 *   DATA01 库位=STAGE / DATA02 卡板=托盘号 / DATA03 物料 / DATA04 箱号 /
 *   DATA05 数量 / DATA08 生产日期 / DATA10 "|" / DATA14 SN码
 */
object WmsWorkbook {

    private val DATA_HEADERS = arrayOf(
        "DATA01", "DATA02", "DATA03", "DATA04", "DATA05", "DATA06", "DATA07",
        "DATA08", "DATA09", "DATA10", "DATA11", "DATA12", "DATA13", "DATA14"
    )

    private val LABEL_HEADERS = arrayOf(
        "库位", "卡板", "物料编码", "箱号", "数量", "工厂", "库存地",
        "生产日期", "销售公司", "销售订单|行号", "供应商", "特别加工指示书编号", "WCS库位", "SN码"
    )

    /**
     * 一箱 → 若干模板行。
     *  - 有 SN：每个 SN 一行，数量固定 1
     *  - 无 SN：只有箱号，1 行，数量 = qty
     */
    fun rowsForBox(
        trayCode: String,
        material: String,
        boxCode: String,
        productionDate: String,
        serialNumbers: List<String>,
        qty: Int,
    ): List<Array<String>> {
        val out = ArrayList<Array<String>>()
        if (serialNumbers.isEmpty()) {
            out.add(row(trayCode, material, boxCode.ifBlank { material }, qty, productionDate, boxCode))
        } else {
            for (sn in serialNumbers) {
                out.add(row(trayCode, material, boxCode.ifBlank { sn }, 1, productionDate, sn))
            }
        }
        return out
    }

    private fun row(
        trayCode: String,
        material: String,
        boxCode: String,
        qty: Int,
        date: String,
        sn: String,
    ): Array<String> = arrayOf(
        "STAGE",        // DATA01 库位
        trayCode,       // DATA02 卡板
        material,       // DATA03 物料编码
        boxCode,        // DATA04 箱号
        qty.toString(), // DATA05 数量
        "",             // DATA06 工厂
        "",             // DATA07 库存地
        date,           // DATA08 生产日期
        "",             // DATA09 销售公司
        "|",            // DATA10 销售订单|行号
        "",             // DATA11 供应商
        "",             // DATA12 特别加工指示书编号
        "",             // DATA13 WCS库位
        sn,             // DATA14 SN码
    )

    /** 一托盘全部箱 → 模板行。 */
    fun buildRows(tray: TraySessionV2): List<Array<String>> {
        val rows = ArrayList<Array<String>>()
        for (b in tray.allBoxes) {
            rows += rowsForBox(
                trayCode = tray.trayCode,
                material = b.materialCode,
                boxCode = b.boxCode,
                productionDate = b.productionDate,
                serialNumbers = b.serialNumbers,
                qty = b.declaredQty,
            )
        }
        return rows
    }

    /** 生成 .xlsx 字节。 */
    fun buildBytes(rows: List<Array<String>>): ByteArray {
        val bos = ByteArrayOutputStream()
        write(bos, rows)
        return bos.toByteArray()
    }

    private fun write(output: OutputStream, rows: List<Array<String>>) {
        ZipOutputStream(output).use { zip ->
            zip.entry(
                "[Content_Types].xml",
                """<?xml version="1.0" encoding="UTF-8"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                <Default Extension="xml" ContentType="application/xml"/>
                <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
                <Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>
                <Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>
                </Types>""".trimIndent()
            )
            zip.entry(
                "_rels/.rels",
                """<?xml version="1.0" encoding="UTF-8"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>
                <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/>
                </Relationships>""".trimIndent()
            )
            zip.entry(
                "xl/workbook.xml",
                """<?xml version="1.0" encoding="UTF-8"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                <sheets><sheet name="WMS导入" sheetId="1" r:id="rId1"/></sheets></workbook>""".trimIndent()
            )
            zip.entry(
                "xl/_rels/workbook.xml.rels",
                """<?xml version="1.0" encoding="UTF-8"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
                </Relationships>""".trimIndent()
            )
            zip.entry("xl/worksheets/sheet1.xml", sheetXml(rows))
            // 以下三部分 Excel 可以没有，但严格的解析器（如微信内置预览、
            // 部分 WPS 版本）会因为"包结构不完整"而拒绝显示，故一并补上。
            zip.entry("xl/styles.xml", STYLES_XML)
            zip.entry("docProps/core.xml", CORE_XML)
            zip.entry("docProps/app.xml", APP_XML)
        }
    }

    /** 最小可用样式表（一个字体/两个填充/一个边框/一个 xf）。 */
    private val STYLES_XML = """<?xml version="1.0" encoding="UTF-8"?>
        <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
        <fonts count="1"><font><sz val="11"/><name val="Calibri"/></font></fonts>
        <fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>
        <borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
        <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
        <cellXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/></cellXfs>
        <cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
        </styleSheet>""".trimIndent()

    private val CORE_XML = """<?xml version="1.0" encoding="UTF-8"?>
        <cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
        <dc:title>WMS导入</dc:title><dc:creator>LabelScanner</dc:creator>
        <cp:lastModifiedBy>LabelScanner</cp:lastModifiedBy>
        <dcterms:created xsi:type="dcterms:W3CDTF">2026-01-01T00:00:00Z</dcterms:created>
        <dcterms:modified xsi:type="dcterms:W3CDTF">2026-01-01T00:00:00Z</dcterms:modified>
        </cp:coreProperties>""".trimIndent()

    private val APP_XML = """<?xml version="1.0" encoding="UTF-8"?>
        <Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties" xmlns:vt="http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes">
        <Application>LabelScanner</Application>
        </Properties>""".trimIndent()

    private fun ZipOutputStream.entry(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(StandardCharsets.UTF_8))
        closeEntry()
    }

    private fun sheetXml(rows: List<Array<String>>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        append("<cols>")
        for (c in 1..15) append("<col min=\"$c\" max=\"$c\" width=\"18\" customWidth=\"1\"/>")
        append("</cols><sheetData>")
        appendRow(1, arrayOf("1") + DATA_HEADERS)   // 导入系统要求这一行
        appendRow(2, arrayOf("") + LABEL_HEADERS)
        rows.forEachIndexed { i, r -> appendRow(i + 3, arrayOf("") + r) }
        append("</sheetData></worksheet>")
    }

    private fun StringBuilder.appendRow(rowNumber: Int, values: Array<String>) {
        append("<row r=\"$rowNumber\">")
        values.forEachIndexed { column, value ->
            if (value.isNotEmpty()) {
                val ref = "${excelColumn(column + 1)}$rowNumber"
                append("<c r=\"$ref\" t=\"inlineStr\"><is><t>")
                append(xmlEscape(value))
                append("</t></is></c>")
            }
        }
        append("</row>")
    }

    private fun excelColumn(index: Int): String {
        var v = index
        val sb = StringBuilder()
        while (v > 0) {
            v--
            sb.append(('A'.code + v % 26).toChar())
            v /= 26
        }
        return sb.reverse().toString()
    }

    private fun xmlEscape(v: String): String = v
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
