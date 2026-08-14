package com.anglesgirl.labelscanner.model

import java.util.regex.Pattern

/**
 * 标签值 → 9 段 SAP 模型 的解析器。
 *
 * 两条识别通道：
 *  - 条码通道（准）：69 码（EAN13）→ 反查物料；SN 条码 → 序列号
 *  - OCR 通道（辅助）：物料编码/日期/型号/颜色/硒鼓
 *  条码结果优先，OCR 只补缺 + 交叉验证（不一致由 UI 人工确认）。
 *
 * SAP 9 段码（|| 分隔）：
 *   供应商 || 箱号 || 物料编码 || 数量 || 日期(yyyymmdd) || 69码 || 透传 || 透传 || 透传
 * 后 4 段对用户系统无用，透传保留。
 */
object LabelParser {

    private val DATE8 = Pattern.compile("^\\d{8}$")
    private val DATE_SEP = Pattern.compile("^\\d{4}[-/. ]\\d{2}[-/. ]\\d{2}$")
    private val EAN13 = Pattern.compile("^69\\d{11}$")
    private val MAT10 = Pattern.compile("^\\d{10}$")
    private val MAT12 = Pattern.compile("^\\d{12}$")
    private val SN_MIX = Pattern.compile("^[A-Za-z0-9]{6,30}$")

    /** 解析单个值，返回 (字段名, 规范化值)，无法判断返回 null */
    fun classify(value: String): Pair<String, String>? {
        val v = value.trim()
        if (v.isEmpty()) return null

        // 日期：8 位纯数字（yyyymmdd）或带符号（2025-05-08 / 2025.05.08）
        // → 统一归一为 8 位无符号 yyyymmdd
        if (DATE8.matcher(v).matches() || DATE_SEP.matcher(v).matches()) {
            val digits = v.replace(Regex("[^0-9]"), "")
            if (digits.length == 8) {
                val m = digits.substring(4, 6).toInt()
                val d = digits.substring(6, 8).toInt()
                if (m in 1..12 && d in 1..31) return "date" to digits
            }
        }

        // EAN13 商品码（69 开头）
        if (EAN13.matcher(v).matches()) return "ean" to v

        // 物料编码：10 位（补 01）或 12 位
        if (MAT10.matcher(v).matches()) return "material10" to v
        if (MAT12.matcher(v).matches()) return "material12" to v

        // SN：字母数字混合
        if (SN_MIX.matcher(v).matches() && v.any { it.isLetter() }) return "sn" to v

        return null
    }

    /**
     * 主入口：条码 + OCR 合并解析成 9 段模型。
     *
     * @param barcodes 条码通道（准）
     * @param ocrText  OCR 通道（辅助）
     * @param lookup69 69码→物料编码 反查函数（可为 null）
     */
    fun parse(
        barcodes: List<String>,
        ocrText: String,
        lookup69: ((String) -> String?)? = null,
    ): LabelResult {
        // 1. 条码里若直接有 9 段码（QR 内容含 ||）→ 直接拆段
        val sapCode = barcodes.firstOrNull { it.contains("||") }
        if (sapCode != null) return parseSapCode(sapCode, barcodes, ocrText)

        val result = LabelResult(barcodes = barcodes, ocrText = ocrText)

        // 2. 条码通道：69 码 / SN / 物料 / 日期
        for (code in barcodes) {
            when (classify(code)?.first) {
                "ean" -> if (result.ean69.isEmpty()) result.ean69 = code
                "sn" -> if (result.serialNumber.isEmpty()) result.serialNumber = code
                "material10", "material12" -> if (result.materialCode.isEmpty()) {
                    result.materialCode = normalizeMaterial(code)
                }
                "date" -> if (result.productionDate.isEmpty()) result.productionDate = code
            }
        }

        // 3. 69 码反查物料（条码通道优先；OCR 的物料只做交叉验证）
        if (result.materialCode.isEmpty() && result.ean69.isNotEmpty() && lookup69 != null) {
            lookup69(result.ean69)?.let { result.materialCode = it }
        }

        // 3.1 条码前缀提取物料（不依赖 OCR）：
        // 混合码前 12 位是纯数字 → 该 12 位即 SAP 物料（201051012201V00224 → 201051012201）
        if (result.materialCode.isEmpty()) {
            for (code in barcodes) {
                val c = code.trim()
                if (c.length > 12 && c.take(12).all { it.isDigit() } && c.any { it.isLetter() }) {
                    result.materialCode = c.take(12)
                    break
                }
            }
        }

        // 3.2 SN 修正：箱号（独立格式码）可能排在混合码前面被误选为 SN →
        // 物料已知时，SN 必须是以物料开头的混合码（201051012201V00224 型）
        if (result.materialCode.isNotEmpty() && result.serialNumber.isNotEmpty() &&
            !result.serialNumber.startsWith(result.materialCode)
        ) {
            for (code in barcodes) {
                val c = code.trim()
                if (c.length > 12 && c.startsWith(result.materialCode) && c.any { it.isLetter() }) {
                    result.serialNumber = c
                    break
                }
            }
        }

        // 4. 规则：SN 以 12 位纯数字 + "01" 结尾 → 取前 12 位当物料编码
        if (result.materialCode.isEmpty() && result.serialNumber.isNotEmpty()) {
            val sn = result.serialNumber
            if (sn.length >= 12 && sn.take(12).all { it.isDigit() } && sn.substring(10, 12) == "01") {
                result.materialCode = sn.take(12)  // 直接用 12 位（已含 01 结尾）
            }
        }

        // 5. OCR 通道：补缺 + 提取型号/颜色/硒鼓/供应商
        val lines = ocrText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        for (line in lines) {
            applyOcrLine(result, line, lookup69)
        }

        // 6. 兜底：无物料编码 + 69 码反查失败 → 标记需人工输入（上层 UI 提示）
        //    这里不抛异常，返回结果让 UI 判断 materialCode 是否为空
        // 7. 兜底：无生产日期 → 默认 19000101
        if (result.productionDate.isEmpty()) {
            result.productionDate = "19000101"
        }

        return result
    }

