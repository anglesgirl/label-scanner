package com.anglesgirl.labelscanner.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Turso libsql HTTP API 客户端（台账：库存 + 出入流水）。
 * 写法沿用 Turso69Client：HttpURLConnection + org.json，零新依赖。
 * 所有函数都是同步阻塞调用，只能在后台线程调用（调用方走 DbExecutor）。
 * 连接地址 + token 一律复用 SettingsActivity.getUrl()/getToken()，本文件不保存任何凭据；
 * 返回的错误信息只含服务端消息，不含地址或 token。
 */
object TursoLedgerClient {

    data class StockRow(
        val materialCode: String,
        val location: String,
        val trayCode: String,
        val balance: Int,
        val updatedAt: Long,
    )

    data class MovementRow(
        val clientId: String,
        val moveType: String,
        val materialCode: String,
        val quantity: Int,
        val location: String,
        val trayCode: String,
        val balanceAfter: Int,
        val createdAt: Long,
    )

    /** 首次使用台账时建表（已存在则不动）。成功返回 null，失败返回错误信息。 */
    fun ensureTables(baseUrl: String, token: String): String? {
        val stockSql = "CREATE TABLE IF NOT EXISTS ledger_stock (" +
            "material_code TEXT NOT NULL, " +
            "location TEXT NOT NULL, " +
            "tray_code TEXT NOT NULL, " +
            "balance INTEGER NOT NULL DEFAULT 0, " +
            "updated_at INTEGER NOT NULL DEFAULT 0, " +
            "PRIMARY KEY (material_code, location, tray_code))"
        val moveSql = "CREATE TABLE IF NOT EXISTS ledger_movements (" +
            "client_id TEXT PRIMARY KEY NOT NULL, " +
            "move_type TEXT NOT NULL, " +
            "material_code TEXT NOT NULL, " +
            "quantity INTEGER NOT NULL, " +
            "location TEXT NOT NULL, " +
            "tray_code TEXT NOT NULL, " +
            "balance_after INTEGER NOT NULL, " +
            "created_at INTEGER NOT NULL)"
        execute(baseUrl, token, stockSql, emptyList())?.let { return it }
        return execute(baseUrl, token, moveSql, emptyList())
    }

    /** 库存 upsert（按物料+库位+托盘主键覆盖）。成功返回 null，失败返回错误信息。 */
    fun upsertStock(baseUrl: String, token: String, row: StockRow): String? {
        val sql = "INSERT OR REPLACE INTO ledger_stock " +
            "(material_code, location, tray_code, balance, updated_at) VALUES (?, ?, ?, ?, ?)"
        return execute(
            baseUrl, token, sql,
            listOf(
                txt(row.materialCode), txt(row.location), txt(row.trayCode),
                num(row.balance.toLong()), num(row.updatedAt),
            ),
        )
    }

    /** 流水写入（按 client_id 去重，已存在则忽略）。成功返回 null，失败返回错误信息。 */
    fun insertMovement(baseUrl: String, token: String, mv: MovementRow): String? {
        val sql = "INSERT OR IGNORE INTO ledger_movements " +
            "(client_id, move_type, material_code, quantity, location, tray_code, balance_after, created_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
        return execute(
            baseUrl, token, sql,
            listOf(
                txt(mv.clientId), txt(mv.moveType), txt(mv.materialCode),
                num(mv.quantity.toLong()), txt(mv.location), txt(mv.trayCode),
                num(mv.balanceAfter.toLong()), num(mv.createdAt),
            ),
        )
    }

    /** 全量库存（远程）。返回（行，错误信息），错误时行是空表。 */
    fun listStock(baseUrl: String, token: String): Pair<List<StockRow>, String?> {
        val (rows, err) = query(
            baseUrl, token,
            "SELECT material_code, location, tray_code, balance, updated_at FROM ledger_stock " +
                "ORDER BY material_code, location, tray_code",
            emptyList(),
        )
        if (err != null) return emptyList() to err
        return rows.mapNotNull { r ->
            if (r.size < 5) null
            else StockRow(r[0], r[1], r[2], r[3].toIntOrNull() ?: 0, r[4].toLongOrNull() ?: 0L)
        } to null
    }

