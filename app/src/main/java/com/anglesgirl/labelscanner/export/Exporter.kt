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
 *   DATA01 供应商           -> supplier
 *   DATA02 供应商批次       -> (留空/可扩展)
 *   DATA03 入库日期         -> 今日 yyyymmdd
 *   DATA04 物料编码         -> materialCode
 *   DATA05 物料名称         -> (留空)
 *   DATA06 规格型号         -> model
 *   DATA07 单位             -> PCS
 *   DATA08 数量             -> quantity
 *   DATA09 批次号/生产日期  -> productionDate
 *   DATA10 仓库编码         -> (留空/默认)
 *   DATA11 库区编码         -> (留空)
 *   DATA12 库位编码         -> (留空)
 *   DATA13 箱号/序列号      -> serialNumber
 *   DATA14 备注             -> ean69 / color / tonerModel / trayCode
 */
object Exporter {

    private const val EXT_DIR = "Download/LabelScanner"

    /** 原有 CSV 导出（保留兼容） */
    fun export(context: Context, records: List<LabelResult>): Uri? {
        val fileName = "labels_${System.currentTimeMillis()}.csv"
        return if (Build.VERSION.SDK_INT >= 29) {
            exportViaMediaStore(context, records, fileName) { r ->
                "\"${r.barcodes.joinToString("|")}\",\"${r.ocrText}\",\"${r.supplier}\",\"${r.serialNumber}\",\"${r.materialCode}\",${r.quantity},\"${r.productionDate}\",\"${r.ean69}\",\"${r.model}\",\"${r.color}\",\"${r.tonerModel}\""
            }
        } else {
            exportViaLegacyDir(context, records, fileName) { r ->
                "\"${r.barcodes.joinToString("|")}\",\"${r.ocrText}\",\"${r.supplier}\",\"${r.serialNumber}\",\"${r.materialCode}\",${r.quantity},\"${r.productionDate}\",\"${r.ean69}\",\"${r.model}\",\"${r.color}\",\"${r.tonerModel}\""
            }
        }
    }

    /** WMS 导出：库存导入模板 DATA01~DATA14 */
    fun exportWms(context: Context, records: List<LabelResult>): Uri? {
        val fileName = "wms_import_${System.currentTimeMillis()}.csv"
        return if (Build.VERSION.SDK_INT >= 29) {
            exportViaMediaStore(context, records, fileName) { r ->
                val today = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"))
                val remark = buildList {
                    if (r.ean69.isNotBlank()) add("69码:${r.ean69}")
                    if (r.color.isNotBlank()) add("颜色:${r.color}")
                    if (r.tonerModel.isNotBlank()) add("硒鼓:${r.tonerModel}")
                    if (r.trayCode.isNotBlank()) add("托盘:${r.trayCode}")
                }.joinToString("; ")
                "\"${r.supplier}\",\"\",\"$today\",\"${r.materialCode}\",\"\",\"${r.model}\",\"PCS\",${r.quantity},\"${r.productionDate}\",\"\",\"\",\"\",\"${r.serialNumber}\",\"$remark\""
            }
        } else {
            exportViaLegacyDir(context, records, fileName) { r ->
                val today = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"))
                val remark = buildList {
                    if (r.ean69.isNotBlank()) add("69码:${r.ean69}")
                    if (r.color.isNotBlank()) add("颜色:${r.color}")
                    if (r.tonerModel.isNotBlank()) add("硒鼓:${r.tonerModel}")
                    if (r.trayCode.isNotBlank()) add("托盘:${r.trayCode}")
                }.joinToString("; ")
                "\"${r.supplier}\",\"\",\"$today\",\"${r.materialCode}\",\"\",\"${r.model}\",\"PCS\",${r.quantity},\"${r.productionDate}\",\"\",\"\",\"\",\"${r.serialNumber}\",\"$remark\""
            }
        }
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