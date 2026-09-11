package com.anglesgirl.labelscanner.export.v2

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.anglesgirl.labelscanner.data.v2.TraySessionV2
import java.io.File
import java.io.FileOutputStream

/**
 * v2 导出：把托盘会话写成 WMS 库存导入模板 .xlsx。
 *
 * 这里只做平台相关的事（写文件 / 分享 Uri）；**模板内容与 xlsx 字节
 * 由 [WmsWorkbook] 生成**（纯 Kotlin，可离线回归测试）。
 */
object ExporterV2 {

    private const val XLSX_MIME =
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    private const val EXT_DIR = "Download/LabelScanner"

    /** 一托盘全部记录 → 模板行（供测试与导出共用）。 */
    fun buildRows(tray: TraySessionV2): List<Array<String>> {
        val rows = ArrayList<Array<String>>()
        for (b in tray.allBoxes) {
            rows += WmsWorkbook.rowsForBox(
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

    /** 导出整个托盘，返回写入后的 Uri；失败返回 null。 */
    fun exportTray(context: Context, tray: TraySessionV2): Uri? {
        val fileName = "${tray.trayCode.ifBlank { "tray_" + System.currentTimeMillis() }}.xlsx"
        val bytes = WmsWorkbook.buildBytes(buildRows(tray))

        // API 29+：MediaStore（免存储权限，文件在"下载/LabelScanner"，卸载不删）
        val viaMediaStore = runCatching {
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            context.contentResolver.delete(
                collection,
                "${MediaStore.Downloads.DISPLAY_NAME} = ?",
                arrayOf(fileName),
            )
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, XLSX_MIME)
                put(MediaStore.Downloads.RELATIVE_PATH, EXT_DIR)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(collection, values)
                ?: error("无法创建导出文件")
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: error("无法打开导出文件")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
            uri
        }.getOrNull()
        if (viaMediaStore != null) return viaMediaStore

        // 旧系统兜底：应用私有目录 + FileProvider 分享
        return runCatching {
            val dir = File(context.getExternalFilesDir(null), "LabelScanner").apply { mkdirs() }
            val file = File(dir, fileName)
            FileOutputStream(file).use { it.write(bytes) }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull()
    }
}
