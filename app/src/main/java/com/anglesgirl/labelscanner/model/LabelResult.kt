package com.anglesgirl.labelscanner.model

/**
 * 单个标签的识别结果（扫码 + OCR 合并后）。
 *
 * @param barcodes 帧内检测到的所有条码原始值（可能有多个：商品码/SN码/QR等）
 * @param ocrText  OCR 识别出的完整文本（供人工确认/解析）
 * @param materialCode 物料编码（D 列）
 * @param productionDate 生产日期（I 列，格式 yyyymmdd）
 * @param serialNumber 序列号（E 列 + O 列同值）
 * @param quantity 数量（F 列，默认 1）
 */
data class LabelResult(
    val barcodes: List<String> = emptyList(),
    val ocrText: String = "",
    var materialCode: String = "",
    var productionDate: String = "",
    var serialNumber: String = "",
    var quantity: Int = 1,
) {
    /** 是否有任何可用数据 */
    val hasData: Boolean
        get() = barcodes.isNotEmpty() || ocrText.isNotBlank() ||
                materialCode.isNotBlank() || serialNumber.isNotBlank()

    /** 模板映射：D/E/F/I/O 五列 */
    fun toTemplateRow(): Map<String, String> = mapOf(
        "D" to materialCode,
        "E" to serialNumber,
        "F" to quantity.toString(),
        "I" to productionDate,
        "O" to serialNumber,
    )
}
