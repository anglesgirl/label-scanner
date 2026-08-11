package com.anglesgirl.labelscanner.data

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import org.json.JSONObject
import java.io.File

/**
 * 69 码 → 物料编码 反查映射表（本地自动积累）。
 *
 * 【双写策略 2026-08】与 RecordStore 一致：
 *  - 内部：SharedPreferences（快速读取）
 *  - 外部：公共 Download/LabelScanner/barcode69_lookup.json（卸载不删）
 * 目的：App 更新需卸载重装时，反查积累不丢失，重装后自动恢复。
 *
 * 每次 learn() 双写；lookup() 读内部，内部空则从外部恢复。
 */
class Barcode69Lookup(context: Context) {

    private val appContext: Context = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("barcode69_lookup", Context.MODE_PRIVATE)

    private companion object {
        const val EXT_FILE = "barcode69_lookup.json"
        const val EXT_DIR = "Download/LabelScanner"
    }

    /** 反查：69 码 → 物料编码，查不到返回 null */
    fun lookup(ean69: String): String? {
        if (ean69.isBlank()) return null
        val map = readMap()
        return map.optString(ean69.trim()).takeIf { it.isNotBlank() }
    }

    /** 远程反查（Turso 库，设置页配置）：本地 miss 时调用；命中自动缓存到本地表 */
    fun lookupRemote(ean69: String, onResult: (String?) -> Unit) {
        val ean = ean69.trim()
        if (ean.isEmpty()) {
            onResult(null); return
        }
        // 本地先查（可能并行调用已在本地命中）
        lookup(ean)?.let { onResult(it); return }
        val baseUrl = com.anglesgirl.labelscanner.SettingsActivity.getUrl(appContext)
        val token = com.anglesgirl.labelscanner.SettingsActivity.getToken(appContext)
        if (baseUrl.isEmpty() || token.isEmpty()) {
            onResult(null); return
        }
        Thread {
            try {
                val hit = Turso69Client.lookup(ean, baseUrl, token)
                if (hit != null) {
                    learn(ean, hit.first)
                    onResult(hit.first)
                } else {
                    onResult(null)
                }
            } catch (e: Exception) {
                onResult(null)
            }
        }.start()
    }

    /** 学习：保存 69码→物料 映射（两值都非空才存），内部+外部双写 */
    fun learn(ean69: String, materialCode: String) {
        val e = ean69.trim()
        val m = materialCode.trim()
        if (e.isEmpty() || m.isEmpty()) return
        val map = readMap()
        if (map.optString(e) != m) {
            map.put(e, m)
            prefs.edit().putString("map", map.toString()).apply()
            writeExternal(map)
        }
    }

    /** 当前映射条数 */
    fun size(): Int = readMap().length()

    private fun readMap(): JSONObject {
        // 内部 SP 优先
        val raw = prefs.getString("map", null)
        if (raw != null) {
            return try {
                JSONObject(raw)
            } catch (e: Exception) {
                JSONObject()
            }
        }
        // 内部空 → 外部恢复
        val external = readExternal()
        if (external.length() > 0) {
            prefs.edit().putString("map", external.toString()).apply()
            return external
        }
        return JSONObject()
    }

    // ---------- 外部 ----------

    private fun readExternal(): JSONObject {
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                val projection = arrayOf(
                    MediaStore.Downloads._ID,
                    MediaStore.Downloads.DISPLAY_NAME,
                )
                val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ?"
                val args = arrayOf(EXT_FILE)
                appContext.contentResolver.query(
                    collection, projection, selection, args, null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                        val uri = Uri.withAppendedPath(collection, id.toString())
                        val text = appContext.contentResolver.openInputStream(uri)
                            ?.bufferedReader()?.use { it.readText() }
                        if (!text.isNullOrBlank()) JSONObject(text) else JSONObject()
                    } else JSONObject()
                } ?: JSONObject()
            } else {
                val f = File(appContext.getExternalFilesDir(null), EXT_FILE)
                if (f.exists()) JSONObject(f.readText()) else JSONObject()
            }
        } catch (e: Exception) {
            JSONObject()
        }
    }

    private fun writeExternal(map: JSONObject) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                appContext.contentResolver.delete(
                    collection,
                    "${MediaStore.Downloads.DISPLAY_NAME} = ?",
                    arrayOf(EXT_FILE)
                )
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, EXT_FILE)
                    put(MediaStore.Downloads.MIME_TYPE, "application/json")
                    put(MediaStore.Downloads.RELATIVE_PATH, EXT_DIR)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = appContext.contentResolver.insert(collection, values)
                if (uri != null) {
                    appContext.contentResolver.openOutputStream(uri)
                        ?.use { it.write(map.toString().toByteArray()) }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    appContext.contentResolver.update(uri, values, null, null)
                }
            } else {
                val f = File(appContext.getExternalFilesDir(null), EXT_FILE)
                f.writeText(map.toString())
            }
        } catch (e: Exception) {
            // 忽略，内部 SP 还在
        }
    }
}
