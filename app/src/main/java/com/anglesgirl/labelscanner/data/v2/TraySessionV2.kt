package com.anglesgirl.labelscanner.data.v2

import com.anglesgirl.labelscanner.model.v2.BoxParseResultV2

/**
 * 一箱的采集记录（对应 WMS 模板里的若干行）。
 */
data class BoxRecordV2(
    /** 箱号；可能为空（部分标签没有箱号）。 */
    val boxCode: String = "",
    val materialCode: String = "",
    val productionDate: String = "",
    val serialNumbers: List<String> = emptyList(),
    /** 标签上标注的数量（QTY.），0 表示没读到。 */
    val declaredQty: Int = 0,
    val warnings: List<String> = emptyList(),
    val capturedAt: Long = System.currentTimeMillis(),
) {
    /** 本箱导出几条记录。 */
    val rowCount: Int get() = if (serialNumbers.isNotEmpty()) serialNumbers.size else 1

    /** 本箱实际序号数量（无 SN 时按声明数量算）。 */
    val effectiveQty: Int get() = if (serialNumbers.isNotEmpty()) serialNumbers.size else declaredQty.coerceAtLeast(1)

    /** 是否有需要用户注意的问题（数量不符/缺字段）。 */
    val hasIssue: Boolean get() = warnings.isNotEmpty()

    companion object {
        fun from(r: BoxParseResultV2): BoxRecordV2 = BoxRecordV2(
            boxCode = r.boxCode,
            materialCode = r.materialCode,
            productionDate = r.productionDate,
            serialNumbers = r.serialNumbers,
            declaredQty = r.qty,
            warnings = r.warnings,
        )
    }
}

/**
 * 一个托盘的采集会话。
 *
 * 设计取舍（用户明确要求）：**不预设"应有清单"**，而是采集时实时累加，
 * 采完把汇总展示出来人工检查。所以这里只有统计，没有"预期 vs 实际"的对账。
 */
class TraySessionV2(
    var trayCode: String = "",
    private val boxes: MutableList<BoxRecordV2> = mutableListOf(),
) {

    val allBoxes: List<BoxRecordV2> get() = boxes.toList()

    fun addBox(box: BoxRecordV2) {
        boxes += box
    }

    fun undoLast(): BoxRecordV2? = if (boxes.isNotEmpty()) boxes.removeAt(boxes.lastIndex) else null

    fun removeAt(index: Int): BoxRecordV2? =
        if (index in boxes.indices) boxes.removeAt(index) else null

    fun clear() = boxes.clear()

    /** 按物料汇总，供托盘页展示。 */
    fun summary(): List<TrayMaterialSummary> {
        val byMaterial = linkedMapOf<String, MutableList<BoxRecordV2>>()
        for (b in boxes) {
            val key = b.materialCode.ifEmpty { "(未知物料)" }
            byMaterial.getOrPut(key) { mutableListOf() } += b
        }
        return byMaterial.map { (material, list) ->
            TrayMaterialSummary(
                materialCode = material,
                boxCount = list.size,
                unitCount = list.sumOf { it.effectiveQty },
                boxCodes = list.map { it.boxCode },
            )
        }
    }

    val totalBoxes: Int get() = boxes.size

    val totalUnits: Int get() = boxes.sumOf { it.effectiveQty }

    /** 导出时展开成模板行数（每箱按 rowCount 展开）。 */
    val totalRows: Int get() = boxes.sumOf { it.rowCount }

    /** 有问题的箱（数量不符/缺字段），供界面高亮提醒。 */
    val boxesWithIssues: List<BoxRecordV2> get() = boxes.filter { it.hasIssue }
}

data class TrayMaterialSummary(
    val materialCode: String,
    val boxCount: Int,
    val unitCount: Int,
    val boxCodes: List<String>,
)