    /** 解析 SAP 9 段码（含 || 的字符串），后 4 段透传不解析 */
    fun parseSapCode(code: String, barcodes: List<String> = emptyList(), ocrText: String = ""): LabelResult {
        val parts = code.split("||").map { it.trim() }
        fun seg(i: Int): String = parts.getOrNull(i)?.takeIf { it.isNotBlank() && it != "NA" } ?: ""
        return LabelResult(
            barcodes = barcodes,
            ocrText = ocrText,
            supplier = seg(0).ifBlank { "NA" },
            serialNumber = seg(1),
            materialCode = seg(2),
            quantity = parts.getOrNull(3)?.toIntOrNull() ?: 1,
            productionDate = seg(4),
            ean69 = seg(5),
        )
    }

    /** OCR 单行处理：补缺字段 + 提取附加信息 */
    private fun applyOcrLine(result: LabelResult, line: String, lookup69: ((String) -> String?)?) {
        // 带标签的行：产品型号/颜色/商品硒鼓/供应商
        when {
            line.contains("型号") -> {
                val m = Regex("[:：]\\s*([A-Za-z0-9][A-Za-z0-9\\-]*)").find(line)
                if (m != null && result.model.isEmpty()) result.model = m.groupValues[1]
                return
            }
            line.contains("颜色") -> {
                val m = Regex("[:：]\\s*(\\S+)").find(line)
                if (m != null && result.color.isEmpty()) result.color = m.groupValues[1]
                return
            }
            line.contains("硒鼓") || line.contains("耗材") -> {
                val m = Regex("[:：]\\s*([A-Za-z0-9][A-Za-z0-9\\-]*)").find(line)
                if (m != null && result.tonerModel.isEmpty()) result.tonerModel = m.groupValues[1]
                return
            }
            line.contains("供应商") -> {
                val m = Regex("[:：]\\s*(\\S+)").find(line)
                if (m != null) result.supplier = m.groupValues[1]
                return
            }
            line.contains("S/N", ignoreCase = true) || line.contains("SN", ignoreCase = true) ||
                line.contains("序列号") -> {
                val m = Regex("[:：]?\\s*([A-Za-z0-9]{6,30})").find(line.replace("S/N", "SN").replace("s/n", "SN"))
                // ⚠️ 69 码（EAN13）是商品码不是序列号：S/N 行扫到 69 开头 13 位纯数字 → 不能进 SN
                if (m != null && result.serialNumber.isEmpty() && !EAN13.matcher(m.groupValues[1]).matches()) {
                    result.serialNumber = m.groupValues[1]
                }
                return
            }
        }

        // 纯值行：按值特征补缺
        when (classify(line)?.first) {
            "date" -> if (result.productionDate.isEmpty()) result.productionDate = normalizeDate(line)
            "material10", "material12" -> if (result.materialCode.isEmpty()) {
                result.materialCode = normalizeMaterial(line)
            }
            "sn" -> if (result.serialNumber.isEmpty()) result.serialNumber = line
        }
    }

    /** 物料编码规范化：10 位补 01；12 位原样；69 开头保留（自有数据） */
    fun normalizeMaterial(code: String): String {
        val c = code.trim()
        return if (c.length == 10) c + "01" else c
    }

    /** 日期规范化：带符号日期 → 8 位无符号 yyyymmdd；已是 8 位数字原样 */
    fun normalizeDate(date: String): String {
        val digits = date.replace(Regex("[^0-9]"), "")
        return if (digits.length == 8) digits else date
    }
}
