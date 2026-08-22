package com.anglesgirl.labelscanner.data

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import org.json.JSONObject
import java.io.File

/** 69 码映射仓库。SQLite 是主数据源，旧 SP/JSON 只在首次启动时导入。 */
class Barcode69Lookup(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("barcode69_lookup", Context.MODE_PRIVATE)

    fun lookup(ean69: String): String? {
        val ean = ean69.trim()
        if (ean.isEmpty()) return null
        ensureImported()
        LocalDatabase.get(appContext).readableDatabase.query(
            "barcode69_lookup", arrayOf("material_code"), "ean69 = ?", arrayOf(ean), null, null, null
        ).use { cursor -> return if (cursor.moveToFirst()) cursor.getString(0) else null }
    }

    fun lookupRemote(ean69: String, onResult: (String?) -> Unit) {
        val ean = ean69.trim()
        if (ean.isEmpty()) { onResult(null); return }
        lookup(ean)?.let { onResult(it); return }
        val baseUrl = com.anglesgirl.labelscanner.SettingsActivity.getUrl(appContext)
        val token = com.anglesgirl.labelscanner.SettingsActivity.getToken(appContext)
        if (baseUrl.isEmpty() || token.isEmpty()) { onResult(null); return }
        Thread {
            try {
                val hit = Turso69Client.lookup(ean, baseUrl, token)
                if (hit != null) { learn(ean, hit.first); onResult(hit.first) } else onResult(null)
            } catch (_: Exception) { onResult(null) }
        }.start()
    }

    fun learn(ean69: String, materialCode: String) {
        val ean = ean69.trim(); val material = materialCode.trim()
        if (ean.isEmpty() || material.isEmpty()) return
        ensureImported()
        val values = ContentValues().apply {
            put("ean69", ean); put("material_code", material); put("updated_at", System.currentTimeMillis())
        }
        LocalDatabase.get(appContext).writableDatabase.insertWithOnConflict(
            "barcode69_lookup", null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
        )
        writeExternal(readMap())
    }

    fun size(): Int {
        ensureImported()
        LocalDatabase.get(appContext).readableDatabase.rawQuery("SELECT COUNT(*) FROM barcode69_lookup", null).use {
            return if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    private fun ensureImported() {
        val marker = appContext.getSharedPreferences("local_database_migration", Context.MODE_PRIVATE)
        if (marker.getBoolean("lookup_imported", false)) return
        val db = LocalDatabase.get(appContext).writableDatabase
        db.beginTransaction()
        try {
            val old = readLegacyMap()
            val cursor = db.rawQuery("SELECT COUNT(*) FROM barcode69_lookup", null)
            val empty = cursor.use { it.moveToFirst() && it.getInt(0) == 0 }
            if (empty) {
                val now = System.currentTimeMillis()
                val values = ContentValues()
                old.keys().forEach { ean ->
                    val material = old.optString(ean).trim()
                    if (material.isNotEmpty()) {
                        values.clear(); values.put("ean69", ean); values.put("material_code", material); values.put("updated_at", now)
                        db.insertWithOnConflict("barcode69_lookup", null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE)
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        marker.edit().putBoolean("lookup_imported", true).apply()
    }

    private fun readMap(): JSONObject {
        val out = JSONObject()
        LocalDatabase.get(appContext).readableDatabase.rawQuery("SELECT ean69, material_code FROM barcode69_lookup", null).use { c ->
            while (c.moveToNext()) out.put(c.getString(0), c.getString(1))
        }
        return out
    }

    private fun readLegacyMap(): JSONObject {
        val raw = prefs.getString("map", null)
        if (raw != null) return try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                appContext.contentResolver.query(collection, arrayOf(MediaStore.Downloads._ID),
                    "${MediaStore.Downloads.DISPLAY_NAME} = ?", arrayOf(EXT_FILE), null)?.use { c ->
                    if (c.moveToFirst()) {
                        val uri = Uri.withAppendedPath(collection, c.getLong(0).toString())
                        appContext.contentResolver.openInputStream(uri)?.bufferedReader()?.use { JSONObject(it.readText()) } ?: JSONObject()
                    } else JSONObject()
                } ?: JSONObject()
            } else {
                val file = File(appContext.getExternalFilesDir(null), EXT_FILE)
                if (file.exists()) JSONObject(file.readText()) else JSONObject()
            }
        } catch (_: Exception) { JSONObject() }
    }

    private fun writeExternal(map: JSONObject) {
        try {
            if (Build.VERSION.SDK_INT < 29) {
                File(appContext.getExternalFilesDir(null), EXT_FILE).writeText(map.toString()); return
            }
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            appContext.contentResolver.delete(collection, "${MediaStore.Downloads.DISPLAY_NAME} = ?", arrayOf(EXT_FILE))
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, EXT_FILE); put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.RELATIVE_PATH, EXT_DIR); put(MediaStore.Downloads.IS_PENDING, 1)
            }
            appContext.contentResolver.insert(collection, values)?.let { uri ->
                appContext.contentResolver.openOutputStream(uri)?.use { it.write(map.toString().toByteArray()) }
                values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0); appContext.contentResolver.update(uri, values, null, null)
            }
        } catch (_: Exception) { /* SQLite remains the source of truth. */ }
    }

    private companion object {
        const val EXT_FILE = "barcode69_lookup.json"
        const val EXT_DIR = "Download/LabelScanner"
    }
}