package com.anglesgirl.labelscanner.model.v2

/**
 * 字段类型枚举。判定完全基于**值的形态**，不依赖排版，也不依赖
 * "SN 是否以物料编码开头"——后者是 v1 采错的根因（真实标签全部不成立）。
 */
enum class FieldKind {
    MATERIAL,   // 物料编码
    DATE,       // 生产日期
    SN,         // 序列号
    BOX,        // 箱号 / LPN
    EAN13,      // 69 开头的商品条码（不进模板）
    QTY,        // 数量（标签上的 QTY.）
    UNKNOWN,
}

data class ParsedField(val kind: FieldKind, val value: String)

/**
 * 一整箱的解析结果。
 *
 * 三种场景（来自用户真实标签盘点，见 docs/DESIGN-v2.md）：
 *  1) 打印机（一箱一台）      -> serialNumbers 1 个，qty = 1
 *  2) 粉盒·有 SN（集成码）    -> serialNumbers N 个（二维码逗号分隔），qty = N
 *  3) 粉盒·只有箱号           -> serialNumbers 为空，qty = 标签上的 QTY
 */
data class BoxParseResultV2(
    val materialCode: String = "",
    val productionDate: String = "",
    val boxCode: String = "",
    val serialNumbers: List<String> = emptyList(),
    val qty: Int = 0,
    val ean13: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
) {
    /** 这一箱应该导出几条记录。 */
    val recordCount: Int
        get() = when {
            serialNumbers.isNotEmpty() -> serialNumbers.size
            else -> 1                       // 只有箱号（或无 SN）→ 1 条，数量用 qty
        }

    /** 模板 F 列：有 SN 时每行 1；只有箱号时用整箱数量。 */
    fun qtyForRow(index: Int): Int =
        if (serialNumbers.isNotEmpty()) 1 else qty.coerceAtLeast(1)

    /** 该行的序列号/箱号：有 SN 用 SN；没有则两列都填箱号。 */
    fun snForRow(index: Int): String =
        if (serialNumbers.isNotEmpty()) serialNumbers[index] else boxCode
}

/**
 * v2 解析内核。
 *
 * 与 v1 的关键区别：**不再用"是否以物料编码开头"区分 SN 与箱号**。
 * 真实标签上 SN 与物料编码没有任何前缀关系，v1 的假设直接导致
 * 「第一个 SN 被当箱号、真箱号被丢弃、SN 少一个」。
 */
object LabelParserV2 {

    private val DATE8 = Regex("^\\d{8}$")
    private val DATE_SEP = Regex("^\\d{4}[-/. _]\\d{1,2}[-/. _]\\d{1,2}$")
    private val DATE_CN = Regex("^\\d{4}\\s*年\\s*\\d{1,2}\\s*月\\s*\\d{1,2}\\s*日$")
    private val EAN13 = Regex("^69\\d{11}$")
    private val MAT10 = Regex("^\\d{10}$")
    private val MAT12 = Regex("^\\d{12}$")
    private val SN_MIX = Regex("^[A-Za-z0-9]{6,30}$")

    /**
     * 箱号前缀与最小长度。
     *
     * 真实样本（全部 16 位）：CA70450P10052278、PA40157P10025632、PA40169P10010214。
     * ⚠️ 不要加入 CL / CV —— CL7LV015J0（A4 机 SN）、CV7DV0007F（本体 SN）
     * 都是 SN 而不是箱号；长度下限 14 用来和这些短 SN 区分开。
     */
    private val BOX_PREFIXES = listOf("CA", "PA")
    private const val BOX_MIN_LEN = 14

    /** SAP / P/N 等字段名行，用于从 OCR 文本里捞物料。 */
    private val SAP_LABEL = Regex("(?i)^(SAP\\.?|P/N|物料编码)\\s*[:：]?\\s*(\\d{10,12})$")

