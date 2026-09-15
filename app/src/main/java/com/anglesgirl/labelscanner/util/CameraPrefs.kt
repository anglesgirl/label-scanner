package com.anglesgirl.labelscanner.util

import android.content.Context

/**
 * 摄像头来源设置（设置页可切换）。
 *
 * 内窥镜 = 普通摄像头源：所有扫码入口（拆码/补扫/入库）统一按此设置
 * 选择取景源 —— phone 用手机摄像头（CameraX），usb 用外接 UVC 摄像头。
 */
object CameraPrefs {

    private const val PREFS = "labelscanner_prefs"
    private const val KEY = "camera_source"

    const val SOURCE_PHONE = "phone"
    const val SOURCE_USB = "usb"

    /** 当前摄像头来源，默认手机摄像头。 */
    fun source(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, SOURCE_PHONE) ?: SOURCE_PHONE

    fun isUsb(context: Context): Boolean = source(context) == SOURCE_USB

    fun set(context: Context, source: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, source).apply()
    }
}
