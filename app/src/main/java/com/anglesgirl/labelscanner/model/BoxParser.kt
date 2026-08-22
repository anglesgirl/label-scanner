package com.anglesgirl.labelscanner.model

import java.util.regex.Pattern

/**
 * 单箱入库解析结果：一箱（外箱 LPN）对应多个序列号。
 *
 * 字段来源规则（2026-08-11 真实标签 DL-5120P 实测）：
 *  - 物料编码 = SAP 号（条码前缀 12 位纯数字，如 201101002301）
 *  - 箱号 = 独立于物料前缀的混合码（DL-5120P 为 CA70565P10013014；
 *    ⚠️ 箱号格式不固定，仅作候选，由人工确认/输入）
 *  - 序列号 = 以物料(SAP)开头的条码（一个箱子多个 SN 正常）
 *  - 生产日期 = OCR 的 DATE 行（2026.05.07 → 20260507）
 *  - 型号 = OCR 的 MODEL 行
 */
data class BoxParseResult(
    val materialCode: String = "",
    val boxCode: String = "",
    val productionDate: String = "",
    val model: String = "",
    val ean69: String = "",
    val materialFromEan69: Boolean = false,
    val serialNumbers: List<String> = emptyList(),
    val allBarcodes: List<String> = emptyList(),
) {
    val hasData: Boolean
        get() = allBarcodes.isNotEmpty() || materialCode.isNotBlank() ||
                boxCode.isNotBlank() || serialNumbers.isNotEmpty() ||
                productionDate.isNotBlank()
}

/**
 * 单箱标签解析：条码通道为主（准确），OCR 补日期/型号/物料兜底。
 *
 * 条码分类优先级（2026-08-11 真实标签 DL-5120P 校准）：
 *  1. EAN13（69 开头 13 位）→ 商品码
 *  2. 12 位纯数字 → SAP 号 = 物料编码
 *  3. 字母数字混合码：
 *     - 以物料代码（SAP）开头的 → 序列号（一箱多个全部保留）
 *     - 不以物料开头的独立混合码 → 箱号/LPN 候选（第一个；⚠️ 箱号格式
 *       不固定（DL-5120P 是 CA 开头，其他标签可能是别的），不可靠时留空
 *       由人工输入）
 *  4. 无物料代码时：混合码全部归序列号，箱号留空（无法可靠区分）
 */
object BoxParser {

    private val EAN13 = Pattern.compile("^69\\d{11}$")
    private val SAP12 = Pattern.compile("^\\d{12}$")
    private val DATE_SEP = Pattern.compile("^\\d{4}[-/. ]\\d{2}[-/. ]\\d{2}$")
    private val DATE8 = Pattern.compile("^\\d{8}$")

    fun parse(barcodes: List<String>, ocrText: String, lookup69: ((String) -> String?)? = null): BoxParseResult {
        var material = ""
        var ean = ""
        var materialFromEan69 = false
        var box = ""
        var date = ""
        var model = ""
        val sns = mutableListOf<String>()
        val classified = mutableListOf<String>()

        // 第 1 轮条码：EAN13 / 纯 12 位数字(SAP)
        for (code in barcodes) {
            val c = code.trim()
            if (c.isEmpty()) continue
            classified.add(c)
            when {
                EAN13.matcher(c).matches() && ean.isEmpty() -> ean = c
                SAP12.matcher(c).matches() && material.isEmpty() -> material = c
            }
        }

        // 第 2 步：先从条码前缀提取物料。条码是机器读取结果，优先级高于 OCR。
        // 混合码前 12 位是纯数字时，该 12 位即 SAP 物料。
        if (material.isEmpty() && ean.isNotEmpty()) {
            lookup69?.invoke(ean)?.let {
                material = it
                materialFromEan69 = true
            }
        }
        if (material.isEmpty()) {
            for (code in barcodes) {
                val c = code.trim()
                if (c.length > 12 && c.take(12).all { it.isDigit() } && c.any { it.isLetter() }) {
                    material = c.take(12)
                    break
                }
            }
        }
        // 条码没有物料时，OCR 才作为补充来源。
        for (line in ocrText.lines()) {
            val l = line.trim()
            if (l.isEmpty()) continue
            val upper = l.uppercase()
            when {
                material.isEmpty() && SAP12.matcher(l).matches() -> material = l
                material.isEmpty() && (upper.startsWith("SAP") || upper.startsWith("SAP.")) -> {
                    Regex("(\\d{12})").find(l)?.groupValues?.get(1)?.let { material = it }
                }
            }
        }

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
            }
        }

        // 第 3 轮条码：混合码区分 SN / 箱号（material 已定，才能判前缀）。
        // 箱号与 SN 规则明显不一致（SN=物料前缀+后缀；箱号=独立格式的码）——
        // 以物料开头的混合码 → SN；独立混合码 → 箱号候选（可人工改）。
        // ⚠️ 二维码可能是多值（逗号分隔的多个 SN，如 PANTUM 箱标签 QR：
        //    "SN1,SN2,...,SN32"）→ 先拆分再分类。
        for (code in barcodes) {
            val c = code.trim()
            if (c.isEmpty() || c == material || c == ean) continue
            if (c.contains(',') || c.contains(';')) {
                // 多值码（QR 集成 SN）：逗号/分号分隔 → 拆分成多个 SN 全部加入
                // ⚠️ 拆分出的 69 码（EAN13）是商品码，绝不进序列号列表
                c.split(Regex("[,;，；\\s]+")).filter { it.length >= 6 }
                    .filterNot { EAN13.matcher(it).matches() }
                    .forEach { sn ->
                        if (sn != material && sn != ean && sn !in sns) sns.add(sn)
                    }
                continue
            }
            if (!c.any { it.isLetter() }) continue // 纯数字非 SAP（PO/SO）忽略
            val isSnPrefix = material.isNotEmpty() && c.startsWith(material)
            if (material.isNotEmpty() && isSnPrefix) {
                if (c !in sns) sns.add(c)
            } else if (material.isNotEmpty()) {
                if (box.isEmpty()) { box = c }            // 独立码 → 箱号（PA/CA 开头等）
                else if (c != box && c !in sns) sns.add(c) // 多条独立码：其余进 SN 人工挑
            } else {
                // 无物料：无法区分箱号/SN，全归 SN（箱号人工输入）
                if (c !in sns) sns.add(c)
            }
        }

        // 序列号兜底：OCR 行中的混合码（条码为空或不足时），排除已分类值
        // ⚠️ 69 码（EAN13）不含字母已被 isLetter 挡掉，这里再显式过滤一次保险
        if (sns.isEmpty()) {
            for (line in ocrText.lines()) {
                val l = line.trim()
                if (l.length in 6..40 && l.all { it.isLetterOrDigit() } && l.any { it.isLetter() }) {
                    if (l != material && l != box && l != ean && l !in sns && !EAN13.matcher(l).matches()) sns.add(l)
                }
            }
        }

        return BoxParseResult(
            materialCode = material,
            boxCode = box,
            productionDate = date,
            model = model,
            ean69 = ean,
            materialFromEan69 = materialFromEan69,
            serialNumbers = sns,
            allBarcodes = classified,
        )
    }
}
