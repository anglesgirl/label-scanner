package com.anglesgirl.labelscanner.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** 本地业务数据库：扫描记录、69 码映射和台账镜像共用一个事务数据库。 */
class LocalDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE records (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                barcodes TEXT NOT NULL DEFAULT '',
                ocr_text TEXT NOT NULL DEFAULT '',
                supplier TEXT NOT NULL DEFAULT 'NA',
                serial_number TEXT NOT NULL DEFAULT '',
                material_code TEXT NOT NULL DEFAULT '',
                quantity INTEGER NOT NULL DEFAULT 1,
                production_date TEXT NOT NULL DEFAULT '',
                ean69 TEXT NOT NULL DEFAULT '',
                material_from_ean69 INTEGER NOT NULL DEFAULT 0,
                model TEXT NOT NULL DEFAULT '',
                color TEXT NOT NULL DEFAULT '',
                toner_model TEXT NOT NULL DEFAULT '',
                tray_code TEXT NOT NULL DEFAULT '',
                box_code TEXT NOT NULL DEFAULT '',
                slip_no TEXT NOT NULL DEFAULT '',
                location TEXT NOT NULL DEFAULT ''
            )"""
        )
        db.execSQL("CREATE UNIQUE INDEX records_serial_index ON records(serial_number) WHERE serial_number <> ''")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_records_tray ON records(tray_code)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_records_slip ON records(slip_no)")
        db.execSQL(
            """CREATE TABLE barcode69_lookup (
                ean69 TEXT PRIMARY KEY NOT NULL,
                material_code TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )"""
        )
        createAppMetadataTable(db)
        createLedgerTables(db)
        createSlipTable(db)
        createSlipFilesTable(db)
    }

    /** 单据表：送货单/退货单照片 + 编号；导出 ZIP 时按单号归集托盘。 */
    private fun createSlipTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS slips (
                slip_no TEXT PRIMARY KEY NOT NULL,
                photo_path TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL DEFAULT 0
            )"""
        )
    }

    /** 单据附件表：一张单据可附多个单独文件（表格/图片等），导出 ZIP 时一并打包。 */
    private fun createSlipFilesTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS slip_files (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                slip_no TEXT NOT NULL,
                file_path TEXT NOT NULL,
                file_name TEXT NOT NULL,
                created_at INTEGER NOT NULL DEFAULT 0
            )"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_slip_files_slip ON slip_files(slip_no)")
    }

    /** v3 台账本地镜像：库存按（物料、库位、托盘）定位，流水按 client_id 去重。 */
    private fun createLedgerTables(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS ledger_stock (
                material_code TEXT NOT NULL,
                location TEXT NOT NULL,
                tray_code TEXT NOT NULL,
                balance INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL DEFAULT 0,
                dirty INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (material_code, location, tray_code)
            )"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS ledger_movements (
                client_id TEXT PRIMARY KEY NOT NULL,
                move_type TEXT NOT NULL,
                material_code TEXT NOT NULL,
                quantity INTEGER NOT NULL,
                location TEXT NOT NULL,
                tray_code TEXT NOT NULL,
                balance_after INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                dirty INTEGER NOT NULL DEFAULT 0
            )"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_ledger_stock_material ON ledger_stock(material_code)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_ledger_movements_material ON ledger_movements(material_code)")
    }

    private fun createAppMetadataTable(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS app_metadata (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_records_tray ON records(tray_code)")
        }
        if (oldVersion < 3) {
            // v3：老库补齐库位列及台账表；已存在记录和同步流水不动。
            if (!hasColumn(db, "records", "location")) {
                db.execSQL("ALTER TABLE records ADD COLUMN location TEXT NOT NULL DEFAULT ''")
            }
            createLedgerTables(db)
            createAppMetadataTable(db)
        }
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        if (db.isReadOnly) return
        // 自愈旧 v3 数据库：缺少哪张新增表/字段就补齐，已存在数据不受影响。
        createLedgerTables(db)
        createAppMetadataTable(db)
        createSlipTable(db)
        createSlipFilesTable(db)
        if (!hasColumn(db, "records", "location")) {
            db.execSQL("ALTER TABLE records ADD COLUMN location TEXT NOT NULL DEFAULT ''")
        }
        if (!hasColumn(db, "records", "slip_no")) {
            db.execSQL("ALTER TABLE records ADD COLUMN slip_no TEXT NOT NULL DEFAULT ''")
        }
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_records_slip ON records(slip_no)")
    }

    private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean {
        db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == column) return true
            }
        }
        return false
    }

    companion object {
        private const val DB_NAME = "label_scanner.db"
        private const val DB_VERSION = 3

        @Volatile private var instance: LocalDatabase? = null

        fun get(context: Context): LocalDatabase =
            instance ?: synchronized(this) {
                instance ?: LocalDatabase(context).also { instance = it }
            }
    }
}