    /**
     * 单值判定。OCR 易把 0 认成 O，日期判定前先规整。
     */
    fun classify(raw: String): ParsedField? {
        val v = raw.trim().trim(',', ';', '，', '；')
        if (v.isEmpty()) return null

        // ① 日期：8 位数字 / 带分隔符 / 中文年月日 → 归一成 yyyymmdd
        val vNorm = v.replace('O', '0').replace('o', '0')
        if (DATE8.matches(vNorm) || DATE_SEP.matches(vNorm) || DATE_CN.matches(vNorm)) {
            val digits = vNorm.filter { it.isDigit() }
            if (digits.length == 8) {
                val m = digits.substring(4, 6).toInt()
                val d = digits.substring(6, 8).toInt()
                if (m in 1..12 && d in 1..31) return ParsedField(FieldKind.DATE, digits)
            } else if (digits.length == 6 && DATE_SEP.matches(vNorm)) {
                // 月日无前导 0：2026 6 22
                val y = digits.substring(0, 4)
                val m = digits.substring(4, 5).toInt()
                val d = digits.substring(5, 6).toInt()
                if (m in 1..12 && d in 1..31) {
                    return ParsedField(FieldKind.DATE, "%s%02d%02d".format(y, m, d))
                }
            }
        }

        // ② EAN13 商品码（69 开头）——不进模板
        if (EAN13.matches(v)) return ParsedField(FieldKind.EAN13, v)

        // ③ 箱号：CA/PA 开头且长度足够（真实箱号 16 位）。
        //    短码如 CL7LV015J0 / CV7DV0007F 是 SN，必须走 ⑤ 的 SN 分支。
        val upper = v.uppercase()
        if (BOX_PREFIXES.any { upper.startsWith(it) } &&
            v.length >= BOX_MIN_LEN &&
            SN_MIX.matches(v) && v.any { it.isLetter() }
        ) {
            return ParsedField(FieldKind.BOX, v)
        }

        // ④ 物料编码：10 位补 01 / 12 位原样
        if (MAT10.matches(v)) return ParsedField(FieldKind.MATERIAL, v + "01")
        if (MAT12.matches(v)) return ParsedField(FieldKind.MATERIAL, v)

        // ⑤ 其余字母数字混合码 → SN
        if (SN_MIX.matches(v) && v.any { it.isLetter() }) return ParsedField(FieldKind.SN, v)

        return ParsedField(FieldKind.UNKNOWN, v)
    }

    /** 二维码/集成码内容拆分：`,` `;` 换行 都当分隔符。 */
    fun splitPayload(payload: String): List<String> =
        payload.split(',', ';', '，', '；', '\n', '\r')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /** 从 OCR 文本行里捞物料（SAP. / P/N 行）。 */
    fun materialFromOcrLine(line: String): String? {
        val m = SAP_LABEL.find(line.trim()) ?: return null
        val digits = m.groupValues[2]
        return when (digits.length) {
            10 -> digits + "01"
            12 -> digits
            else -> null
        }
    }

    /** 从 OCR 文本行里捞数量（QTY. 8 PCS）。 */
    fun qtyFromOcrLine(line: String): Int? {
        val m = Regex("(?i)(QTY\\.?|数量)\\s*[:：]?\\s*(\\d{1,4})").find(line) ?: return null
        return m.groupValues[2].toIntOrNull()
    }

