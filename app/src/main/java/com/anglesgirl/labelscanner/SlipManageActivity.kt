package com.anglesgirl.labelscanner

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.anglesgirl.labelscanner.data.SlipStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 单据管理：全部单据列表；点一条看详情（单号、照片、OCR 字段、附件），
 * 附件能用系统应用打开（PDF / 图片 / 表格），能删附件、删整单、设为当前单据。
 * 单据本身不分类型（送货单/退货单都一样管），按时间倒序排。
 */
class SlipManageActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private var slips: List<SlipStore.Slip> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        root.addView(TextView(this).apply {
            text = "单据管理"
            textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "点一条看详情：照片、附件都能打开；附件、整单都能删"
            textSize = 13f
            setPadding(0, dp(4), 0, dp(12))
        })
        listView = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        val empty = TextView(this).apply {
            text = "还没有单据，先去首页添加"
            textSize = 15f
            setPadding(0, dp(24), 0, 0)
        }
        root.addView(empty)
        root.addView(listView)
        listView.emptyView = empty
        setContentView(root)

        listView.onItemClickListener = android.widget.AdapterView.OnItemClickListener { _, _, pos, _ ->
            slips.getOrNull(pos)?.let { showDetail(it.slipNo) }
        }
        refreshList()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun fmtDate(ts: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ts))

    private fun refreshList() {
        slips = SlipStore.listAllSlips(this)
        val current = SlipStore.getCurrent(this)
        val adapter = object : ArrayAdapter<SlipStore.Slip>(
            this, android.R.layout.simple_list_item_2, android.R.id.text1, slips
        ) {
            override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(pos, convertView, parent)
                val s = getItem(pos) ?: return v
                val files = SlipStore.getFiles(context, s.slipNo).size
                v.findViewById<TextView>(android.R.id.text1).text =
                    s.slipNo + if (s.slipNo == current) " · 当前" else ""
                v.findViewById<TextView>(android.R.id.text2).text =
                    "${fmtDate(s.createdAt)} · 数量 ${s.quantity} · 箱数 ${s.boxCount}" +
                        (if (s.materialCode.isNotBlank()) " · ${s.materialCode}" else "") +
                        (if (files > 0) " · 附件 $files 个" else "")
                return v
            }
        }
        listView.adapter = adapter
    }

    /** 详情弹窗：照片 + 字段 + 附件（打开/删除），能设为当前、能删整单。 */
    private fun showDetail(slipNo: String) {
        val slip = SlipStore.getSlip(this, slipNo) ?: run {
            Toast.makeText(this, "这张单据找不到了", Toast.LENGTH_SHORT).show()
            refreshList()
            return
        }
        val files = SlipStore.getFiles(this, slipNo)
        var detailDialog: AlertDialog? = null
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val photoFile = File(slip.photoPath)
        if (photoFile.exists()) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(photoFile.absolutePath, bounds)
            var sample = 1
            val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
            while (maxSide / sample > 1024) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = BitmapFactory.decodeFile(photoFile.absolutePath, opts)
            if (bmp != null) {
                layout.addView(ImageView(this).apply {
                    setImageBitmap(bmp)
                    adjustViewBounds = true
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                })
            }
        } else {
            layout.addView(TextView(this).apply { text = "照片文件已丢失" })
        }
        fun info(label: String, value: String) {
            layout.addView(TextView(this).apply {
                text = "$label：$value"
                textSize = 15f
                setPadding(0, dp(4), 0, 0)
            })
        }
        info("单据编号", slip.slipNo)
        info("创建时间", fmtDate(slip.createdAt))
        info("数量", slip.quantity.toString())
        info("箱数", slip.boxCount.toString())
        info("物料编码", slip.materialCode.ifBlank { "—" })
        layout.addView(TextView(this).apply {
            text = "附件（${files.size} 个）"
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, dp(12), 0, dp(4))
        })
        if (files.isEmpty()) {
            layout.addView(TextView(this).apply { text = "无附件" })
        }
        for (f in files) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(4), 0, dp(4))
            }
            row.addView(TextView(this).apply {
                text = f.fileName
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(Button(this).apply {
                text = "打开"
                setOnClickListener { openFile(File(f.filePath), f.fileName) }
            })
            row.addView(Button(this).apply {
                text = "删除"
                setOnClickListener {
                    AlertDialog.Builder(this@SlipManageActivity)
                        .setTitle("删除附件")
                        .setMessage("确定删除 ${f.fileName} 吗？文件一起删掉。")
                        .setPositiveButton("删除") { _, _ ->
                            SlipStore.deleteFile(this@SlipManageActivity, f.id)
                            Toast.makeText(this@SlipManageActivity, "附件已删除", Toast.LENGTH_SHORT).show()
                            detailDialog?.dismiss()
                            showDetail(slipNo)
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            })
            layout.addView(row)
        }
        val scroll = ScrollView(this).apply { addView(layout) }
        detailDialog = AlertDialog.Builder(this)
            .setTitle("单据 ${slip.slipNo}")
            .setView(scroll)
            .setPositiveButton("设为当前") { _, _ ->
                SlipStore.setCurrent(this, slipNo)
                Toast.makeText(this, "已设为当前单据：$slipNo", Toast.LENGTH_SHORT).show()
                refreshList()
            }
            .setNeutralButton("删除单据") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("删除单据")
                    .setMessage("确定删除单据 $slipNo 吗？照片和附件一起删掉，采集记录不受影响。")
                    .setPositiveButton("删除") { _, _ ->
                        SlipStore.deleteSlip(this, slipNo)
                        Toast.makeText(this, "单据已删除", Toast.LENGTH_SHORT).show()
                        refreshList()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNegativeButton("关闭", null)
            .create()
        detailDialog?.show()
    }

    /** 用系统应用打开附件：PDF、图片、表格都行；没有能打开的应用就提示。 */
    private fun openFile(file: File, displayName: String) {
        if (!file.exists()) {
            Toast.makeText(this, "文件找不到了：$displayName", Toast.LENGTH_SHORT).show()
            return
        }
        val mime = when (file.extension.lowercase(Locale.ROOT)) {
            "pdf" -> "application/pdf"
            "jpg", "jpeg", "png", "webp" -> "image/*"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "csv" -> "text/csv"
            "txt" -> "text/plain"
            else -> "*/*"
        }
        val uri: Uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "没有能打开这个文件的应用", Toast.LENGTH_SHORT).show()
        }
    }
}
