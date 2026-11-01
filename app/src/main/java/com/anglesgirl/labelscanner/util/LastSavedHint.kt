package com.anglesgirl.labelscanner.util

import android.app.Activity
import android.widget.TextView
import com.anglesgirl.labelscanner.data.DbExecutor
import com.anglesgirl.labelscanner.data.RecordStore

/** 异步显示最近一次成功入库的序列号。 */
object LastSavedHint {
    fun bind(activity: Activity, view: TextView, prefix: String = "上次保存序列号：") =
        refresh(activity, view, prefix)

    /** 成功 append 后调用；独立标题的紧凑布局可传空前缀。 */
    fun refresh(activity: Activity, view: TextView, prefix: String = "上次保存序列号：") {
        view.text = "${prefix}读取中…"
        DbExecutor.run({ RecordStore.getLastSavedSerialNumber(activity) }) { result ->
            if (activity.isFinishing || (android.os.Build.VERSION.SDK_INT >= 17 && activity.isDestroyed)) return@run
            if (result.isFailure) {
                view.text = "${prefix}读取失败"
                return@run
            }
            view.text = result.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { "$prefix$it" }
                ?: "${prefix}暂无"
        }
    }
}
