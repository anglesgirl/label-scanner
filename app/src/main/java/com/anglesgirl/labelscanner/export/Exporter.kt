package com.anglesgirl.labelscanner.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.anglesgirl.labelscanner.model.LabelResult
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 导出工具：CSV / WMS 格式
 *
 * CSV（原有兼容）：标签字段 + 原始条码/OCR
 * WMS：库存导入模板 DATA01~DATA14（.xlsx）
 *   DATA01 库位           -> 固定 "STAGE"
 *   DATA02 卡板/托盘      -> trayCode
 *   DATA03 物料编码       -> materialCode
 *   DATA04 箱号           -> boxCode（旧数据/单条模式 boxCode 空 → 回退 serialNumber，
 *                            因为以前一箱一个 SN，箱号=SN；一箱多 SN 后真正分开）
 *   DATA05 数量           -> 1 (固定)
 *   DATA06 工厂           -> 留空
 *   DATA07 库存地         -> 留空
 *   DATA08 生产日期-yyyymmdd -> productionDate
 *   DATA09 销售公司       -> 留空
 *   DATA10 销售订单|行号   -> 固定 "|"
 *   DATA11 供应商         -> 留空
 *   DATA12 特别加工指示书编号 -> 留空
 *   DATA13 WCS库位        -> 留空
 *   DATA14 SN码           -> serialNumber
 */
object Exporter {

    private const val EXT_DIR = "Download/LabelScanner"
    private const val XLSX_MIME_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

    /** 原有 CSV 导出（保留兼容） */
    fun export(context: Context, records: List<LabelResult>): Uri? {
        val fileName = "labels_${System.currentTimeMillis()}.csv"
        return if (Build.VERSION.SDK_INT >= 29) {
            exportViaMediaStore(context, records, fileName, ::buildCsvRow)
        } else {
            exportViaLegacyDir(context, records, fileName, ::buildCsvRow)
        }
    }

    /** WMS 导出：库存导入模板 DATA01~DATA14，始终生成真实 .xlsx。 */
    fun exportWms(context: Context, records: List<LabelResult>): Uri? {
        val trayCode = records.firstOrNull()?.trayCode?.takeIf { it.isNotBlank() }
        val fileName = "${trayCode ?: "wms_import_${System.currentTimeMillis()}"}.xlsx"
        return if (Build.VERSION.SDK_INT >= 29) {
            exportXlsxViaMediaStore(context, records, fileName)
        } else {
            exportXlsxViaLegacyDir(context, records, fileName)
        }
    }

    /** WMS 模板：第 1 行 DATA 编码行（1, DATA01..DATA14），第 2 行中文说明行 */
    private val WMS_DATA_HEADERS = arrayOf(
        "DATA01", "DATA02", "DATA03", "DATA04", "DATA05",
        "DATA06", "DATA07", "DATA08", "DATA09", "DATA10",
        "DATA11", "DATA12", "DATA13", "DATA14"
    )

    private val WMS_LABEL_HEADERS = arrayOf(
        "库位", "卡板/托盘", "物料编码", "箱号", "数量", "工厂", "库存地",
        "生产日期", "销售公司", "销售订单|行号", "供应商", "特别加工指示书编号",
        "WCS库位", "SN码"
    )

    private fun buildCsvRow(r: LabelResult): String {
        return "\"${r.barcodes.joinToString("|")}\",\"${r.ocrText}\",\"${r.supplier}\",\"${r.serialNumber}\",\"${r.materialCode}\",${r.quantity},\"${r.productionDate}\",\"${r.ean69}\",\"${r.model}\",\"${r.color}\",\"${r.tonerModel}\""
    }

