package com.anglesgirl.labelscanner.util

import android.content.Context

/**
 * 当前采集托盘号（跨界面共享）。
 * 采集开始时填一次，整批沿用；换托盘时手动改一次即可。
 */
object TrayPrefs {

    private const val PREFS = "labelscanner_prefs"
    private const val KEY = "current_tray_code"

    fun get(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "").orEmpty()

    fun set(context: Context, code: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, code).apply()
    }
}
