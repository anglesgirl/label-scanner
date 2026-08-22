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

/** 本地记录仓库。SQLite 是主数据源，旧 JSON 仅用于首次迁移和人工恢复。 */
object RecordStore {
    private const val FILE_NAME = "label_records.json"
    private const val EXT_DIR = "Download/LabelScanner"
    private const val PREFS = "local_database_migration"
    private const val IMPORTED = "records_imported"

    fun load(context: Context): MutableList<LabelResult> {
        ensureImported(context)
        val db = LocalDatabase.get(context).readableDatabase
        db.rawQuery("SELECT * FROM records ORDER BY id", null).use { cursor ->
            val list = mutableListOf<LabelResult>()
            while (cursor.moveToNext()) list += fromCursor(cursor)
            return list
        }
    }

    fun loadByTrayCode(context: Context, trayCode: String): MutableList<LabelResult> =
        load(context).filter { it.trayCode == trayCode }.toMutableList()

    fun getAllTrayCodes(context: Context): List<String> =
        load(context).map { it.trayCode }.filter { it.isNotBlank() }.distinct()

    /** 全量替换在一个事务内完成，兼容现有编辑页按下标保存的接口。 */
    fun save(context: Context, records: List<LabelResult>) {
        ensureImported(context)
        val database = LocalDatabase.get(context).writableDatabase
        database.beginTransaction()
        try {
            database.delete("records", null, null)
            records.forEach { insert(database, it) }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        writeExternal(context, records)
    }

    fun append(context: Context, newRecords: List<LabelResult>) {
        ensureImported(context)
        val database = LocalDatabase.get(context).writableDatabase
        database.beginTransaction()
        try {
            newRecords.forEach { record ->
                val values = values(record)
                val serial = record.serialNumber.trim()
                val updated = if (serial.isBlank()) 0 else database.update(
                    "records", values, "serial_number = ?", arrayOf(serial)
                )
                if (updated == 0) database.insertOrThrow("records", null, values)
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        writeExternal(context, load(context))
    }

    private fun ensureImported(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(IMPORTED, false)) return
        val database = LocalDatabase.get(context).writableDatabase
        database.beginTransaction()
        try {
            if (database.query("records", arrayOf("id"), null, null, null, null, null, "1").count == 0) {
                (readInternal(context).ifEmpty { readExternal(context) }).forEach { insert(database, it) }
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        prefs.edit().putBoolean(IMPORTED, true).apply()
    }

    private fun insert(db: android.database.sqlite.SQLiteDatabase, record: LabelResult) {
        db.insertOrThrow("records", null, values(record))
    }

    private fun values(r: LabelResult) = ContentValues().apply {
        put("barcodes", r.barcodes.joinToString("\n")); put("ocr_text", r.ocrText)
        put("supplier", r.supplier); put("serial_number", r.serialNumber)
        put("material_code", r.materialCode); put("quantity", r.quantity)
        put("production_date", r.productionDate); put("ean69", r.ean69)
        put("material_from_ean69", if (r.materialFromEan69) 1 else 0)
        put("model", r.model); put("color", r.color); put("toner_model", r.tonerModel)
        put("tray_code", r.trayCode); put("box_code", r.boxCode)
    }

    private fun fromCursor(c: android.database.Cursor) = LabelResult(
        barcodes = c.string("barcodes").split("\n").filter { it.isNotBlank() },
        ocrText = c.string("ocr_text"), supplier = c.string("supplier"),
        serialNumber = c.string("serial_number"), materialCode = c.string("material_code"),
        quantity = c.getInt(c.getColumnIndexOrThrow("quantity")),
        productionDate = c.string("production_date"), ean69 = c.string("ean69"),
        materialFromEan69 = c.getInt(c.getColumnIndexOrThrow("material_from_ean69")) != 0,
        model = c.string("model"), color = c.string("color"), tonerModel = c.string("toner_model"),
        trayCode = c.string("tray_code"), boxCode = c.string("box_code")
    )

    private fun android.database.Cursor.string(name: String): String =
        getString(getColumnIndexOrThrow(name)) ?: ""

    // ---------- Legacy JSON import / public recovery backup ----------
    private fun internalFile(context: Context) = File(context.filesDir, FILE_NAME)
    private fun readInternal(context: Context) = readJsonFile(internalFile(context))

    private fun readExternal(context: Context): MutableList<LabelResult> = if (Build.VERSION.SDK_INT >= 29) {
        try {
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            context.contentResolver.query(collection, arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME} = ?", arrayOf(FILE_NAME), null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val uri = Uri.withAppendedPath(collection, cursor.getLong(0).toString())
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { parseJson(it.readText()) }
                        ?: mutableListOf()
                } else mutableListOf()
            } ?: mutableListOf()
        } catch (_: Exception) { mutableListOf() }
    } else readJsonFile(File(context.getExternalFilesDir(null), FILE_NAME))

    private fun readJsonFile(file: File): MutableList<LabelResult> = try {
        if (file.exists()) parseJson(file.readText()) else mutableListOf()
    } catch (_: Exception) { mutableListOf() }

    private fun writeExternal(context: Context, records: List<LabelResult>) {
        try {
            val json = toJson(records)
            if (Build.VERSION.SDK_INT < 29) {
                File(context.getExternalFilesDir(null), FILE_NAME).writeText(json)
                return
            }
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            context.contentResolver.delete(collection, "${MediaStore.Downloads.DISPLAY_NAME} = ?", arrayOf(FILE_NAME))
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME); put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.RELATIVE_PATH, EXT_DIR); put(MediaStore.Downloads.IS_PENDING, 1)
            }
            context.contentResolver.insert(collection, values)?.let { uri ->
                context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
            }
        } catch (_: Exception) { /* SQLite remains the source of truth. */ }
    }

    private fun toJson(records: List<LabelResult>): String = JSONArray().apply {
        records.forEach { r -> put(JSONObject().apply {
            put("barcodes", r.barcodes.joinToString("\n")); put("ocrText", r.ocrText); put("supplier", r.supplier)
            put("serialNumber", r.serialNumber); put("materialCode", r.materialCode); put("quantity", r.quantity)
            put("productionDate", r.productionDate); put("ean69", r.ean69); put("materialFromEan69", r.materialFromEan69)
            put("model", r.model); put("color", r.color); put("tonerModel", r.tonerModel); put("trayCode", r.trayCode); put("boxCode", r.boxCode)
        }) }
    }.toString()

    private fun parseJson(raw: String): MutableList<LabelResult> = JSONArray(raw).let { arr ->
        MutableList(arr.length()) { i -> arr.getJSONObject(i).let { o -> LabelResult(
            barcodes = o.optString("barcodes").split("\n").filter { it.isNotBlank() }, ocrText = o.optString("ocrText"),
            supplier = o.optString("supplier", "NA"), serialNumber = o.optString("serialNumber"), materialCode = o.optString("materialCode"),
            quantity = o.optInt("quantity", 1), productionDate = o.optString("productionDate"), ean69 = o.optString("ean69"),
            materialFromEan69 = o.optBoolean("materialFromEan69"), model = o.optString("model"), color = o.optString("color"),
            tonerModel = o.optString("tonerModel"), trayCode = o.optString("trayCode"), boxCode = o.optString("boxCode")
        ) } }.toMutableList()
    }
}