    /**
     * 主入口：把「扫到的所有码 + OCR 得到的文本行」归组成一箱结果。
     *
     * @param codes  条码/二维码解码结果，以及二维码拆出来的 SN（调用方已拆好也可）
     * @param ocrLines OCR 文本行（用于补 SAP 行、QTY、日期）
     * @param qrPayloads 二维码原始内容（内部会按逗号拆成 SN）
     */
    fun parse(
        codes: List<String> = emptyList(),
        ocrLines: List<String> = emptyList(),
        qrPayloads: List<String> = emptyList(),
    ): BoxParseResultV2 {
        val warnings = mutableListOf<String>()
        var material = ""
        var date = ""
        var box = ""
        var qty = 0
        val sns = mutableListOf<String>()
        val eans = mutableListOf<String>()

        // 二维码内容优先当 SN 集合（集成码形态：N 个 SN 逗号分隔）
        for (p in qrPayloads) {
            val parts = splitPayload(p)
            if (parts.size > 1) {
                for (x in parts) {
                    val f = classify(x) ?: continue
                    if (f.kind == FieldKind.SN) sns += f.value
                    else if (f.kind == FieldKind.BOX && box.isEmpty()) box = f.value
                    else if (f.kind == FieldKind.MATERIAL && material.isEmpty()) material = f.value
                    else if (f.kind == FieldKind.DATE && date.isEmpty()) date = f.value
                    else if (f.kind == FieldKind.EAN13) eans += f.value
                }
                continue
            }
            // 单值二维码：当普通码处理
            val f = classify(p) ?: continue
            when (f.kind) {
                FieldKind.SN -> sns += f.value
                FieldKind.BOX -> if (box.isEmpty()) box = f.value
                FieldKind.MATERIAL -> if (material.isEmpty()) material = f.value
                FieldKind.DATE -> if (date.isEmpty()) date = f.value
                FieldKind.EAN13 -> eans += f.value
                else -> Unit
            }
        }

        // 普通条码
        for (c in codes) {
            val f = classify(c) ?: continue
            when (f.kind) {
                FieldKind.MATERIAL -> if (material.isEmpty()) material = f.value
                FieldKind.DATE -> if (date.isEmpty()) date = f.value
                FieldKind.BOX -> if (box.isEmpty()) box = f.value
                FieldKind.SN -> sns += f.value
                FieldKind.EAN13 -> eans += f.value
                else -> Unit
            }
        }

        // OCR 兜底：SAP 行 / QTY / 日期
        for (line in ocrLines) {
            if (material.isEmpty()) materialFromOcrLine(line)?.let { material = it }
            if (qty == 0) qtyFromOcrLine(line)?.let { qty = it }
            if (date.isEmpty()) {
                classify(line)?.let { if (it.kind == FieldKind.DATE) date = it.value }
            }
        }

        // 【去错规则 1】前缀抑制：短码是长码前缀 → 短的丢弃
        // 真实案例：A3 标签上 ACP5P81（产品代码）是 ACP5P81000050（SN）的前缀
        val deduped = suppressPrefixes(sns)

        // 【去错规则 2】同值去重
        val finalSns = deduped.distinct()

        if (sns.size > finalSns.size) {
            val dropped = sns.size - finalSns.size
            warnings += "已忽略 $dropped 个重复/前缀码（如产品代码与 SN 重叠）"
        }

        // 数量对账：标签 QTY 与实扫 SN 数不一致 → 明确告警（不静默通过）
        if (qty > 0 && finalSns.size > 0 && finalSns.size != qty) {
            warnings += "标签数量 $qty 个，实际识别 ${finalSns.size} 个 SN，还差 ${qty - finalSns.size} 个"
        }
        if (material.isEmpty()) warnings += "缺少物料编码"
        if (date.isEmpty()) warnings += "缺少生产日期"

        return BoxParseResultV2(
            materialCode = material,
            productionDate = date,
            boxCode = box,
            serialNumbers = finalSns,
            qty = if (qty > 0) qty else finalSns.size.coerceAtLeast(1),
            ean13 = eans.distinct(),
            warnings = warnings,
        )
    }

    /**
     * 前缀抑制：若某码是另一个更长码的前缀，则丢弃它。
     * 例：["ACP5P81", "ACP5P81000050", "ACP5P81000050"] → ["ACP5P81000050"]
     */
    internal fun suppressPrefixes(codes: List<String>): List<String> {
        val uniq = codes.distinct()
        return uniq.filter { c ->
            !uniq.any { other -> other != c && other.length > c.length && other.startsWith(c) }
        }
    }
}
