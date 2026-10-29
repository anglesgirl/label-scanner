package com.anglesgirl.labelscanner.util

import android.content.Context

/**
 * 当前库位（跨界面共享）。
 * 登记时填一次，整批沿用；换库位时手动改一次即可（与 TrayPrefs 同款做法）。
 */
object LocationPrefs {

    private const val PREFS = "labelscanner_prefs"
    private const val KEY = "current_location"

    fun get(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "").orEmpty()

    fun set(context: Context, code: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, code).apply()
    }
}
