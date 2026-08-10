package com.anglesgirl.labelscanner.model

/**
 * 单个标签的识别结果 —— 统一 9 段 SAP 码模型。
 *
 * SAP 9 段码（双竖线 || 分隔）：
 *   供应商 || 箱号 || 物料编码 || 数量 || 日期 || 69商品码 || NA || 00 || NA
 * WMS 采集只读到日期（第 5 段），后面 6~9 段它不理 → 69 码放第 6 段安全。
 *
 * @param barcodes 帧内检测到的所有条码原始值（69码/SN码/QR 等）
 * @param ocrText  OCR 识别出的完整文本（供人工确认/解析）
 * @param supplier 段1 供应商（标签无 → NA）
 * @param serialNumber 段2 箱号（大多数情况 = 序列号；例外以后再说）
 * @param materialCode 段3 物料编码（标签有→填；无→69 反查；查不到留空）
 * @param quantity 段4 数量（默认 1）
 * @param productionDate 段5 日期（标签有→填；无→NA；格式 yyyymmdd）
 * @param ean69 段6 69 商品码（EAN13，反查物料编码的钥匙）
 * @param model 附加字段：产品型号（9 段无位置，自有数据保留）
 * @param color 附加字段：产品颜色（自有数据保留）
 * @param tonerModel 附加字段：商品硒鼓/耗材型号（自有数据保留）
 * @param trayCode 托盘码（WMS 托盘关联，不修改原始标签数据）
 */
data class LabelResult(
    val barcodes: List<String> = emptyList(),
    val ocrText: String = "",
    var supplier: String = "NA",
    var serialNumber: String = "",
    var materialCode: String = "",
    var quantity: Int = 1,
    var productionDate: String = "",
    var ean69: String = "",
    var model: String = "",
    var color: String = "",
    var tonerModel: String = "",
    var trayCode: String = "",  // 托盘码：WMS 托盘关联，不修改原始标签数据
) {
    /** 是否有任何可用数据 */
    val hasData: Boolean
        get() = barcodes.isNotEmpty() || ocrText.isNotBlank() ||
                materialCode.isNotBlank() || serialNumber.isNotBlank() || ean69.isNotBlank()

    /** 拼回 SAP 9 段码（双竖线 ||），空字段填 NA —— 生成二维码/迁移用 */
    fun toSapCode(): String = listOf(
        supplier.ifBlank { "NA" },
        serialNumber.ifBlank { "NA" },
        materialCode.ifBlank { "NA" },
        quantity.toString(),
        productionDate.ifBlank { "NA" },
        ean69.ifBlank { "NA" },
        "NA", "00", "NA",
    ).joinToString("||")

    /** 模板映射：D/E/F/I/O 五列 */
    fun toTemplateRow(): Map<String, String> = mapOf(
        "D" to materialCode,
        "E" to serialNumber,
        "F" to quantity.toString(),
        "I" to productionDate,
        "O" to serialNumber,
    )
}
