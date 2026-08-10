package com.anglesgirl.labelscanner.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.anglesgirl.labelscanner.model.LabelResult
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * 导出工具：CSV / WMS 格式
 *
 * CSV（原有兼容）：标签字段 + 原始条码/OCR
 * WMS（新增）：库存导入模板 DATA01~DATA14
 *   DATA01 库位           -> 固定 "STAGE"
 *   DATA02 卡板/托盘      -> trayCode
 *   DATA03 物料编码       -> materialCode
 *   DATA04 箱号           -> serialNumber
 *   DATA05 数量           -> 1 (固定)
 *   DATA06 工厂           -> 留空
 *   DATA07 库存地         -> 留空
 *   DATA08 生产日期-yyyymmdd -> productionDate
 *   DATA09 销售公司       -> 留空
 *   DATA10 销售订单|行号   -> 固定 "|"
 *   DATA11 供应商         -> 留空
 *   DATA12 特别加工指示书编号 -> 留空
 *   DATA13 WCS库位        -> 留空
 *   DATA14 SN码           -> serialNumber (同 DATA04)
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

    /** WMS 导出：库存导入模板 DATA01~DATA14（按用户提供的实际模板格式） */
    fun exportWms(context: Context, records: List<LabelResult>): Uri? {
        // 文件名用托盘码，如果有多个托盘码取第一个，或用时间戳兜底
        val trayCode = records.firstOrNull()?.trayCode?.takeIf { it.isNotBlank() }
        val fileName = if (trayCode != null) "wms_${trayCode}.csv" else "wms_import_${System.currentTimeMillis()}.csv"
        return if (Build.VERSION.SDK_INT >= 29) {
            exportViaMediaStore(context, records, fileName, ::buildWmsRow)
        } else {
            exportViaLegacyDir(context, records, fileName, ::buildWmsRow)
        }
    }

    private fun buildCsvRow(r: LabelResult): String {
        return "\"${r.barcodes.joinToString("|")}\",\"${r.ocrText}\",\"${r.supplier}\",\"${r.serialNumber}\",\"${r.materialCode}\",${r.quantity},\"${r.productionDate}\",\"${r.ean69}\",\"${r.model}\",\"${r.color}\",\"${r.tonerModel}\""
    }

    private fun buildWmsRow(r: LabelResult): String {
        val trayCode = r.trayCode.ifBlank { "" }
        return "\"STAGE\",\"$trayCode\",\"${r.materialCode}\",\"${r.serialNumber}\",1,\"\",\"\",\"${r.productionDate}\",\"\",\"|\",\"\",\"\",\"\",\"${r.serialNumber}\""
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