package com.anglesgirl.labelscanner.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.anglesgirl.labelscanner.SettingsActivity
import com.anglesgirl.labelscanner.model.LabelParser
import java.util.UUID

/**
 * 台账本地仓库：SQLite 是工作副本，Turso 是远程存档。
 *
 * 离线优先：登记只写本地库，不联网也能用；联网时再把脏数据推到 Turso，
 * 拉取时只覆盖本地不脏的行 —— 顺序是先推后拉，推失败直接返回，
 * 绝不让一次拉取盖掉还没推上去的本地改动。
 *
 * 所有函数都是同步调用，调用方必须走 DbExecutor（后台线程）。
 * 台账从空开始：这里没有任何把现有扫描记录导入成库存的逻辑，
 * 采集历史不是结存，混进去会算乱账。
 */
object LedgerStore {

    const val TYPE_IN = "入库"
    const val TYPE_OUT = "出库"

    /** 扫码解析结果：解析出的物料编码、托盘号，以及已有的对应库存行。 */
    data class Resolved(
        val materialCode: String,
        val trayCode: String,
        val stocks: List<TursoLedgerClient.StockRow>,
        val note: String,
    )

    // ---------------- 本地读 ----------------

    fun listStock(context: Context): List<TursoLedgerClient.StockRow> {
        val db = LocalDatabase.get(context).readableDatabase
        db.rawQuery(
            "SELECT material_code, location, tray_code, balance, updated_at FROM ledger_stock " +
                "ORDER BY material_code, location, tray_code",
            null,
        ).use { c ->
            val out = mutableListOf<TursoLedgerClient.StockRow>()
            while (c.moveToNext()) {
                out += TursoLedgerClient.StockRow(
                    c.getString(0), c.getString(1), c.getString(2),
                    c.getInt(3), c.getLong(4),
                )
            }
            return out
        }
    }

    fun listMovements(context: Context): List<TursoLedgerClient.MovementRow> {
        val db = LocalDatabase.get(context).readableDatabase
        db.rawQuery(
            "SELECT client_id, move_type, material_code, quantity, location, tray_code, balance_after, created_at " +
                "FROM ledger_movements ORDER BY created_at DESC LIMIT 500",
            null,
        ).use { c ->
            val out = mutableListOf<TursoLedgerClient.MovementRow>()
            while (c.moveToNext()) {
                out += TursoLedgerClient.MovementRow(
                    c.getString(0), c.getString(1), c.getString(2), c.getInt(3),
                    c.getString(4), c.getString(5), c.getInt(6), c.getLong(7),
                )
            }
            return out
        }
    }

    fun findLocalStocksByMaterial(context: Context, materialCode: String): List<TursoLedgerClient.StockRow> {
        val db = LocalDatabase.get(context).readableDatabase
        db.rawQuery(
            "SELECT material_code, location, tray_code, balance, updated_at FROM ledger_stock " +
                "WHERE material_code = ? ORDER BY location, tray_code",
            arrayOf(materialCode),
        ).use { c ->
            val out = mutableListOf<TursoLedgerClient.StockRow>()
            while (c.moveToNext()) {
                out += TursoLedgerClient.StockRow(
                    c.getString(0), c.getString(1), c.getString(2),
                    c.getInt(3), c.getLong(4),
                )
            }
            return out
        }
    }

    fun findLocalStocksByTray(context: Context, trayCode: String): List<TursoLedgerClient.StockRow> {
        val db = LocalDatabase.get(context).readableDatabase
        db.rawQuery(
            "SELECT material_code, location, tray_code, balance, updated_at FROM ledger_stock " +
                "WHERE tray_code = ? ORDER BY material_code, location",
            arrayOf(trayCode),
        ).use { c ->
            val out = mutableListOf<TursoLedgerClient.StockRow>()
            while (c.moveToNext()) {
                out += TursoLedgerClient.StockRow(
                    c.getString(0), c.getString(1), c.getString(2),
                    c.getInt(3), c.getLong(4),
                )
            }
            return out
        }
    }

    // ---------------- 本地登记（事务） ----------------

