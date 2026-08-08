package com.anglesgirl.labelscanner.data

import android.content.Context
import com.anglesgirl.labelscanner.model.LabelResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 本地数据存储：已保存的标签记录持久化到 App 内部文件（JSON）。
 *
 * 目的：保存的数据不随 App 关闭丢失；支持列表查看/编辑/删除后再导出。
 * 存储位置：context.filesDir/label_records.json（App 卸载才清除）。
 */
object RecordStore {

    private const val FILE_NAME = "label_records.json"

    /** 读取全部记录（按保存顺序） */
    fun load(context: Context): MutableList<LabelResult> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(file.readText())
            val list = mutableListOf<LabelResult>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    LabelResult(
                        barcodes = o.optString("barcodes", "").split("\n").filter { it.isNotBlank() },
                        ocrText = o.optString("ocrText", ""),
                        supplier = o.optString("supplier", "NA"),
                        serialNumber = o.optString("serialNumber", ""),
                        materialCode = o.optString("materialCode", ""),
                        quantity = o.optInt("quantity", 1),
                        productionDate = o.optString("productionDate", ""),
                        ean69 = o.optString("ean69", ""),
                        model = o.optString("model", ""),
                        color = o.optString("color", ""),
                        tonerModel = o.optString("tonerModel", ""),
                    )
                )
            }
            list
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    /** 全量保存记录 */
    fun save(context: Context, records: List<LabelResult>) {
        val arr = JSONArray()
        for (r in records) {
            arr.put(
                JSONObject()
                    .put("barcodes", r.barcodes.joinToString("\n"))
                    .put("ocrText", r.ocrText)
                    .put("supplier", r.supplier)
                    .put("serialNumber", r.serialNumber)
                    .put("materialCode", r.materialCode)
                    .put("quantity", r.quantity)
                    .put("productionDate", r.productionDate)
                    .put("ean69", r.ean69)
                    .put("model", r.model)
                    .put("color", r.color)
                    .put("tonerModel", r.tonerModel)
            )
        }
        val file = File(context.filesDir, FILE_NAME)
        file.writeText(arr.toString())
    }
}
