package com.anglesgirl.labelscanner.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * 69 码 → 物料编码 反查映射表（本地自动积累）。
 *
 * 每次保存记录时若同时有 69 码和物料编码 → 自动学习入表；
 * 以后扫到只有 69 码的标签 → 反查填充物料编码。
 * 没有现成数据，靠日常扫码慢慢积累（用户确认）。
 */
class Barcode69Lookup(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("barcode69_lookup", Context.MODE_PRIVATE)

    /** 反查：69 码 → 物料编码，查不到返回 null */
    fun lookup(ean69: String): String? {
        if (ean69.isBlank()) return null
        return readMap().optString(ean69.trim()).takeIf { it.isNotBlank() }
    }

    /** 学习：保存 69码→物料 映射（两值都非空才存） */
    fun learn(ean69: String, materialCode: String) {
        val e = ean69.trim()
        val m = materialCode.trim()
        if (e.isEmpty() || m.isEmpty()) return
        val map = readMap()
        if (map.optString(e) != m) {
            map.put(e, m)
            prefs.edit().putString("map", map.toString()).apply()
        }
    }

    /** 当前映射条数 */
    fun size(): Int = readMap().length()

    private fun readMap(): JSONObject {
        val raw = prefs.getString("map", "{}") ?: "{}"
        return try {
            JSONObject(raw)
        } catch (e: Exception) {
            JSONObject()
        }
    }
}