    /** 按物料编码查库存（远程）。 */
    fun lookupStock(baseUrl: String, token: String, materialCode: String): Pair<List<StockRow>, String?> {
        val (rows, err) = query(
            baseUrl, token,
            "SELECT material_code, location, tray_code, balance, updated_at FROM ledger_stock " +
                "WHERE material_code = ? ORDER BY location, tray_code",
            listOf(txt(materialCode)),
        )
        if (err != null) return emptyList() to err
        return rows.mapNotNull { r ->
            if (r.size < 5) null
            else StockRow(r[0], r[1], r[2], r[3].toIntOrNull() ?: 0, r[4].toLongOrNull() ?: 0L)
        } to null
    }

    /** 按托盘号查库存（远程）。 */
    fun lookupStockByTray(baseUrl: String, token: String, trayCode: String): Pair<List<StockRow>, String?> {
        val (rows, err) = query(
            baseUrl, token,
            "SELECT material_code, location, tray_code, balance, updated_at FROM ledger_stock " +
                "WHERE tray_code = ? ORDER BY material_code, location",
            listOf(txt(trayCode)),
        )
        if (err != null) return emptyList() to err
        return rows.mapNotNull { r ->
            if (r.size < 5) null
            else StockRow(r[0], r[1], r[2], r[3].toIntOrNull() ?: 0, r[4].toLongOrNull() ?: 0L)
        } to null
    }

    /** 全量流水（远程，按时间倒序，最多 500 条）。 */
    fun listMovements(baseUrl: String, token: String): Pair<List<MovementRow>, String?> {
        val (rows, err) = query(
            baseUrl, token,
            "SELECT client_id, move_type, material_code, quantity, location, tray_code, balance_after, created_at " +
                "FROM ledger_movements ORDER BY created_at DESC LIMIT 500",
            emptyList(),
        )
        if (err != null) return emptyList() to err
        return rows.mapNotNull { r ->
            if (r.size < 8) null
            else MovementRow(
                r[0], r[1], r[2], r[3].toIntOrNull() ?: 0, r[4], r[5],
                r[6].toIntOrNull() ?: 0, r[7].toLongOrNull() ?: 0L,
            )
        } to null
    }

    /** 执行一条写语句（CREATE / INSERT / REPLACE）。成功返回 null，失败返回错误信息。 */
    private fun execute(baseUrl: String, token: String, sql: String, args: List<JSONObject>): String? {
        val body = JSONObject().put("requests", JSONArray().put(
            JSONObject().put("type", "execute").put("stmt", JSONObject()
                .put("sql", sql)
                .put("args", JSONArray(args))),
        ))
        val resp = post(baseUrl, token, body) ?: return "请求失败"
        val first = resp.optJSONArray("results")?.optJSONObject(0)
            ?: return "响应异常: ${resp.optString("error")}"
        return if (first.optString("type") == "ok") null
        else first.optJSONObject("error")?.optString("message") ?: "未知错误"
    }

    /** 执行一条查语句。返回（行单元格字符串表，错误信息），错误时行是空表。 */
    private fun query(baseUrl: String, token: String, sql: String, args: List<JSONObject>): Pair<List<List<String>>, String?> {
        val body = JSONObject().put("requests", JSONArray().put(
            JSONObject().put("type", "execute").put("stmt", JSONObject()
                .put("sql", sql)
                .put("args", JSONArray(args))),
        ))
        val resp = post(baseUrl, token, body) ?: return emptyList() to "请求失败"
        val first = resp.optJSONArray("results")?.optJSONObject(0)
            ?: return emptyList() to "响应异常: ${resp.optString("error")}"
        if (first.optString("type") != "ok") {
            return emptyList() to (first.optJSONObject("error")?.optString("message") ?: "未知错误")
        }
        val rowsJson = first.optJSONObject("response")?.optJSONObject("result")?.optJSONArray("rows")
            ?: return emptyList() to null
        val rows = mutableListOf<List<String>>()
        for (i in 0 until rowsJson.length()) {
            val rowJson = rowsJson.optJSONArray(i) ?: continue
            val cells = mutableListOf<String>()
            for (j in 0 until rowJson.length()) {
                cells += rowJson.optJSONObject(j)?.optString("value", "") ?: ""
            }
            rows += cells
        }
        return rows to null
    }

    private fun txt(v: String) = JSONObject().put("type", "text").put("value", v)

    private fun num(v: Long) = JSONObject().put("type", "integer").put("value", v.toString())

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
