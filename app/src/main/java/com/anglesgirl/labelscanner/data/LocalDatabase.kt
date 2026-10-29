package com.anglesgirl.labelscanner.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** 本地业务数据库：记录和 69 码映射共用一个事务数据库。 */
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
                location TEXT NOT NULL DEFAULT ''
            )"""
        )
        db.execSQL("CREATE UNIQUE INDEX records_serial_index ON records(serial_number) WHERE serial_number <> ''")
        // v2：托盘号索引 —— 按托盘查询/计数走索引，数据量大时不回退全表扫描
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_records_tray ON records(tray_code)")
        db.execSQL(
            """CREATE TABLE barcode69_lookup (
                ean69 TEXT PRIMARY KEY NOT NULL,
                material_code TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )"""
        )
        createLedgerTables(db)
    }

    /**
     * 台账本地镜像表（v3 新增）：库存按（物料编码，库位，托盘号）主键，
     * 流水按 client_id 去重；dirty=1 表示还没推到远程 Turso，同步成功后清零。
     * 台账从空开始：这里只建空表，不把现有 records 导入成库存。
     */
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

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_records_tray ON records(tray_code)")
        }
        if (oldVersion < 3) {
            // v3：records 加库位列（新装直接建在表里，老库 ALTER 补列，默认空串不影响旧数据）
            db.execSQL("ALTER TABLE records ADD COLUMN location TEXT NOT NULL DEFAULT ''")
            createLedgerTables(db)
        }
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