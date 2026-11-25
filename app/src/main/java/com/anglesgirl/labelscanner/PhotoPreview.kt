package com.anglesgirl.labelscanner

import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/**
 * 全屏看图（共用）：双指缩放、单指拖动、双击放大/还原。
 * actions：底部按钮（文字 → 点后执行的动作，动作先执行、弹窗随后关）。
 * onDismiss：弹窗关掉后调用。
 * 黑底、不盖状态栏/导航栏，按钮不会被手势条挡住。
 * 供 WMS 导入附加单据、拍单据等“先看清再确认”的取图流程共用。
 */
object PhotoPreview {
    fun showDialog(
        activity: AppCompatActivity,
        file: File,
        actions: List<Pair<String, (() -> Unit)?>>,
        onDismiss: (() -> Unit)? = null,
    ) {
        val bmp = decodeSampledBitmap(file, 2048)
        if (bmp == null) {
            Toast.makeText(activity, "图片读取失败", Toast.LENGTH_SHORT).show()
            onDismiss?.invoke()
            return
        }
        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        )
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        val imageView = ZoomableImageView(activity).apply {
            setImageBitmap(bmp)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
            )
        }
        root.addView(imageView)
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            val pad = (16 * activity.resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        for ((label, action) in actions) {
            val btn = Button(activity).apply {
                text = label
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    action?.invoke()
                    dialog.dismiss()
                }
            }
            bar.addView(btn)
        }
        root.addView(bar)
        dialog.setContentView(root)
        dialog.setOnDismissListener { bmp.recycle(); onDismiss?.invoke() }
        dialog.show()
    }

    /** 按最长边上限解码图片（防大图 OOM），解不出返回 null。 */
    fun decodeSampledBitmap(file: File, maxDim: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= 0 || h <= 0) return null
            var sample = 1
            while (maxOf(w, h) / sample > maxDim) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(file.absolutePath, opts)
        } catch (_: Exception) {
            null
        }
    }
}
