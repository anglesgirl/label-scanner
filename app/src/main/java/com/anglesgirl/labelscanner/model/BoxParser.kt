package com.anglesgirl.labelscanner.model

import java.util.regex.Pattern

/**
 * 单箱入库解析结果：一箱（外箱 LPN）对应多个序列号。
 *
 * 字段来源规则（2026-08-11 真实标签 DL-5120P 实测）：
 *  - 物料编码 = SAP 号（条码前缀 12 位纯数字，如 201101002301）
 *  - 外箱 LPN = CA 开头的条码（如 CA70565P10013014）→ 托盘码 trayCode
 *  - 序列号 = 其余字母数字混合条码（一个箱子多个 SN 正常）
 *  - 生产日期 = OCR 的 DATE 行（2026.05.07 → 20260507）
 *  - 型号 = OCR 的 MODEL 行
 */
data class BoxParseResult(
    val materialCode: String = "",
    val trayCode: String = "",
    val productionDate: String = "",
    val model: String = "",
    val ean69: String = "",
    val serialNumbers: List<String> = emptyList(),
    val allBarcodes: List<String> = emptyList(),
) {
    val hasData: Boolean
        get() = allBarcodes.isNotEmpty() || materialCode.isNotBlank() ||
                trayCode.isNotBlank() || serialNumbers.isNotEmpty() ||
                productionDate.isNotBlank()
}

/**
 * 单箱标签解析：条码通道为主（准确），OCR 补日期/型号/物料兜底。
 *
 * 条码分类优先级：
 *  1. EAN13（69 开头 13 位）→ 商品码
 *  2. 12 位纯数字 → SAP 号 = 物料编码
 *  3. CA 开头（字母+数字混合）→ 外箱 LPN → 托盘码
 *  4. 其余字母数字混合 → 序列号（多 SN 全部保留）
 */
object BoxParser {

    private val EAN13 = Pattern.compile("^69\\d{11}$")
    private val SAP12 = Pattern.compile("^\\d{12}$")
    private val DATE_SEP = Pattern.compile("^\\d{4}[-/. ]\\d{2}[-/. ]\\d{2}$")
    private val DATE8 = Pattern.compile("^\\d{8}$")
    private val LPN = Pattern.compile("^CA[A-Za-z0-9]{8,}$", Pattern.CASE_INSENSITIVE)

    fun parse(barcodes: List<String>, ocrText: String): BoxParseResult {
        var material = ""
        var tray = ""
        var ean = ""
        val sns = mutableListOf<String>()
        val classified = mutableListOf<String>()

        for (code in barcodes) {
            val c = code.trim()
            if (c.isEmpty()) continue
            classified.add(c)
            when {
                EAN13.matcher(c).matches() && ean.isEmpty() -> ean = c
                SAP12.matcher(c).matches() && material.isEmpty() -> material = c
                LPN.matcher(c).matches() && tray.isEmpty() -> tray = c
                c.any { it.isLetter() } && !c.all { it.isDigit() } -> {
                    if (c !in sns) sns.add(c)
                }
                else -> { /* 其他纯数字（PO/SO 等）忽略 */ }
            }
        }

        // OCR 兜底：日期 / 型号 / SAP 物料 / 额外 SN 候选
        var date = ""
        var model = ""
        for (line in ocrText.lines()) {
            val l = line.trim()
            if (l.isEmpty()) continue
            val upper = l.uppercase()
            when {
                date.isEmpty() && (DATE8.matcher(l).matches() || DATE_SEP.matcher(l).matches()) -> {
                    val digits = l.replace(Regex("[^0-9]"), "")
                    if (digits.length == 8) {
                        val m = digits.substring(4, 6).toIntOrNull()
                        val d = digits.substring(6, 8).toIntOrNull()
                        if (m != null && d != null && m in 1..12 && d in 1..31) date = digits
                    }
                }
                date.isEmpty() && (upper.startsWith("DATE") || upper.startsWith("MFG") ||
                    upper.startsWith("生产日期") || upper.startsWith("PD")) -> {
                    // 带字段前缀：DATE: 2026.05.07 / MFG:DATE: 20260507
                    Regex("(\\d{4}[-/. ]\\d{2}[-/. ]\\d{2}|\\d{8})").find(l)
                        ?.groupValues?.get(1)?.let { raw ->
                            val digits = raw.replace(Regex("[^0-9]"), "")
                            if (digits.length == 8) {
                                val m = digits.substring(4, 6).toIntOrNull()
                                val d = digits.substring(6, 8).toIntOrNull()
                                if (m != null && d != null && m in 1..12 && d in 1..31) date = digits
                            }
                        }
                }
                model.isEmpty() && (upper.startsWith("MODEL") || upper.contains("型号")) -> {
                    Regex("[:：]?\\s*([A-Za-z0-9][A-Za-z0-9\\-]*)\\s*$").find(l)
                        ?.groupValues?.get(1)?.takeIf { it.length in 2..40 }
                        ?.let { model = it }
                }
                material.isEmpty() && SAP12.matcher(l).matches() -> material = l
                upper.startsWith("SAP") || upper.startsWith("SAP.") -> {
                    // SAP.: 201101002301
                    Regex("(\\d{12})").find(l)?.groupValues?.get(1)?.let { material = it }
                }
            }
        }

        // 序列号兜底：OCR 行中的混合码（条码为空或不足时），排除已分类值
        if (sns.isEmpty()) {
            for (line in ocrText.lines()) {
                val l = line.trim()
                if (l.length in 6..40 && l.all { it.isLetterOrDigit() } && l.any { it.isLetter() }) {
                    if (l != material && l != tray && l != ean && l !in sns) sns.add(l)
                }
            }
        }

        return BoxParseResult(
            materialCode = material,
            trayCode = tray,
            productionDate = date,
            model = model,
            ean69 = ean,
            serialNumbers = sns,
            allBarcodes = classified,
        )
    }
}
