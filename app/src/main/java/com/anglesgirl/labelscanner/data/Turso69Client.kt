package com.anglesgirl.labelscanner.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Turso libsql HTTP API 客户端（69 码反查库）。
 * 内部使用：连接地址 + token 由用户在设置页手动配置，App 不内置。
 * API: POST {base}/v2/pipeline，Authorization: Bearer <token>
 */
object Turso69Client {

    /** 查询：最终 ean → materialCode/materialName，未命中返回 null，异常返回 error 前缀 */
    fun lookup(ean: String, baseUrl: String, token: String): Pair<String, String?>? {
        val sql = "SELECT material_code, material_name FROM ean69_lookup WHERE ean = ?"
        val body = JSONObject().put("requests", JSONArray().put(
            JSONObject().put("type", "execute").put("stmt", JSONObject()
                .put("sql", sql)
                .put("args", JSONArray().put(
                    JSONObject().put("type", "text").put("value", ean)))
            )
        ))
        val resp = post(baseUrl, token, body)
            ?: return null
        val results = resp.optJSONArray("results")
        val first = results?.optJSONObject(0) ?: return null
        if (first.optString("type") != "ok") return null
        val rows = first.optJSONObject("response")?.optJSONObject("result")?.optJSONArray("rows")
            ?: return null
        if (rows.length() == 0) return null
        val row = rows.optJSONArray(0) ?: return null
        val code = row.optJSONObject(0)?.optString("value", "") ?: ""
        val name = row.optJSONObject(1)?.optString("value", "") ?: ""
        return if (code.isNotEmpty()) code to (name.ifEmpty { null }) else null
    }

    /** 写入/更新一条（管理端用）：成功返回 null，失败返回错误信息 */
    fun upsert(baseUrl: String, token: String, ean: String, materialCode: String, name: String?): String? {
        val sql = "INSERT OR REPLACE INTO ean69_lookup (ean, material_code, material_name, created_at) VALUES (?, ?, ?, ?)"
        val body = JSONObject().put("requests", JSONArray().put(
            JSONObject().put("type", "execute").put("stmt", JSONObject()
                .put("sql", sql)
                .put("args", JSONArray()
                    .put(txt(ean)).put(txt(materialCode)).put(txt(name ?: "")).put(txt("2026-08-11")))
            )
        ))
        val resp = post(baseUrl, token, body) ?: return "请求失败"
        val first = resp.optJSONArray("results")?.optJSONObject(0)
            ?: return "响应异常: ${resp.optString("error")}"
        return if (first.optString("type") == "ok") null
        else first.optJSONObject("error")?.optString("message") ?: "未知错误"
    }

    /** 连接测试：SELECT 1 */
    fun testConnection(baseUrl: String, token: String): String? {
        val body = JSONObject().put("requests", JSONArray().put(
            JSONObject().put("type", "execute").put("stmt", JSONObject().put("sql", "SELECT 1"))
        ))
        val resp = post(baseUrl, token, body) ?: return "网络/响应失败"
        val first = resp.optJSONArray("results")?.optJSONObject(0)
            ?: return "响应异常: ${resp.optString("error")}"
        return if (first.optString("type") == "ok") null
        else first.optJSONObject("error")?.optString("message") ?: "未知错误"
    }

    private fun txt(v: String) = JSONObject().put("type", "text").put("value", v)

    private fun post(baseUrl: String, token: String, body: JSONObject): JSONObject? {
        return try {
            // 兼容 libsql:// 前缀（Turso 客户端协议名）：HTTP API 必须用 https://
            val httpUrl = baseUrl.trim().replaceFirst("libsql://", "https://")
            val u = URL(if (httpUrl.endsWith("/v2/pipeline")) httpUrl else "$httpUrl/v2/pipeline")
            val conn = u.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 10000
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            if (code !in 200..299) {
                JSONObject().put("error", "HTTP $code")
            } else {
                conn.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            }
        } catch (e: Exception) {
            null
        }
    }
}