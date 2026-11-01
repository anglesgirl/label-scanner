package com.anglesgirl.labelscanner.model

import java.time.DateTimeException
import java.time.LocalDate
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

    /** 日期字段分隔符；数字间允许 OCR 插入空白（如 2026-08-2 9）。 */
    private val DATE_SEP = Regex("^\\s*(\\d(?:\\s*\\d){3})\\s*[-/. _－·．]\\s*(\\d(?:\\s*\\d){0,1})\\s*[-/. _－·．]\\s*(\\d(?:\\s*\\d){0,1})\\s*$")
    private val DATE_CN = Regex("^\\s*(\\d(?:\\s*\\d){3})\\s*年\\s*(\\d(?:\\s*\\d){0,1})\\s*月\\s*(\\d(?:\\s*\\d){0,1})\\s*日\\s*$")
    /** 行内日期提取，分隔符之间及数字内部均可有 OCR 空白。 */
    private val DATE_INLINE = Regex("\\d(?:\\s*\\d){3}\\s*[-/. _－·．]\\s*\\d(?:\\s*\\d){0,1}\\s*[-/. _－·．]\\s*\\d(?:\\s*\\d){0,1}")
    private val EAN13 = Pattern.compile("^69\\d{11}$")
    private val MAT10 = Pattern.compile("^\\d{10}$")
    private val MAT12 = Pattern.compile("^\\d{12}$")
    private val SN_MIX = Pattern.compile("^[A-Za-z0-9]{6,30}$")

    /**
     * OCR 容错：去掉数字之间的空白。
     * OCR 常把 "17" 识别成 "1 7"（数字中间插空格），导致日期正则只匹配到
     * "2024-12-1"（7 位），长度校验失败。先把数字间的空格压掉，
     * "2024-12-1 7" → "2024-12-17"，再走正常流程。
     */
    private fun collapseDigitSpaces(input: String): String {
        return input.replace(Regex("(?<=\\d)\\s+(?=\\d)"), "")
    }

    /** OCR 字符容错 + 严格日历校验，输出 yyyyMMdd；非日期或不可能日期返回 null。 */
    private fun normalizeDateCandidate(raw: String): String? {
        val value = raw.trim()
            .replace('O', '0').replace('o', '0')
            .replace('I', '1').replace('l', '1').replace('|', '1')
            .replace('S', '5').replace('s', '5')
            .replace('g', '9')
            .replace('B', '8')

        val digits = when {
            Regex("^\\d(?:\\s*\\d){7}$").matches(value) -> value.replace(Regex("\\s+"), "")
            else -> {
                val separated = DATE_SEP.matchEntire(value) ?: DATE_CN.matchEntire(value)
                if (separated != null) {
                    val year = separated.groupValues[1].replace(Regex("\\s+"), "")
                    val month = separated.groupValues[2].replace(Regex("\\s+"), "")
                    val day = separated.groupValues[3].replace(Regex("\\s+"), "")
                    val y = year.toIntOrNull() ?: return null
                    val m = month.toIntOrNull() ?: return null
                    val d = day.toIntOrNull() ?: return null
                    return validDate(y, m, d)
                }
                return null
            }
        }
        if (digits.length != 8) return null
        val y = digits.substring(0, 4).toIntOrNull() ?: return null
        val m = digits.substring(4, 6).toIntOrNull() ?: return null
        val d = digits.substring(6, 8).toIntOrNull() ?: return null
        return validDate(y, m, d)
    }

    private fun validDate(year: Int, month: Int, day: Int): String? {
        if (year !in 2000..2050) return null
        return try {
            LocalDate.of(year, month, day)
            "%04d%02d%02d".format(year, month, day)
        } catch (_: DateTimeException) {
            null
        }
    }

    /** 解析单个值，返回 (字段名, 规范化值)，无法判断返回 null */
    fun classify(value: String): Pair<String, String>? {
        val raw = value.trim()
        val v = collapseDigitSpaces(raw)
        if (v.isEmpty()) return null

        // 日期先基于原始空白结构判定：直接 collapse 会把 `2026 08-29`
        // 粘成 `202608-29`，导致年份/月日边界丢失。
        normalizeDateCandidate(raw)?.let { return "date" to it }

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
            val cls = classify(code) ?: continue
            when (cls.first) {
                "ean" -> if (result.ean69.isEmpty()) result.ean69 = code
                "box" -> if (result.boxCode.isEmpty()) result.boxCode = code
                "sn" -> if (result.serialNumber.isEmpty()) result.serialNumber = code
                "material10", "material12" -> if (result.materialCode.isEmpty()) {
                    result.materialCode = normalizeMaterial(code)
                }
                "date" -> if (result.productionDate.isEmpty()) result.productionDate = cls.second
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
        val isDateLikeLine = { l: String -> normalizeDateCandidate(l) != null }
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
        // 字段间空格不能被 collapseDigitSpaces 当成数字内部空格吞掉。
        // 连体文本则必须有本帧真实条码作边界证据，不能按尾部长度猜切 SN。
        if (applyAdjacentSerialMaterial(result, line)) return

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
            // 日期标签行：MFG/DATE/日期/生产日期 后跟日期，整行无法归类 → 行内提取
            line.contains("日期") || Regex("\\b(date|mfg|mfd|prod)\b", RegexOption.IGNORE_CASE).containsMatchIn(line) -> {
                if (result.productionDate.isEmpty()) {
                    extractDateInline(line)?.let { result.productionDate = it }
                }
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
            null -> {
                // 行内日期兜底：物料与日期并排（如 `3011211002 2025-09-17`）整行匹配不上时，
                // 先抠日期（不拆段，避免日期与紧贴字符连体），再走拆段补其余字段
                if (result.productionDate.isEmpty()) {
                    extractDateInline(line)?.let { result.productionDate = it }
                }
                applyOcrSplitLine(result, line)
            }
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
     * 先保留字段边界，再做字段内部容错。只接受唯一的 SN/物料组合。
     * 有空格时依赖明确的字母数字 SN + 10/12 位数字；完全连体时依赖条码 SN。
     * 不修改原始 OCR/条码证据，不覆盖条码已填字段，也不推断任意长 SN 的后缀。
     */
    private fun applyAdjacentSerialMaterial(result: LabelResult, line: String): Boolean {
        val value = line.trim().replace(
            Regex("^(?:S/N|SN|序列号)(?:\\s*[:：]\\s*|\\s+)", RegexOption.IGNORE_CASE), ""
        )
        val candidates = linkedSetOf<Pair<String, String>>()
        fun addCandidate(serial: String, material: String) {
            val sn = classify(serial)
            val mat = classify(material)
            if (sn?.first == "sn" && mat?.first in setOf("material10", "material12")) {
                candidates += sn.second to normalizeMaterial(mat!!.second)
            }
        }

        // Keep a real inter-field whitespace boundary. The material itself may contain OCR spaces.
        Regex("^([A-Za-z0-9]{6,30})\\s+(\\d(?:\\s*\\d){9,11})$")
            .matchEntire(value)?.let { addCandidate(it.groupValues[1], it.groupValues[2]) }
        Regex("^(\\d(?:\\s*\\d){9,11})\\s+([A-Za-z0-9]{6,30})$")
            .matchEntire(value)?.let { addCandidate(it.groupValues[2], it.groupValues[1]) }

        // ML Kit can omit the gap entirely (or insert gaps inside either field).
        // Only the exact decoded SN establishes a safe split; never strip a numeric tail blindly.
        val compact = value.replace(Regex("\\s+"), "")
        for (barcode in result.barcodes) {
            val serial = barcode.trim()
            if (classify(serial)?.first != "sn") continue
            if (compact.startsWith(serial, ignoreCase = true)) {
                addCandidate(serial, compact.substring(serial.length))
            }
            if (compact.endsWith(serial, ignoreCase = true)) {
                addCandidate(serial, compact.substring(0, compact.length - serial.length))
            }
        }
        // Distinct possible boundaries are ambiguous: leave the existing/manual path in charge.
        val unique = candidates.distinctBy { it.first.uppercase(java.util.Locale.ROOT) to it.second }
        if (unique.size != 1) return false
        val (serial, material) = unique.single()
        if (result.serialNumber.isEmpty()) result.serialNumber = serial
        if (result.materialCode.isEmpty()) result.materialCode = material
        return true
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

    /**
     * 行内日期提取（生产日期识别加强，2026-09-19）：
     * OCR 常把日期和旁边文字/另一字段合成一行（如 `MFG 2026.07.13`、
     * `生产日期:2026-7-13`、`3011211002   2025-09-17`）。整行锚定匹配不上时，
     * 用本函数从行内抠出日期子串，做字符容错后校验合法性。
     * 只提取"带分隔符"的日期（格式特征强、误伤小）；8 位纯数字留在
     * 整行/分段流程里识别，避免把 SN/物料里的数字串当日期。
     */
    fun extractDateInline(line: String): String? {
        val hit = DATE_INLINE.find(line)?.value ?: return null
        return normalizeDateCandidate(hit)
    }

    /** 物料编码规范化：10 位补 01；12 位原样；69 开头保留（自有数据） */
    fun normalizeMaterial(code: String): String {
        val c = code.trim()
        return if (c.length == 10) c + "01" else c
    }

    /** 日期规范化：有效日期 → 8 位无符号 yyyymmdd；非日期仍原样返回。 */
    fun normalizeDate(date: String): String {
        return normalizeDateCandidate(date) ?: date
    }
}
