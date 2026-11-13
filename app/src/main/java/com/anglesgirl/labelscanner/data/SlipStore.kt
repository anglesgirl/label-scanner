package com.anglesgirl.labelscanner.data

import android.content.ContentValues
import android.content.Context
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 单据仓库：送货单/退货单照片 + 编号。
 *
 * 流程：先拍单据照片 → 给单号 → 该单成为“当前单据”，之后采集的数据
 * 自动带上单号；导出时按单号归集托盘，导出成单个 Excel（WMS 表 + 单据照片 + 附件表）。
 * 照片存应用私有目录 files/slips，不进系统相册；单据表走 onOpen 自愈建表。
 */
object SlipStore {

    data class Slip(val slipNo: String, val photoPath: String, val createdAt: Long)

    private const val PREFS = "slip_prefs"
    private const val CURRENT_SLIP_KEY = "current_slip_no"

    /** 当前单据编号（空白 = 还没拍过单据）。 */
    fun getCurrent(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(CURRENT_SLIP_KEY, "") ?: ""

    fun setCurrent(context: Context, slipNo: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(CURRENT_SLIP_KEY, slipNo).apply()
    }

    /** 保存单据（同号重拍 = 替换照片），并设为当前单据。 */
    fun saveSlip(context: Context, slipNo: String, photoPath: String) {
        val db = LocalDatabase.get(context).writableDatabase
        val values = ContentValues().apply {
            put("slip_no", slipNo)
            put("photo_path", photoPath)
            put("created_at", System.currentTimeMillis())
        }
        db.insertWithOnConflict("slips", null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
        setCurrent(context, slipNo)
    }

    fun getSlip(context: Context, slipNo: String): Slip? {
        val db = LocalDatabase.get(context).readableDatabase
        db.rawQuery(
            "SELECT slip_no, photo_path, created_at FROM slips WHERE slip_no = ? LIMIT 1",
            arrayOf(slipNo)
        ).use { c ->
            if (!c.moveToFirst()) return null
            return Slip(
                slipNo = c.getString(0) ?: "",
                photoPath = c.getString(1) ?: "",
                createdAt = c.getLong(2)
            )
        }
    }

    /** 相机输出临时文件（FileProvider 只覆盖 cache/captures，拍完再挪到私有 slips 目录）。 */
    fun newTempPhotoFile(context: Context): File {
        val dir = File(context.cacheDir, "captures").apply { mkdirs() }
        return File(dir, "tmp_slip_${System.currentTimeMillis()}.jpg")
    }

    /** 把拍好的临时照片归档到 files/slips，返回归档路径。 */
    fun storePhoto(context: Context, tmpFile: File, slipNo: String): String {
        val dir = File(context.filesDir, "slips").apply { mkdirs() }
        val safeNo = slipNo.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val dest = File(dir, "${safeNo}_${System.currentTimeMillis()}.jpg")
        try {
            tmpFile.copyTo(dest, overwrite = true)
            tmpFile.delete()
        } catch (_: Exception) {
            // 归档失败就直接用临时文件路径（cache 可能被清，仅兜底）。
            return tmpFile.absolutePath
        }
        return dest.absolutePath
    }

    /** 默认单号：年月日时分秒（纸单有印号时用户在弹窗里改成印号）。 */
    fun generateSlipNo(): String =
        SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault()).format(Date())

    // ---------- 单据附件：表格/图片等单独文件，跟照片一样归进单据 ----------

    data class SlipFile(val id: Long, val slipNo: String, val filePath: String, val fileName: String, val createdAt: Long)

    /** 外部文件临时落盘（FileProvider 只覆盖 cache/captures，归档时再挪到私有 slips 目录）。 */
    fun newTempFile(context: Context, displayName: String): File {
        val dir = File(context.cacheDir, "captures").apply { mkdirs() }
        val safe = displayName.replace(Regex("[^A-Za-z0-9_\\-.\\u4e00-\\u9fa5]"), "_").takeIf { it.isNotBlank() } ?: "file"
        return File(dir, "tmp_file_${System.currentTimeMillis()}_$safe")
    }

    /** 把外部文件归档到 files/slips（保留原文件名），返回归档路径。 */
    fun storeFile(context: Context, tmpFile: File, slipNo: String, displayName: String): String {
        val dir = File(context.filesDir, "slips").apply { mkdirs() }
        val safeNo = slipNo.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val safeName = displayName.replace(Regex("[^A-Za-z0-9_\\-.\\u4e00-\\u9fa5]"), "_").takeIf { it.isNotBlank() } ?: "file"
        val dest = File(dir, "${safeNo}_${System.currentTimeMillis()}_$safeName")
        try {
            tmpFile.copyTo(dest, overwrite = true)
            tmpFile.delete()
        } catch (_: Exception) {
            return tmpFile.absolutePath
        }
        return dest.absolutePath
    }

    /** 登记一个附件（文件已归档）。 */
    fun addFile(context: Context, slipNo: String, filePath: String, fileName: String) {
        val db = LocalDatabase.get(context).writableDatabase
        val values = ContentValues().apply {
            put("slip_no", slipNo)
            put("file_path", filePath)
            put("file_name", fileName)
            put("created_at", System.currentTimeMillis())
        }
        db.insert("slip_files", null, values)
    }

    /** 取某单号的全部附件（按添加顺序）。 */
    fun getFiles(context: Context, slipNo: String): List<SlipFile> {
        val db = LocalDatabase.get(context).readableDatabase
        db.rawQuery(
            "SELECT id, slip_no, file_path, file_name, created_at FROM slip_files WHERE slip_no = ? ORDER BY created_at",
            arrayOf(slipNo)
        ).use { c ->
            val out = mutableListOf<SlipFile>()
            while (c.moveToNext()) {
                out += SlipFile(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: "", c.getLong(4))
            }
            return out
        }
    }
}

/** 单据编号输入弹窗（共用）：初始值可改，空号不保存。 */
object SlipUi {
    fun showSlipNumberDialog(
        activity: AppCompatActivity,
        initial: String,
        onConfirm: (String) -> Unit,
    ) {
        val input = EditText(activity).apply {
            hint = "输入单号（纸单上印的号）"
            setText(initial)
            setSelection(text.length)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(activity)
            .setTitle("单据编号")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val no = input.text.toString().trim()
                if (no.isEmpty()) {
                    Toast.makeText(activity, "单号不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                onConfirm(no)
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