    /**
     * 本地登记一笔出入库，只动本地库，不联网。
     * 校验失败抛 IllegalArgumentException，文案可直接展示给用户。
     * 出库：无记录或库存不足都拦下；入库：无记录自动建账。
     */
    fun applyMovement(
        context: Context,
        moveType: String,
        materialCode: String,
        quantity: Int,
        location: String,
        trayCode: String,
    ): TursoLedgerClient.MovementRow {
        val material = materialCode.trim()
        val loc = location.trim()
        val tray = trayCode.trim()
        if (material.isEmpty()) throw IllegalArgumentException("请填写物料编码")
        if (quantity <= 0) throw IllegalArgumentException("数量必须大于 0")
        if (loc.isEmpty()) throw IllegalArgumentException("请填写库位")
        val db = LocalDatabase.get(context).writableDatabase
        db.beginTransaction()
        try {
            var balance = 0
            var found = false
            db.rawQuery(
                "SELECT balance FROM ledger_stock WHERE material_code = ? AND location = ? AND tray_code = ?",
                arrayOf(material, loc, tray),
            ).use { c ->
                if (c.moveToFirst()) {
                    found = true
                    balance = c.getInt(0)
                }
            }
            if (moveType == TYPE_OUT) {
                if (!found) throw IllegalArgumentException("无库存记录，无法出库")
                if (quantity > balance) throw IllegalArgumentException("库存不足，当前结存 $balance")
            }
            val newBalance = if (moveType == TYPE_IN) balance + quantity else balance - quantity
            val now = System.currentTimeMillis()
            val stockValues = ContentValues().apply {
                put("material_code", material)
                put("location", loc)
                put("tray_code", tray)
                put("balance", newBalance)
                put("updated_at", now)
                put("dirty", 1)
            }
            db.insertWithOnConflict(
                "ledger_stock", null, stockValues, SQLiteDatabase.CONFLICT_REPLACE,
            )
            val clientId = UUID.randomUUID().toString()
            val mvValues = ContentValues().apply {
                put("client_id", clientId)
                put("move_type", moveType)
                put("material_code", material)
                put("quantity", quantity)
                put("location", loc)
                put("tray_code", tray)
                put("balance_after", newBalance)
                put("created_at", now)
                put("dirty", 1)
            }
            db.insertOrThrow("ledger_movements", null, mvValues)
            db.setTransactionSuccessful()
            return TursoLedgerClient.MovementRow(
                clientId, moveType, material, quantity, loc, tray, newBalance, now,
            )
        } finally {
            db.endTransaction()
        }
    }

    // ---------------- 库存移动（事务） ----------------

    /** 托盘间移动结果：出库流水（源）与入库流水（目标）。 */
    data class TransferResult(
        val outMovement: TursoLedgerClient.MovementRow,
        val inMovement: TursoLedgerClient.MovementRow,
    )