    private fun buildWmsRow(r: LabelResult): Array<String> {
        val trayCode = r.trayCode.ifBlank { "" }
        // 箱号：一箱多 SN 后 boxCode 独立；旧数据 boxCode 空 → 回退 SN（一箱一 SN 时箱号=SN）
        val boxCode = r.boxCode.ifBlank { r.serialNumber }
        return arrayOf(
            "STAGE",              // DATA01 库位
            trayCode,             // DATA02 卡板/托盘
            r.materialCode,       // DATA03 物料编码
            boxCode,              // DATA04 箱号
            "1",                  // DATA05 数量
            "",                   // DATA06 工厂
            "",                   // DATA07 库存地
            r.productionDate,     // DATA08 生产日期-yyyymmdd
            "",                   // DATA09 销售公司
            "|",                  // DATA10 销售订单|行号
            "",                   // DATA11 供应商
            "",                   // DATA12 特别加工指示书编号
            "",                   // DATA13 WCS库位
            r.serialNumber        // DATA14 SN码
        )
    }

    // ===== Excel (.xlsx) 导出：使用 Apache POI 直接生成二进制 .xlsx =====

    private fun exportXlsxViaMediaStore(
        context: Context,
        records: List<LabelResult>,
        fileName: String,
    ): Uri? = runCatching {
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        context.contentResolver.delete(
            collection,
            "${MediaStore.Downloads.DISPLAY_NAME} = ?",
            arrayOf(fileName)
        )
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, XLSX_MIME_TYPE)
            put(MediaStore.Downloads.RELATIVE_PATH, EXT_DIR)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(collection, values)
            ?: error("无法创建导出文件")
        context.contentResolver.openOutputStream(uri)?.use { writeWmsWorkbook(it, records) }
            ?: error("无法打开导出文件")
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        context.contentResolver.update(uri, values, null, null)
        uri
    }.getOrNull()

    private fun exportXlsxViaLegacyDir(
        context: Context,
        records: List<LabelResult>,
        fileName: String,
    ): Uri? = runCatching {
        val dir = File(context.getExternalFilesDir(null), "LabelScanner").apply { mkdirs() }
        val file = File(dir, fileName)
        FileOutputStream(file).use { writeWmsWorkbook(it, records) }
        androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
    }.getOrNull()

    /** 只写本应用需要的 OOXML 工作簿，避免 Apache POI 在 Android 上的运行时依赖。 */
    private fun writeWmsWorkbook(output: java.io.OutputStream, records: List<LabelResult>) {
        ZipOutputStream(output).use { zip ->
            zip.writeEntry("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                <Default Extension="xml" ContentType="application/xml"/>
                <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
                <Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>
                <Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>
                </Types>""".trimIndent())
            zip.writeEntry("_rels/.rels", """<?xml version="1.0" encoding="UTF-8"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>
                <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/>
                </Relationships>""".trimIndent())
            zip.writeEntry("xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                <sheets><sheet name="WMS导入" sheetId="1" r:id="rId1"/></sheets></workbook>""".trimIndent())
            zip.writeEntry("xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
                </Relationships>""".trimIndent())
            zip.writeEntry("xl/worksheets/sheet1.xml", buildSheetXml(records))
            // 以下三个部件 Excel 可缺，但严格解析器（微信内置预览、部分 WPS）
            // 会因"包结构不完整"拒绝显示，故一并补上。
            zip.writeEntry("xl/styles.xml", XLSX_STYLES)
            zip.writeEntry("docProps/core.xml", XLSX_CORE)
            zip.writeEntry("docProps/app.xml", XLSX_APP)
        }
    }

    /** 最小可用样式表。 */
    private val XLSX_STYLES = """<?xml version="1.0" encoding="UTF-8"?>
        <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
        <fonts count="1"><font><sz val="11"/><name val="Calibri"/></font></fonts>
        <fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>
        <borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
        <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
        <cellXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/></cellXfs>
        <cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
        </styleSheet>""".trimIndent()

    private val XLSX_CORE = """<?xml version="1.0" encoding="UTF-8"?>
        <cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
        <dc:title>WMS导入</dc:title><dc:creator>LabelScanner</dc:creator>
        <cp:lastModifiedBy>LabelScanner</cp:lastModifiedBy>
        <dcterms:created xsi:type="dcterms:W3CDTF">2026-01-01T00:00:00Z</dcterms:created>
        <dcterms:modified xsi:type="dcterms:W3CDTF">2026-01-01T00:00:00Z</dcterms:modified>
        </cp:coreProperties>""".trimIndent()

    private val XLSX_APP = """<?xml version="1.0" encoding="UTF-8"?>
        <Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties" xmlns:vt="http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes">
        <Application>LabelScanner</Application>
        </Properties>""".trimIndent()

    private fun ZipOutputStream.writeEntry(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(StandardCharsets.UTF_8))
        closeEntry()
    }

    private fun buildSheetXml(records: List<LabelResult>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        append("<cols>")
        for (column in 1..15) append("<col min=\"$column\" max=\"$column\" width=\"18\" customWidth=\"1\"/>")
        append("</cols><sheetData>")
        appendXmlRow(1, arrayOf("1") + WMS_DATA_HEADERS)
        appendXmlRow(2, arrayOf("") + WMS_LABEL_HEADERS)
        records.forEachIndexed { index, record -> appendXmlRow(index + 3, arrayOf("") + buildWmsRow(record)) }
        append("</sheetData></worksheet>")
    }

    private fun StringBuilder.appendXmlRow(rowNumber: Int, values: Array<String>) {
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
        var value = index
        val out = StringBuilder()
        while (value > 0) {
            value--
            out.append(('A'.code + value % 26).toChar())
            value /= 26
        }
        return out.reverse().toString()
    }

    private fun xmlEscape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    /** API 29+：MediaStore.Downloads（免权限，公共可见，卸载不删） */
    private fun exportViaMediaStore(
        context: Context,
        records: List<LabelResult>,
        fileName: String,
        rowBuilder: (LabelResult) -> String,
    ): Uri? {
        try {
            val header = if (fileName.startsWith("wms_")) {
                "DATA01,DATA02,DATA03,DATA04,DATA05,DATA06,DATA07,DATA08,DATA09,DATA10,DATA11,DATA12,DATA13,DATA14"
            } else {
                "条码,OCR,供应商,序列号,物料编码,数量,生产日期,69码,型号,颜色,硒鼓"
            }

            val csv = buildString {
                appendLine(header)
                for (r in records) appendLine(rowBuilder(r))
            }

            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            // 先删同名
            context.contentResolver.delete(
                collection,
                "${MediaStore.Downloads.DISPLAY_NAME} = ?",
                arrayOf(fileName)
            )
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                put(MediaStore.Downloads.RELATIVE_PATH, EXT_DIR)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(collection, values)
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray(StandardCharsets.UTF_8)) }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
            }
            return uri
        } catch (e: Exception) {
            return null
        }
    }

    /** API 26-28：外部文件目录（需要 WRITE_EXTERNAL_STORAGE 权限，卸载删） */
    private fun exportViaLegacyDir(
        context: Context,
        records: List<LabelResult>,
        fileName: String,
        rowBuilder: (LabelResult) -> String,
    ): Uri? {
        try {
            val dir = File(context.getExternalFilesDir(null), "LabelScanner").apply { mkdirs() }
            val file = File(dir, fileName)

            val header = if (fileName.startsWith("wms_")) {
                "DATA01,DATA02,DATA03,DATA04,DATA05,DATA06,DATA07,DATA08,DATA09,DATA10,DATA11,DATA12,DATA13,DATA14"
            } else {
                "条码,OCR,供应商,序列号,物料编码,数量,生产日期,69码,型号,颜色,硒鼓"
            }

            val csv = buildString {
                appendLine(header)
                for (r in records) appendLine(rowBuilder(r))
            }
            file.writeText(csv, StandardCharsets.UTF_8)
            return Uri.fromFile(file)
        } catch (e: Exception) {
            return null
        }
    }
}