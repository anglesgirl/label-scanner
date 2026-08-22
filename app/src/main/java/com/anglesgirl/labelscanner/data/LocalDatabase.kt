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
                box_code TEXT NOT NULL DEFAULT ''
            )"""
        )
        db.execSQL("CREATE UNIQUE INDEX records_serial_index ON records(serial_number) WHERE serial_number <> ''")
        db.execSQL(
            """CREATE TABLE barcode69_lookup (
                ean69 TEXT PRIMARY KEY NOT NULL,
                material_code TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 is the first database release; future schema changes belong here.
    }

    companion object {
        private const val DB_NAME = "label_scanner.db"
        private const val DB_VERSION = 1

        @Volatile private var instance: LocalDatabase? = null

        fun get(context: Context): LocalDatabase =
            instance ?: synchronized(this) {
                instance ?: LocalDatabase(context).also { instance = it }
            }
    }
}