    /**
     * 托盘间部分移动：同一物料从源（库位，托盘）移 quantity 件到目标（库位，托盘）。
     * 单事务：源行减数（0 也保留行不删）、目标行加数（无行新建）、流水一出一入。
     * 校验失败抛 IllegalArgumentException，文案可直接展示给用户。
     */
    fun transferBetweenTrays(
        context: Context,
        materialCode: String,
        quantity: Int,
        sourceLocation: String,
        sourceTray: String,
        targetLocation: String,
        targetTray: String,
    ): TransferResult {
        val material = materialCode.trim()
        val srcLoc = sourceLocation.trim()
        val srcTray = sourceTray.trim()
        val dstLoc = targetLocation.trim()
        val dstTray = targetTray.trim()
        if (material.isEmpty()) throw IllegalArgumentException("请填写物料编码")
        if (quantity <= 0) throw IllegalArgumentException("数量必须大于 0")
        if (srcLoc.isEmpty() || srcTray.isEmpty()) throw IllegalArgumentException("请先查库存确定源库位与源托盘")
        if (dstLoc.isEmpty() || dstTray.isEmpty()) throw IllegalArgumentException("请填写目标库位与目标托盘")
        if (srcLoc == dstLoc && srcTray == dstTray) throw IllegalArgumentException("目标与源相同，无需移动")
        val db = LocalDatabase.get(context).writableDatabase
        db.beginTransaction()
        try {
            var srcBalance: Int? = null
            db.rawQuery(
                "SELECT balance FROM ledger_stock WHERE material_code = ? AND location = ? AND tray_code = ?",
                arrayOf(material, srcLoc, srcTray),
            ).use { c ->
                if (c.moveToFirst()) srcBalance = c.getInt(0)
            }
            val src = srcBalance ?: throw IllegalArgumentException("源托盘无该物料库存，无法移动")
            if (quantity > src) throw IllegalArgumentException("源结存不足，当前结存 $src")
            var dstBalance = 0
            db.rawQuery(
                "SELECT balance FROM ledger_stock WHERE material_code = ? AND location = ? AND tray_code = ?",
                arrayOf(material, dstLoc, dstTray),
            ).use { c ->
                if (c.moveToFirst()) dstBalance = c.getInt(0)
            }
            val now = System.currentTimeMillis()
            val srcNew = src - quantity
            val dstNew = dstBalance + quantity
            val srcValues = ContentValues().apply {
                put("balance", srcNew)
                put("updated_at", now)
                put("dirty", 1)
            }
            db.update(
                "ledger_stock", srcValues,
                "material_code = ? AND location = ? AND tray_code = ?",
                arrayOf(material, srcLoc, srcTray),
            )
            val dstValues = ContentValues().apply {
                put("material_code", material)
                put("location", dstLoc)
                put("tray_code", dstTray)
                put("balance", dstNew)
                put("updated_at", now)
                put("dirty", 1)
            }
            db.insertWithOnConflict(
                "ledger_stock", null, dstValues, SQLiteDatabase.CONFLICT_REPLACE,
            )
            val outId = UUID.randomUUID().toString()
            val inId = UUID.randomUUID().toString()
            val outValues = ContentValues().apply {
                put("client_id", outId)
                put("move_type", TYPE_OUT)
                put("material_code", material)
                put("quantity", quantity)
                put("location", srcLoc)
                put("tray_code", srcTray)
                put("balance_after", srcNew)
                put("created_at", now)
                put("dirty", 1)
            }
            db.insertOrThrow("ledger_movements", null, outValues)
            val inValues = ContentValues().apply {
                put("client_id", inId)
                put("move_type", TYPE_IN)
                put("material_code", material)
                put("quantity", quantity)
                put("location", dstLoc)
                put("tray_code", dstTray)
                put("balance_after", dstNew)
                put("created_at", now)
                put("dirty", 1)
            }
            db.insertOrThrow("ledger_movements", null, inValues)
            db.setTransactionSuccessful()
            return TransferResult(
                TursoLedgerClient.MovementRow(outId, TYPE_OUT, material, quantity, srcLoc, srcTray, srcNew, now),
                TursoLedgerClient.MovementRow(inId, TYPE_IN, material, quantity, dstLoc, dstTray, dstNew, now),
            )
        } finally {
            db.endTransaction()
        }
    }

    /** 整托移库结果：实际移动的物料种数与总件数。 */
    data class WholeTrayResult(
        val movedKinds: Int,
        val movedTotal: Int,
    )

