package com.anglesgirl.labelscanner.util

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局崩溃捕获：把未捕获异常堆栈追加写入 filesDir/crash_log.txt，
 * 供下次启动时弹窗展示（用户可直接复制发回排查，无需连接电脑 logcat）。
 */
object CrashHandler {

    const val LOG_FILE = "crash_log.txt"

    fun install(context: Context) {
        val default = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                val entry = "===== $stamp =====\n${sw}\n"
                File(context.filesDir, LOG_FILE).appendText(entry)
            }
            default?.uncaughtException(thread, throwable)
        }
    }

    /** 读取已记录的崩溃日志（无则返回 null） */
    fun read(context: Context): String? =
        File(context.filesDir, LOG_FILE).takeIf { it.exists() && it.length() > 0 }?.readText()

    /** 清空崩溃日志 */
    fun clear(context: Context) {
        File(context.filesDir, LOG_FILE).takeIf { it.exists() }?.delete()
    }
}
