package com.anglesgirl.labelscanner.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.anglesgirl.labelscanner.model.LabelResult
import org.apache.poi.ss.usermodel.CellStyle
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.HorizontalAlignment
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

/**
 * 导出工具：CSV / WMS 格式
 *
 * CSV（原有兼容）：标签字段 + 原始条码/OCR
 * WMS（新增）：库存导入模板 DATA01~DATA14（支持 .csv 和 .xlsx 两种格式）
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

    /** 原有 CSV 导出（保留兼容） */
    fun export(context: Context, records: List<LabelResult>): Uri? {
        val fileName = "labels_${System.currentTimeMillis()}.csv"
        return if (Build.VERSION.SDK_INT >= 29) {
            exportViaMediaStore(context, records, fileName, ::buildCsvRow)
        } else {
            exportViaLegacyDir(context, records, fileName, ::buildCsvRow)
        }
    }

    /**
     * WMS 导出：库存导入模板 DATA01~DATA14（默认 .xlsx；POI 在 Android 不稳
     * 会 NoClassDefFoundError → 自动降级 .csv，保证导出不闪退）
     *
     * 模板结构（用户确认，xlsx 与 csv 完全一致）：
     *   第 1 行：1, DATA01, DATA02, ..., DATA14   （顶部 DATA 编码行，保留）
     *   第 2 行： , 库位, 卡板/托盘, 物料编码, 箱号, ..., SN码  （中文说明行，保留）
     *   第 3 行起：数据（A 列空，B~O 列 14 个值，与 DATA01..14 对齐）
     */
    fun exportWms(context: Context, records: List<LabelResult>): Uri? {
        val trayCode = records.firstOrNull()?.trayCode?.takeIf { it.isNotBlank() }
        val baseName = trayCode ?: "wms_import_${System.currentTimeMillis()}"
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                exportXlsxViaMediaStore(context, records, "$baseName.xlsx")
            } else {
                exportXlsxViaLegacyDir(context, records, "$baseName.xlsx")
            }
        } catch (e: Throwable) {
            // POI 失败（Android 上常见 NoClassDefFoundError）→ 降级 CSV
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    exportWmsCsvViaMediaStore(context, records, "$baseName.csv")
                } else {
                    exportWmsCsvViaLegacyDir(context, records, "$baseName.csv")
                }
            } catch (e2: Throwable) {
                e2.printStackTrace()
                null
            }
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

    /** WMS CSV（降级方案）：15 列对齐 —— A 列固定标记，B~O 列 DATA/说明/数据 */
    private fun exportWmsCsvRaw(records: List<LabelResult>): String {
        val sb = StringBuilder()
        // 第 1 行：DATA 编码行（顶部保留）
        sb.append("1,").append(WMS_DATA_HEADERS.joinToString(",")).append("\n")
        // 第 2 行：中文说明行（A 列空，与 DATA01 对齐）
        sb.append(",").append(WMS_LABEL_HEADERS.joinToString(",")).append("\n")
        // 数据行：A 列空，B~O 列 14 个值
        for (r in records) {
            sb.append(",").append(buildWmsRow(r).joinToString(",")).append("\n")
        }
        return sb.toString()
    }

    private fun exportWmsCsvViaMediaStore(context: Context, records: List<LabelResult>, fileName: String): Uri? {
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
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
        val uri = context.contentResolver.insert(collection, values) ?: return null
        context.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(exportWmsCsvRaw(records).toByteArray(Charsets.UTF_8))
        }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        context.contentResolver.update(uri, values, null, null)
        return uri
    }

    private fun exportWmsCsvViaLegacyDir(context: Context, records: List<LabelResult>, fileName: String): Uri? {
        val dir = File(context.getExternalFilesDir(null), "LabelScanner").apply { mkdirs() }
        val file = File(dir, fileName)
        file.writeText(exportWmsCsvRaw(records), Charsets.UTF_8)
        return androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
    }

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
    ): Uri? {
        try {
            val workbook = buildWmsWorkbook(records)

            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            // 先删同名
            context.contentResolver.delete(
                collection,
                "${MediaStore.Downloads.DISPLAY_NAME} = ?",
                arrayOf(fileName)
            )
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                put(MediaStore.Downloads.RELATIVE_PATH, EXT_DIR)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(collection, values)
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    workbook.write(outputStream)
                    workbook.close()
                }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
            }
            return uri
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    private fun exportXlsxViaLegacyDir(
        context: Context,
        records: List<LabelResult>,
        fileName: String,
    ): Uri? {
        try {
            val dir = File(context.getExternalFilesDir(null), "LabelScanner").apply { mkdirs() }
            val file = File(dir, fileName)
            val workbook = buildWmsWorkbook(records)
            FileOutputStream(file).use { outputStream ->
                workbook.write(outputStream)
                workbook.close()
            }
            return Uri.fromFile(file)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    private fun buildWmsWorkbook(records: List<LabelResult>): XSSFWorkbook {
        val workbook = XSSFWorkbook()
        val sheet = workbook.createSheet("WMS导入")

        // 表头样式：加粗、居中、浅灰背景
        val headerStyle = workbook.createCellStyle()
        headerStyle.alignment = HorizontalAlignment.CENTER
        val headerFont = workbook.createFont()
        headerFont.bold = true
        headerStyle.setFont(headerFont)
        headerStyle.fillForegroundColor = IndexedColors.GREY_25_PERCENT.index
        headerStyle.fillPattern = FillPatternType.SOLID_FOREGROUND

        // 说明行样式：居中、浅黄背景（与数据行区分，WMS 导入时可忽略）
        val labelStyle = workbook.createCellStyle()
        labelStyle.alignment = HorizontalAlignment.CENTER
        labelStyle.fillForegroundColor = IndexedColors.LIGHT_YELLOW.index
        labelStyle.fillPattern = FillPatternType.SOLID_FOREGROUND

        // 数据样式：居中
        val dataStyle = workbook.createCellStyle()
        dataStyle.alignment = HorizontalAlignment.CENTER

        // 第 1 行：顶部 DATA 编码行（保留）—— A 列 "1"，B~O 列 DATA01..DATA14
        val dataHeaderRow = sheet.createRow(0)
        dataHeaderRow.createCell(0).apply {
            setCellValue("1")
            cellStyle = headerStyle
        }
        for (i in WMS_DATA_HEADERS.indices) {
            dataHeaderRow.createCell(i + 1).apply {
                setCellValue(WMS_DATA_HEADERS[i])
                cellStyle = headerStyle
            }
        }

        // 第 2 行：中文说明行（保留）—— A 列空，B~O 列 库位/卡板/物料编码/箱号...
        val labelRow = sheet.createRow(1)
        for (i in WMS_LABEL_HEADERS.indices) {
            labelRow.createCell(i + 1).apply {
                setCellValue(WMS_LABEL_HEADERS[i])
                cellStyle = labelStyle
            }
        }

        // 数据行（第 3 行起）：A 列空，B~O 列 14 个值，与 DATA01..14 对齐
        for (rowIndex in records.indices) {
            val r = records[rowIndex]
            val row = sheet.createRow(rowIndex + 2)
            val values = buildWmsRow(r)
            for (colIndex in values.indices) {
                val cell = row.createCell(colIndex + 1)
                cell.setCellValue(values[colIndex])
                cell.cellStyle = dataStyle
            }
        }

        // 自动调整列宽（含 A 列）
        for (i in 0..WMS_DATA_HEADERS.size) {
            sheet.autoSizeColumn(i)
            // 设置最小宽度，防止太窄
            if (sheet.getColumnWidth(i) < 3000) {
                sheet.setColumnWidth(i, 3000)
            }
        }

        return workbook
    }

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