    /**
     * 整托移库：源托盘全部库存行换到目标库位（托盘号不变）。
     * 单事务逐行处理：目标行存在则余额相加并删除源行；不存在则 UPDATE 源行库位。
     * 已在目标库位的行跳过；托盘无行或全部行已在目标库位时抛 IllegalArgumentException。
     * 每种物料记一出一入两条流水（出库 balance_after=0），同时间戳、各自 client_id。
     */
    fun moveWholeTray(
        context: Context,
        sourceTray: String,
        targetLocation: String,
    ): WholeTrayResult {
        val tray = sourceTray.trim()
        val dstLoc = targetLocation.trim()
        if (tray.isEmpty()) throw IllegalArgumentException("请填写源托盘")
        if (dstLoc.isEmpty()) throw IllegalArgumentException("请填写目标库位")
        val db = LocalDatabase.get(context).writableDatabase
        db.beginTransaction()
        try {
            val rows = mutableListOf<TursoLedgerClient.StockRow>()
            db.rawQuery(
                "SELECT material_code, location, tray_code, balance, updated_at FROM ledger_stock " +
                    "WHERE tray_code = ? ORDER BY material_code",
                arrayOf(tray),
            ).use { c ->
                while (c.moveToNext()) {
                    rows += TursoLedgerClient.StockRow(
                        c.getString(0), c.getString(1), c.getString(2),
                        c.getInt(3), c.getLong(4),
                    )
                }
            }
            if (rows.isEmpty()) throw IllegalArgumentException("该托盘暂无库存")
            // 已在目标库位的行跳过（目标行就是它自己，合并逻辑会把余额翻倍，必须排除）。
            val toMove = rows.filter { it.location != dstLoc }
            if (toMove.isEmpty()) throw IllegalArgumentException("已经在该库位")
            val now = System.currentTimeMillis()
            var total = 0
            for (row in toMove) {
                var dstBalance: Int? = null
                db.rawQuery(
                    "SELECT balance FROM ledger_stock WHERE material_code = ? AND location = ? AND tray_code = ?",
                    arrayOf(row.materialCode, dstLoc, tray),
                ).use { c ->
                    if (c.moveToFirst()) dstBalance = c.getInt(0)
                }
                val dstNew = (dstBalance ?: 0) + row.balance
                if (dstBalance != null) {
                    val dstValues = ContentValues().apply {
                        put("balance", dstNew)
                        put("updated_at", now)
                        put("dirty", 1)
                    }
                    db.update(
                        "ledger_stock", dstValues,
                        "material_code = ? AND location = ? AND tray_code = ?",
                        arrayOf(row.materialCode, dstLoc, tray),
                    )
                    db.delete(
                        "ledger_stock",
                        "material_code = ? AND location = ? AND tray_code = ?",
                        arrayOf(row.materialCode, row.location, tray),
                    )
                } else {
                    val srcValues = ContentValues().apply {
                        put("location", dstLoc)
                        put("updated_at", now)
                        put("dirty", 1)
                    }
                    db.update(
                        "ledger_stock", srcValues,
                        "material_code = ? AND location = ? AND tray_code = ?",
                        arrayOf(row.materialCode, row.location, tray),
                    )
                }
                val outValues = ContentValues().apply {
                    put("client_id", UUID.randomUUID().toString())
                    put("move_type", TYPE_OUT)
                    put("material_code", row.materialCode)
                    put("quantity", row.balance)
                    put("location", row.location)
                    put("tray_code", tray)
                    put("balance_after", 0)
                    put("created_at", now)
                    put("dirty", 1)
                }
                db.insertOrThrow("ledger_movements", null, outValues)
                val inValues = ContentValues().apply {
                    put("client_id", UUID.randomUUID().toString())
                    put("move_type", TYPE_IN)
                    put("material_code", row.materialCode)
                    put("quantity", row.balance)
                    put("location", dstLoc)
                    put("tray_code", tray)
                    put("balance_after", dstNew)
                    put("created_at", now)
                    put("dirty", 1)
                }
                db.insertOrThrow("ledger_movements", null, inValues)
                total += row.balance
            }
            db.setTransactionSuccessful()
            return WholeTrayResult(toMove.size, total)
        } finally {
            db.endTransaction()
        }
    }

    // ---------------- 扫码解析链路 ----------------

    /**
     * 解析一个扫到的码：托盘码 / 69 码 / 物料编码 / SAP 集成码。
     * 69 码先走本地映射，本地没有再远程反查（复用 Barcode69Lookup + Turso69Client），
     * 查到的映射顺手学进本地表；库存先查本地，本地没有且联网时到 Turso 补一次并镜像回本地。
     */
    fun resolveScanned(context: Context, rawCode: String): Resolved {
        val code = rawCode.trim()
        if (code.isEmpty()) return Resolved("", "", emptyList(), "扫到的码是空的")
        // SAP 集成码（含 ||）：取第 3 段物料编码
        if (code.contains("||")) {
            val parsed = LabelParser.parseSapCode(code)
            val mat = LabelParser.normalizeMaterial(parsed.materialCode)
            if (mat.isBlank()) return Resolved("", "", emptyList(), "集成码里没有物料编码")
            val stocks = findLocalStocksByMaterial(context, mat)
            return Resolved(mat, "", stocks, "集成码解析得到物料编码")
        }
        // 托盘码：TP + 数字（沿用扫码页 wantField=tray 的排序口径）
        if (code.startsWith("TP", ignoreCase = true)) {
            val local = findLocalStocksByTray(context, code)
            val stocks = if (local.isEmpty()) findRemoteStocksByTray(context, code) else local
            return Resolved(
                "", code, stocks,
                if (stocks.isEmpty()) "该托盘在台账中暂无库存" else "托盘扫码命中 ${stocks.size} 条库存",
            )
        }
        val classified = LabelParser.classify(code)
        val kind = classified?.first
        val value = classified?.second ?: code
        var material = ""
        var note = ""
        when (kind) {
            "ean" -> {
                val local = try {
                    Barcode69Lookup(context).lookup(value)
                } catch (_: Exception) {
                    null
                }
                if (local != null) {
                    material = LabelParser.normalizeMaterial(local)
                    note = "69 码本地反查得到物料编码"
                } else {
                    val url = SettingsActivity.getUrl(context)
                    val token = SettingsActivity.getToken(context)
                    val hit = if (url.isNotEmpty() && token.isNotEmpty()) {
                        try {
                            Turso69Client.lookup(value, url, token)
                        } catch (_: Exception) {
                            null
                        }
                    } else {
                        null
                    }
                    if (hit != null) {
                        material = LabelParser.normalizeMaterial(hit.first)
                        try {
                            Barcode69Lookup(context).learn(value, hit.first)
                        } catch (_: Exception) {
                        }
                        note = "69 码远程反查得到物料编码"
                    } else {
                        return Resolved("", "", emptyList(), "69 码在映射库中查不到，请手动填物料编码")
                    }
                }
            }
            "material10", "material12" -> {
                material = LabelParser.normalizeMaterial(value)
                note = "物料编码"
            }
            else -> {
                return Resolved("", "", emptyList(), "扫到的不是物料编码/69 码/托盘号，请手动输入")
            }
        }
        if (material.isEmpty()) return Resolved("", "", emptyList(), "未能解析出物料编码")
        val localStocks = findLocalStocksByMaterial(context, material)
        val stocks = if (localStocks.isEmpty()) findRemoteStocksByMaterial(context, material) else localStocks
        return Resolved(
            material, "", stocks,
            if (stocks.isEmpty()) "台账中暂无该物料库存（入库将新建）" else note,
        )
    }

