package com.anglesgirl.labelscanner.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.anglesgirl.labelscanner.model.LabelResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 本地数据存储：已保存的标签记录。
 *
 * 【双写策略 2026-08】
 *  - 内部：filesDir/label_records.json（运行期快速读写）
 *  - 外部：公共 Download/LabelScanner/label_records.json（MediaStore，卸载不删）
 * 目的：App 更新需卸载重装时，内部数据会清空，外部公共目录文件保留，
 *       重装后 load() 自动从外部恢复。
 *
 * 写：内部 + 外部都写；读：内部优先，内部空则从外部恢复。
 */
object RecordStore {

    private const val FILE_NAME = "label_records.json"
    private const val EXT_DIR = "Download/LabelScanner"

    /** 读取全部记录（内部优先，内部空则从外部恢复） */
    fun load(context: Context): MutableList<LabelResult> {
        // 1. 内部
        val internal = readInternal(context)
        if (internal.isNotEmpty()) return internal

        // 2. 内部空 → 外部恢复
        val external = readExternal(context)
        if (external.isNotEmpty()) {
            // 恢复到内部，下次直接读内部
            writeInternal(context, external)
            return external
        }
        return mutableListOf()
    }

    /** 按托盘码查询记录 */
    fun loadByTrayCode(context: Context, trayCode: String): MutableList<LabelResult> {
        return load(context).filter { it.trayCode == trayCode }.toMutableList()
    }

    /** 获取所有已用的托盘码列表 */
    fun getAllTrayCodes(context: Context): List<String> {
        return load(context)
            .filter { it.trayCode.isNotBlank() }
            .map { it.trayCode }
            .distinct()
    }

    /** 全量保存：内部 + 外部双写 */
    fun save(context: Context, records: List<LabelResult>) {
        writeInternal(context, records)
        writeExternal(context, records)
    }

    /** 追加记录（保留已有数据，只加新的） */
    fun append(context: Context, newRecords: List<LabelResult>) {
        val existing = load(context)
        val combined = (existing + newRecords).distinctBy { it.serialNumber }
        writeInternal(context, combined)
        writeExternal(context, combined)
    }

    // ---------- 内部 ----------

    private fun internalFile(context: Context): File = File(context.filesDir, FILE_NAME)

    private fun readInternal(context: Context): MutableList<LabelResult> {
        val file = internalFile(context)
        if (!file.exists()) return mutableListOf()
        return try {
            parseJson(file.readText())
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun writeInternal(context: Context, records: List<LabelResult>) {
        try {
            internalFile(context).writeText(toJson(records))
        } catch (e: Exception) {
            // 忽略，外部还在
        }
    }

    // ---------- 外部（公共 Download 目录，卸载不删） ----------

    /** 读外部：优先 MediaStore（API 29+），fallback 外部文件目录 */
    private fun readExternal(context: Context): MutableList<LabelResult> {
        return if (Build.VERSION.SDK_INT >= 29) {
            readViaMediaStore(context)
        } else {
            readViaLegacyDir(context)
        }
    }

    private fun writeExternal(context: Context, records: List<LabelResult>) {
        if (Build.VERSION.SDK_INT >= 29) {
            writeViaMediaStore(context, records)
        } else {
            writeViaLegacyDir(context, records)
        }
    }

    /** API 29+：MediaStore.Downloads（免权限，公共可见，卸载不删） */
    private fun readViaMediaStore(context: Context): MutableList<LabelResult> {
        return try {
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Downloads._ID,
                MediaStore.Downloads.DISPLAY_NAME,
            )
            val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ?"
            val args = arrayOf(FILE_NAME)
            context.contentResolver.query(
                collection, projection, selection, args, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                    val uri = Uri.withAppendedPath(collection, id.toString())
                    val text = context.contentResolver.openInputStream(uri)
                        ?.bufferedReader()?.use { it.readText() }
                    if (!text.isNullOrBlank()) parseJson(text) else mutableListOf()
                } else mutableListOf()
            } ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun writeViaMediaStore(context: Context, records: List<LabelResult>) {
        try {
            val json = toJson(records)
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            // 先删旧的（防止重复）
            context.contentResolver.delete(
                collection,
                "${MediaStore.Downloads.DISPLAY_NAME} = ?",
                arrayOf(FILE_NAME)
            )
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.RELATIVE_PATH, EXT_DIR)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(collection, values)
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
            }
        } catch (e: Exception) {
            // 忽略
        }
    }

    /** API 26-28：外部文件目录（需要 WRITE_EXTERNAL_STORAGE 权限，卸载删） */
    private fun legacyDir(context: Context): File =
        File(context.getExternalFilesDir(null), FILE_NAME)

    private fun readViaLegacyDir(context: Context): MutableList<LabelResult> {
        val f = legacyDir(context)
        return try {
            if (f.exists()) parseJson(f.readText()) else mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun writeViaLegacyDir(context: Context, records: List<LabelResult>) {
        try {
            legacyDir(context).writeText(toJson(records))
        } catch (e: Exception) {
            // 忽略
        }
    }

    // ---------- JSON ----------

    private fun toJson(records: List<LabelResult>): String {
        val arr = JSONArray()
        for (r in records) {
            arr.put(
                JSONObject()
                    .put("barcodes", r.barcodes.joinToString("\n"))
                    .put("ocrText", r.ocrText)
                    .put("supplier", r.supplier)
                    .put("serialNumber", r.serialNumber)
                    .put("materialCode", r.materialCode)
                    .put("quantity", r.quantity)
                    .put("productionDate", r.productionDate)
                    .put("ean69", r.ean69)
                    .put("model", r.model)
                    .put("color", r.color)
                    .put("tonerModel", r.tonerModel)
                    .put("trayCode", r.trayCode)
            )
        }
        return arr.toString()
    }

    private fun parseJson(raw: String): MutableList<LabelResult> {
        val arr = JSONArray(raw)
        val list = mutableListOf<LabelResult>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                LabelResult(
                    barcodes = o.optString("barcodes", "").split("\n").filter { it.isNotBlank() },
                    ocrText = o.optString("ocrText", ""),
                    supplier = o.optString("supplier", "NA"),
                    serialNumber = o.optString("serialNumber", ""),
                    materialCode = o.optString("materialCode", ""),
                    quantity = o.optInt("quantity", 1),
                    productionDate = o.optString("productionDate", ""),
                    ean69 = o.optString("ean69", ""),
                    model = o.optString("model", ""),
                    color = o.optString("color", ""),
                    tonerModel = o.optString("tonerModel", ""),
                    trayCode = o.optString("trayCode", ""),
                )
            )
        }
        return list
    }
}
