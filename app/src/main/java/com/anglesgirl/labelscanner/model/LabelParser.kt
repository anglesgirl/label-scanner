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
    private val DATE_SEP = Pattern.compile("^\\d{4}[-/. _]\\d{1,2}[-/. _]\\d{1,2}$")
    private val DATE_CN = Pattern.compile("^\\d{4}\\s*年\\s*\\d{1,2}\\s*月\\s*\\d{1,2}\\s*日$")
    private val EAN13 = Pattern.compile("^69\\d{11}$")
    private val MAT10 = Pattern.compile("^\\d{10}$")
    private val MAT12 = Pattern.compile("^\\d{12}$")
    private val SN_MIX = Pattern.compile("^[A-Za-z0-9]{6,30}$")

    /** 解析单个值，返回 (字段名, 规范化值)，无法判断返回 null */
    fun classify(value: String): Pair<String, String>? {
        val v = value.trim()
        if (v.isEmpty()) return null

        // 日期：8 位纯数字（yyyymmdd）、带符号（2025-05-08 / 2025.05.08 / 2025_05_08）、
        //       中文（2025年05月08日）、月日无前导0（2025 6 22）
        //       → 统一归一为 8 位 yyyymmdd；OCR 易把 0 认成 O，先规整
        val vNorm = v.replace('O', '0').replace('o', '0')
        if (DATE8.matcher(vNorm).matches() || DATE_SEP.matcher(vNorm).matches() || DATE_CN.matcher(vNorm).matches()) {
            val digits = vNorm.replace(Regex("[^0-9]"), "")
            if (digits.length == 8) {
                val m = digits.substring(4, 6).toInt()
                val d = digits.substring(6, 8).toInt()
                if (m in 1..12 && d in 1..31) return "date" to digits
            } else if (digits.length in 6..7 && DATE_SEP.matcher(vNorm).matches()) {
                // 月日无前导0：2026 6 2（去符号 6 位）→ 20260602；
                //             2026 6 22（去符号 7 位）→ 20260622
                val y = digits.substring(0, 4)
                val m = digits.substring(4, 5).toInt()
                val d = digits.substring(5).toInt()
                if (m in 1..12 && d in 1..31) return "date" to String.format("%s%02d%02d", y, m, d)
            }
        }

        // EAN13 商品码（69 开头）
        if (EAN13.matcher(v).matches()) return "ean" to v

        // 物料编码：10 位（补 01）或 12 位
        if (MAT10.matcher(v).matches()) return "material10" to v
        if (MAT12.matcher(v).matches()) return "material12" to v

        // 箱号：CA/PA 开头、≥14 位的字母数字混合码。
        // （2026-09-17 用户实测第一张整箱标签校准：CA70450P10052278 是箱号
        //  （C/NO 行），SCAG2529704B3 系列才是序列号；第二/三张 PA 开头长码
        //  同样确认是箱号。此前这类码全被当 "sn"，导致箱号抢进序列号框、
        //  真 SN 反而全丢 —— 用户反馈"箱号被当序列号"。）
        if (v.length >= 14 &&
            (v.startsWith("CA", ignoreCase = true) || v.startsWith("PA", ignoreCase = true)) &&
            v.any { it.isLetter() }
        ) return "box" to v

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
                "box" -> if (result.boxCode.isEmpty()) result.boxCode = code
                "sn" -> if (result.serialNumber.isEmpty()) result.serialNumber = code
                "material10", "material12" -> if (result.materialCode.isEmpty()) {
                    result.materialCode = normalizeMaterial(code)
                }
                "date" -> if (result.productionDate.isEmpty()) result.productionDate = code
            }
        }

        // 3. 69 码反查物料（条码通道优先；OCR 的物料只做交叉验证）
        if (result.materialCode.isEmpty() && result.ean69.isNotEmpty() && lookup69 != null) {
            lookup69(result.ean69)?.let {
                result.materialCode = it
                result.materialFromEan69 = true
            }
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

        // 5. OCR 通道：补缺 + 提取型号/颜色/硒鼓/供应商。
        //    先剔除相机水印（与 BoxParser 同款逻辑，2026-09-14 真实标签实测）：
        //    翻拍时 OCR 会读进状态栏 `16:38 / 2026.09.11 / 星期五`，那个"拍摄当天"
        //    的日期会先于标签真日期被 classify 取走。时间行/星期行直接丢；
        //    日期行紧邻时间/星期行、或点号日期(2026.09.11)且全文有星期行 → 丢。
        val ocrTextLines = ocrText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val isTimeLine = { l: String -> Regex("^\\d{1,2}:\\d{2}$").matches(l) }
        val isWeekLine = { l: String -> Regex("^星期[一二三四五六日天]$").matches(l) }
        val isDateLikeLine = { l: String ->
            DATE8.matcher(l).matches() || DATE_SEP.matcher(l).matches() || DATE_CN.matcher(l).matches()
        }
        val hasWeekLine = ocrTextLines.any { isWeekLine(it) }
        val lines = ocrTextLines.filterIndexed { i, l ->
            if (isTimeLine(l) || isWeekLine(l)) false
            else if (isDateLikeLine(l)) {
                val prevIsTime = i > 0 && isTimeLine(ocrTextLines[i - 1])
                val nextIsWeek = i + 1 < ocrTextLines.size && isWeekLine(ocrTextLines[i + 1])
                val dottedAndWeek = Regex("^\\d{4}\\.\\d{2}\\.\\d{2}$").matches(l) && hasWeekLine
                !prevIsTime && !nextIsWeek && !dottedAndWeek
            } else true
        }
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

        // 纯值行：按值特征补缺。
        // 注意日期用 classify 返回的**规范化值**（如 2026 6 22 → 20260622），
        // 不能再用 normalizeDate(line) 重算 —— 它只认 8 位纯数字，会把
        // 月日无前导 0 的日期还原成原样。
        when (val cls = classify(line)) {
            null -> applyOcrSplitLine(result, line)
            else -> when (cls.first) {
                "date" -> if (result.productionDate.isEmpty()) result.productionDate = cls.second
                "material10", "material12" -> if (result.materialCode.isEmpty()) {
                    result.materialCode = normalizeMaterial(line)
                }
                "box" -> if (result.boxCode.isEmpty()) result.boxCode = line
                "sn" -> if (result.serialNumber.isEmpty()) result.serialNumber = line
            }
        }
    }

    /**
     * 一行里并排两个字段（OCR 把左右两段合成一行）时的兜底拆分。
     *
     * 现象（用户实测）：标签同一行印着两个值、中间留白，OCR 把它们合成一行文本，
     * 如 `3011211002   2025-09-17`（物料 + 日期）。整行无法归类（锚定正则
     * `^...$` 匹配不上），原逻辑整行丢弃 —— 两个字段全丢。
     *
     * 处理：按空白拆成段，逐段交给 classify 识别补缺；只收能识别的段，
     * 拆不出来的段直接丢弃，不引入任何新值。
     */
    private fun applyOcrSplitLine(result: LabelResult, line: String) {
        for (part in line.split(Regex("\\s+")).map { it.trim() }.filter { it.isNotEmpty() }) {
            val cls = classify(part) ?: continue
            when (cls.first) {
                "date" -> if (result.productionDate.isEmpty()) result.productionDate = cls.second
                "material10", "material12" -> if (result.materialCode.isEmpty()) {
                    result.materialCode = normalizeMaterial(part)
                }
                "ean" -> if (result.ean69.isEmpty()) result.ean69 = part
                "box" -> if (result.boxCode.isEmpty()) result.boxCode = part
                "sn" -> if (result.serialNumber.isEmpty()) result.serialNumber = part
            }
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