    /** 远程按物料查库存并镜像回本地（仅当本地该物料无任何行时调用，不覆盖脏数据）。 */
    private fun findRemoteStocksByMaterial(context: Context, material: String): List<TursoLedgerClient.StockRow> {
        val url = SettingsActivity.getUrl(context)
        val token = SettingsActivity.getToken(context)
        if (url.isEmpty() || token.isEmpty()) return emptyList()
        val (rows, err) = try {
            TursoLedgerClient.lookupStock(url, token, material)
        } catch (_: Exception) {
            return emptyList()
        }
        if (err != null) return emptyList()
        if (rows.isNotEmpty()) mirrorStocks(context, rows)
        return rows
    }

    /** 远程按托盘查库存并镜像回本地（仅当本地该托盘无任何行时调用）。 */
    private fun findRemoteStocksByTray(context: Context, tray: String): List<TursoLedgerClient.StockRow> {
        val url = SettingsActivity.getUrl(context)
        val token = SettingsActivity.getToken(context)
        if (url.isEmpty() || token.isEmpty()) return emptyList()
        val (rows, err) = try {
            TursoLedgerClient.lookupStockByTray(url, token, tray)
        } catch (_: Exception) {
            return emptyList()
        }
        if (err != null) return emptyList()
        if (rows.isNotEmpty()) mirrorStocks(context, rows)
        return rows
    }

    /** 把远程库存行写进本地（dirty=0：它们本来就来自远程，不用再推）。 */
    private fun mirrorStocks(context: Context, rows: List<TursoLedgerClient.StockRow>) {
        val db = LocalDatabase.get(context).writableDatabase
        db.beginTransaction()
        try {
            for (row in rows) {
                val values = ContentValues().apply {
                    put("material_code", row.materialCode)
                    put("location", row.location)
                    put("tray_code", row.trayCode)
                    put("balance", row.balance)
                    put("updated_at", row.updatedAt)
                    put("dirty", 0)
                }
                db.insertWithOnConflict(
                    "ledger_stock", null, values, SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ---------------- 同步 ----------------

    /**
     * 登记后推单条增量（刚登记的那行库存 + 那条流水）。成功返回 null；
     * 失败返回错误信息，脏标记保留，稍后全量同步再推。
     */
    fun pushDelta(
        context: Context,
        stock: TursoLedgerClient.StockRow,
        movement: TursoLedgerClient.MovementRow,
    ): String? {
        val url = SettingsActivity.getUrl(context)
        val token = SettingsActivity.getToken(context)
        if (url.isEmpty() || token.isEmpty()) return "未配置远程数据库"
        TursoLedgerClient.ensureTables(url, token)?.let { return it }
        TursoLedgerClient.upsertStock(url, token, stock)?.let { return it }
        TursoLedgerClient.insertMovement(url, token, movement)?.let { return it }
        val db = LocalDatabase.get(context).writableDatabase
        val stockValues = ContentValues().apply { put("dirty", 0) }
        db.update(
            "ledger_stock", stockValues,
            "material_code = ? AND location = ? AND tray_code = ?",
            arrayOf(stock.materialCode, stock.location, stock.trayCode),
        )
        val mvValues = ContentValues().apply { put("dirty", 0) }
        db.update(
            "ledger_movements", mvValues, "client_id = ?", arrayOf(movement.clientId),
        )
        return null
    }

    /**
     * 全量同步：先推脏数据，再拉远程。顺序不能反 —— 先拉会把未推的本地改动覆盖掉。
     * 成功返回 null，失败返回错误信息（本地数据不受影响，脏标记保留）。
     */
    fun syncAll(context: Context): String? {
        val url = SettingsActivity.getUrl(context)
        val token = SettingsActivity.getToken(context)
        if (url.isEmpty() || token.isEmpty()) return "未配置远程数据库"
        TursoLedgerClient.ensureTables(url, token)?.let { return it }
        val db = LocalDatabase.get(context).writableDatabase
        // 推：脏库存
        val dirtyStocks = mutableListOf<TursoLedgerClient.StockRow>()
        db.rawQuery(
            "SELECT material_code, location, tray_code, balance, updated_at FROM ledger_stock WHERE dirty = 1",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                dirtyStocks += TursoLedgerClient.StockRow(
                    c.getString(0), c.getString(1), c.getString(2),
                    c.getInt(3), c.getLong(4),
                )
            }
        }
        for (row in dirtyStocks) {
            TursoLedgerClient.upsertStock(url, token, row)?.let { return it }
            val values = ContentValues().apply { put("dirty", 0) }
            db.update(
                "ledger_stock", values,
                "material_code = ? AND location = ? AND tray_code = ?",
                arrayOf(row.materialCode, row.location, row.trayCode),
            )
        }
        // 推：脏流水
        val dirtyMovements = mutableListOf<TursoLedgerClient.MovementRow>()
        db.rawQuery(
            "SELECT client_id, move_type, material_code, quantity, location, tray_code, balance_after, created_at " +
                "FROM ledger_movements WHERE dirty = 1",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                dirtyMovements += TursoLedgerClient.MovementRow(
                    c.getString(0), c.getString(1), c.getString(2), c.getInt(3),
                    c.getString(4), c.getString(5), c.getInt(6), c.getLong(7),
                )
            }
        }
        for (mv in dirtyMovements) {
            TursoLedgerClient.insertMovement(url, token, mv)?.let { return it }
            val values = ContentValues().apply { put("dirty", 0) }
            db.update("ledger_movements", values, "client_id = ?", arrayOf(mv.clientId))
        }
        // 拉：远程库存 —— 只写本地没有或不脏的行，脏行已在上面推过（推成功才清脏），
        // 推失败会提前返回，所以走到这里的脏行不存在，拉取不会丢本地改动。
        val (remoteStocks, stockErr) = TursoLedgerClient.listStock(url, token)
        if (stockErr != null) return stockErr
        db.beginTransaction()
        try {
            for (row in remoteStocks) {
                var dirty = false
                var exists = false
                db.rawQuery(
                    "SELECT dirty FROM ledger_stock WHERE material_code = ? AND location = ? AND tray_code = ?",
                    arrayOf(row.materialCode, row.location, row.trayCode),
                ).use { c ->
                    if (c.moveToFirst()) {
                        exists = true
                        dirty = c.getInt(0) != 0
                    }
                }
                if (exists && dirty) continue
                val values = ContentValues().apply {
                    put("material_code", row.materialCode)
                    put("location", row.location)
                    put("tray_code", row.trayCode)
                    put("balance", row.balance)
                    put("updated_at", row.updatedAt)
                    put("dirty", 0)
                }
                db.insertWithOnConflict(
                    "ledger_stock", null, values, SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        // 拉：远程流水 —— 按 client_id 去重，已有的不动。
        val (remoteMovements, mvErr) = TursoLedgerClient.listMovements(url, token)
        if (mvErr != null) return mvErr
        db.beginTransaction()
        try {
            for (mv in remoteMovements) {
                val values = ContentValues().apply {
                    put("client_id", mv.clientId)
                    put("move_type", mv.moveType)
                    put("material_code", mv.materialCode)
                    put("quantity", mv.quantity)
                    put("location", mv.location)
                    put("tray_code", mv.trayCode)
                    put("balance_after", mv.balanceAfter)
                    put("created_at", mv.createdAt)
                    put("dirty", 0)
                }
                db.insertWithOnConflict(
                    "ledger_movements", null, values, SQLiteDatabase.CONFLICT_IGNORE,
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return null
    }
}
