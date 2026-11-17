package com.anglesgirl.labelscanner.model

import java.util.regex.Pattern

/**
 * 单箱入库解析结果：一箱（外箱 LPN）对应多个序列号。
 *
 * 字段来源规则（2026-08-11 真实标签 DL-5120P 实测）：
 *  - 物料编码 = SAP 号（10~12 位纯数字，如 3011225058 / 201071000501）
 *  - 箱号 = 独立于物料前缀的混合码（DL-5120P 为 CA70565P10013014；
 *    ⚠️ 箱号格式不固定，仅作候选，由人工确认/输入）
 *  - 序列号 = 以物料(SAP)开头的条码（一个箱子多个 SN 正常）
 *  - 生产日期 = OCR 的 DATE 行（2026.05.07 → 20260507）
 *  - 型号 = OCR 的 MODEL 行
 */
data class BoxParseResult(
    val materialCode: String = "",
    /**
     * 第二物料编码（“二中心编码”）：只有标签上同时出现两个物料编码时才有值。
     *
     * 2026-11-17 奔图外箱标签（MODEL CPJ3000）：印的 SAP 301603100201 是二中心
     * 的生产编码（OCR “SAP.” 行后面，纯文字、无条码）；单独贴的小贴纸条码
     * 308649300001 是一中心的编码，进 D 列（materialCode）。两个编码都是 12 位
     * 末尾 01，旧的 preferMaterial 先到先得区分不了，必须按来源区分：
     * 条码来的 → D 列；OCR “SAP.” 行后面的 → 第二编码。
     * 单个编码的标签：本字段为空，行为与旧版完全一致；不进导出模板（模板列固定）。
     */
    val secondMaterialCode: String = "",
    val boxCode: String = "",
    val productionDate: String = "",
    val model: String = "",
    val ean69: String = "",
    val materialFromEan69: Boolean = false,
    val serialNumbers: List<String> = emptyList(),
    /**
     * 哪些 SN 属于「条码扫不出、只能靠 OCR 兜底推定」的。
     *
     * 用户说明过业务实情："全靠 OCR 的场景也比较少，只有少数标签损坏比较严重的、
     * 条码扫不出来的，才会被迫 OCR 识别 SN。"
     *
     * 这类值可靠性明显低于条码结果（OCR 连 O/0、I/l/1 都分不清），
     * 所以界面要标明来源、提醒必须人工核对 —— 不能跟条码扫出的可信值混在一起看。
     */
    val ocrFallbackSns: Set<String> = emptySet(),
    val allBarcodes: List<String> = emptyList(),
    /**
     * 箱号是否由「单台机器：箱号 = 序列号」规则推得。
     *
     * 用户明确说："对于这种单台的机器来说，它的箱号就是序列号。"
     * 这种情况箱号不是标签上独立印的，而是跟着 SN 走的 —— UI 可以据此提示用户
     * 核对一下，避免在一箱多台的标签上被误用。
     */
    val boxFromSn: Boolean = false,
) {
    val hasData: Boolean
        get() = allBarcodes.isNotEmpty() || materialCode.isNotBlank() ||
                secondMaterialCode.isNotBlank() ||
                boxCode.isNotBlank() || serialNumbers.isNotEmpty() ||
                productionDate.isNotBlank()
}

/**
 * 单箱标签解析：条码通道为主（准确），OCR 补日期/型号/物料兜底。
 *
 * 条码分类优先级（2026-08-11 真实标签 DL-5120P 校准）：
 *  1. EAN13（69 开头 13 位）→ 商品码
 *  2. 10~12 位纯数字 → SAP 号 = 物料编码（长度不统一，勿再写死 12 位）
 *  3. 字母数字混合码：
 *     - 以物料代码（SAP）开头的 → 序列号（一箱多个全部保留）
 *     - 不以物料开头的独立混合码 → 箱号/LPN 候选（第一个；⚠️ 箱号格式
 *       不固定（DL-5120P 是 CA 开头，其他标签可能是别的），不可靠时留空
 *       由人工输入）
 *  4. 无物料代码时：混合码全部归序列号，箱号留空（无法可靠区分）
 */
object BoxParser {

    private val EAN13 = Pattern.compile("^69\\d{11}$")
    /**
     * SAP 物料编码：10~12 位纯数字。
     *
     * 曾写死 12 位（`^\d{12}$`），结果**用户标签上的 10 位 SAP 号识别不到**
     * （实测标签 OCR 给出 `3011225058`，提示却是"未识别到物料"）。
     * 现实中 SAP 号长度不统一（见过 10 位 `3011225058`、12 位 `201071000501`），
     * 所以放宽为 10~12 位。
     *
     * 放宽后仍安全 —— 会混淆的两类值长度都不在这个区间：
     *  - 生产日期 8 位（`20260225`），
     *  - 69 商品码 13 位（`6937173464565`）。
     */
    private val SAP_NUM = Pattern.compile("^\\d{10,12}$")
    /**
     * 带分隔符的日期：月/日允许 **1~2 位**。
     *
     * 曾写死两位（`\d{4}[-/. ]\d{2}[-/. ]\d{2}`），结果实测标签上的
     * `2024-0-07`（月份没补零）匹配不上，日期字段直接掉。
     */
    private val DATE_SEP = Pattern.compile("^\\d{4}[-/. ]\\d{1,2}[-/. ]\\d{1,2}$")
    private val DATE8 = Pattern.compile("^\\d{8}$")

    /** 行内任意位置的日期（用于「DATE: 2024-0-7」这类字段行）。
     * 与 LabelParser 同款容错：数字间允许 OCR 插入空白（2026-08-2 9）、
     * 全角分隔符（－·．）、中文年月日（2026年11月1日）。 */
    private val DATE_ANY = Regex(
        "(\\d(?:\\s*\\d){3}\\s*[-/. _－·．]\\s*\\d(?:\\s*\\d){0,1}\\s*[-/. _－·．]\\s*\\d(?:\\s*\\d){0,1}" +
            "|\\d{4}\\s*年\\s*\\d{1,2}\\s*月\\s*\\d{1,2}\\s*日)"
    )
    /** 行内年月（只有年月时补 01，如生产日期 2026-11 → 2026-11-01）。 */
    private val DATE_YM_ANY = Regex(
        "(\\d(?:\\s*\\d){3}\\s*[-/. _－·．]\\s*\\d(?:\\s*\\d){0,1}" +
            "|\\d{4}\\s*年\\s*\\d{1,2}\\s*月)" +
            "(?!\\s*[-/. _－·．]\\s*\\d)(?!\\s*\\d)"
    )

    /** 日期字段分隔符；数字间允许 OCR 插入空白（如 2026-08-2 9）。 */
    private val DATE_SEP_TOL = Regex("^\\s*(\\d(?:\\s*\\d){3})\\s*[-/. _－·．]\\s*(\\d(?:\\s*\\d){0,1})\\s*[-/. _－·．]\\s*(\\d(?:\\s*\\d){0,1})\\s*$")
    /** 中文日期：2026年11月1日（数字间同样允许 OCR 空白）。 */
    private val DATE_CN = Regex("^\\s*(\\d(?:\\s*\\d){3})\\s*年\\s*(\\d(?:\\s*\\d){0,1})\\s*月\\s*(\\d(?:\\s*\\d){0,1})\\s*日\\s*$")

    /** 箱号：16 位纯数字（如 1110054025000029，条码常带 -9 后缀，先剥掉再认）。 */
    private val BOX16 = Regex("^(\\d{16})(?:-\\d+)?$")

    /**
     * 11/12 位末尾 01（本厂标准料号）优先于 10 位；10 位订单号常被误认成物料。
     * 只有当前值还是 10 位（弱，可能是订单号）时，才让标准料号覆盖它；
     * 11/12 位之间先到先得，不互相顶掉。
     */
    private fun preferMaterial(current: String, candidate: String): String {
        if (current.isEmpty()) return candidate
        val candIsStd = candidate.length in 11..12 && candidate.all { it.isDigit() } && candidate.endsWith("01")
        return if (current.length == 10 && candIsStd) candidate else current
    }

    /**
     * 10 位与 12 位末尾 01 是否同一物料的两种写法（10 位补 01）。
     * 是则不算“两个物料编码”（双码判定用，避免把同一物料的两种写法拆成两码）。
     */
    private fun isPadPair(a: String, b: String): Boolean =
        (a.length == 10 && b == a + "01") || (b.length == 10 && a == b + "01")

    /**
     * 把各种写法的日期归一成 yyyyMMdd；不合法返回 null。
     * 与 LabelParser.normalizeDateCandidate 同款容错（单箱入库走 BoxParser，
     * 之前这里比 LabelParser 弱，中文年月日、数字间空格、全角分隔符、
     * 字母数字误认都会导致日期识别不出来）：
     *  - 20240907 / 2024-0-07 / 2024.9.7 / 2024/09/07 —— 月、日都允许 1 位
     *  - 2026年11月1日 / 2026年11月（年月补 01）
     *  - OCR 数字间空格（2026-08-2 9）—— 正则里数字间允许 \s*
     *  - OCR 字母数字误认：O/o→0、I/l/|→1、S/s→5、g→9、B→8
     *  - 全角分隔符 －·．
     * 只有年月（如 2026-11）时补 01 当 1 号；用真实日历校验（2 月 30 日无效）。
     */
    private fun normalizeDate(raw: String): String? {
        val value = raw.trim().trim('.', ',', ';', ':', '"', '\'', '(', ')', '[', ']')
            .replace('O', '0').replace('o', '0')
            .replace('I', '1').replace('l', '1').replace('|', '1')
            .replace('S', '5').replace('s', '5')
            .replace('g', '9')
            .replace('B', '8')
        // 8 位连写（数字间可能有 OCR 空白，如 2026 08 29）
        if (Regex("^\\d(?:\\s*\\d){7}$").matches(value)) {
            val digits = value.replace(Regex("\\s+"), "")
            return validDate(
                digits.substring(0, 4).toIntOrNull(),
                digits.substring(4, 6).toIntOrNull(),
                digits.substring(6, 8).toIntOrNull(),
            )
        }
        // 只有年月（如生产日期 2026-11 / 2026年11月）：后面补 01 当 1 号。
        val yearMonth = Regex("^(\\d{4})\\s*[-/. _－·．]\\s*(\\d{1,2})\\s*$").matchEntire(value)
            ?: Regex("^(\\d{4})\\s*年\\s*(\\d{1,2})\\s*月\\s*$").matchEntire(value)
        if (yearMonth != null) {
            return validDate(
                yearMonth.groupValues[1].toIntOrNull(),
                yearMonth.groupValues[2].toIntOrNull(),
                1,
            )
        }
        val separated = DATE_SEP_TOL.matchEntire(value) ?: DATE_CN.matchEntire(value) ?: return null
        val year = separated.groupValues[1].replace(Regex("\\s+"), "")
        val month = separated.groupValues[2].replace(Regex("\\s+"), "")
        val day = separated.groupValues[3].replace(Regex("\\s+"), "")
        return validDate(year.toIntOrNull(), month.toIntOrNull(), day.toIntOrNull())
    }

    /** 真实日历校验（2 月 30 日、13 月无效）；年份沿用旧范围 1900..2100。 */
    private fun validDate(y: Int?, m: Int?, d: Int?): String? {
        if (y == null || m == null || d == null) return null
        if (y !in 1900..2100) return null
        return try {
            java.time.LocalDate.of(y, m, d)
            "%04d%02d%02d".format(y, m, d)
        } catch (_: java.time.DateTimeException) {
            null
        }
    }

    fun parse(barcodes: List<String>, ocrText: String, lookup69: ((String) -> String?)? = null): BoxParseResult {
        // 相机水印剔除（2026-09-14 真实标签实测）：
        // 翻拍/截屏时 OCR 会把状态栏时间、日期、星期读进来（实测 `16:38 / 2026.09.11 /
        // 星期五`），那个"拍摄当天"的日期会抢在生产日期前面。判据：
        //  ① 单独的时间行（16:38）与星期行（星期五）直接丢弃；
        //  ② 日期行紧邻时间行或星期行（状态栏三行连排）→ 丢弃；
        //  ③ 点号日期（2026.09.11）且全文出现星期行 → 丢弃（两个 OCR 识别器合并后
        //     行序可能打乱，相邻判据失效时兜底）。
        // 标签真日期不受影响：PANTUM 整箱标签走"DATE/生产日期"字段行优先，
        // 碳粉盒小签是横杠/斜杠格式（2025-06-12、2022/5/17），8 位连写（20250814）同理。
        val ocrTextLines = ocrText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val isTimeLine = { l: String -> Regex("^\\d{1,2}:\\d{2}$").matches(l) }
        val isWeekLine = { l: String -> Regex("^星期[一二三四五六日天]$").matches(l) }
        val isDateLikeLine = { l: String ->
            DATE8.matcher(l).matches() || DATE_SEP.matcher(l).matches() ||
                Regex("^\\d{4}\\s*年\\s*\\d{1,2}\\s*月\\s*\\d{1,2}\\s*日$").matches(l)
        }
        val hasWeekLine = ocrTextLines.any { isWeekLine(it) }
        val ocrLines = ocrTextLines.filterIndexed { i, l ->
            if (isTimeLine(l) || isWeekLine(l)) false
            else if (isDateLikeLine(l)) {
                val prevIsTime = i > 0 && isTimeLine(ocrTextLines[i - 1])
                val nextIsWeek = i + 1 < ocrTextLines.size && isWeekLine(ocrTextLines[i + 1])
                val dottedAndWeek = Regex("^\\d{4}\\.\\d{2}\\.\\d{2}$").matches(l) && hasWeekLine
                !prevIsTime && !nextIsWeek && !dottedAndWeek
            } else true
        }

        var material = ""
        var ean = ""
        var materialFromEan69 = false
        // 双码判定用的分来源候选（见文末双码判定）：条码来的 SAP、OCR “SAP.” 行
        // 后面的印制 SAP、OCR 裸行 SAP。只在两个编码同时出现时才启用区分。
        var barcodeMaterial = ""
        var sapLineMaterial = ""
        var bareOcrMaterial = ""
        var box = ""
        var date = ""
        var model = ""
        val sns = mutableListOf<String>()
        // 记录哪些 SN 来自"OCR 兜底"（条码完全没扫出东西时的被迫选择）——
        // 这些值可靠性低，界面要标出来提醒人工核对。
        val ocrFallback = mutableSetOf<String>()
        val classified = mutableListOf<String>()

        // 第 1 轮条码：EAN13(69 开头 13 位) / 纯数字 SAP 物料号(10~12 位)
        // 16 位纯数字码是箱号（1110054025000029），不进物料也不进 SN。
        for (code in barcodes) {
            val c = code.trim()
            if (c.isEmpty()) continue
            classified.add(c)
            val box16 = BOX16.find(c)?.groupValues?.get(1)
            when {
                EAN13.matcher(c).matches() && ean.isEmpty() -> ean = c
                box16 != null && box.isEmpty() -> box = box16
                SAP_NUM.matcher(c).matches() -> {
                    material = preferMaterial(material, c)
                    barcodeMaterial = preferMaterial(barcodeMaterial, c)
                }
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
        // 订单号不是物料：10 位订单号常被误认成物料编码，含订单号的行整行跳过。
        // “SAP.” 字段名与值可能分行（OCR 列优先排版：字段名一列、值一列），
        // 字段名行本身没数字时，下一行裸数字就是印的 SAP（pendingSapLine）。
        var pendingSapLine = false
        for (line in ocrLines) {
            val l = line.trim()
            if (l.isEmpty()) continue
            if (l.contains("订单号")) continue
            val upper = l.uppercase()
            if (pendingSapLine) {
                pendingSapLine = false
                if (SAP_NUM.matcher(l).matches()) {
                    material = preferMaterial(material, l)
                    sapLineMaterial = preferMaterial(sapLineMaterial, l)
                    continue
                }
                // 下一行不是数字：标记作废，继续正常处理本行。
            }
            when {
                SAP_NUM.matcher(l).matches() -> {
                    material = preferMaterial(material, l)
                    bareOcrMaterial = preferMaterial(bareOcrMaterial, l)
                }
                upper.startsWith("SAP") || upper.startsWith("SAP.") -> {
                    val found = Regex("(\\d{10,12})").find(l)?.groupValues?.get(1)
                    if (found != null) {
                        material = preferMaterial(material, found)
                        sapLineMaterial = preferMaterial(sapLineMaterial, found)
                    } else {
                        // 字段名行（“SAP.”），值在下一行。
                        pendingSapLine = true
                    }
                }
                // 一行并排两个字段（OCR 合成一行，如 `3011211002   2025-09-17`）时
                // 整行匹配不上 → 按空白拆成段，逐段找 10~12 位 SAP 号。
                else -> {
                    for (part in l.split(Regex("\\s+")).map { it.trim() }.filter { it.isNotEmpty() }) {
                        if (SAP_NUM.matcher(part).matches()) {
                            material = preferMaterial(material, part)
                            bareOcrMaterial = preferMaterial(bareOcrMaterial, part)
                            break
                        }
                    }
                }
            }
        }

        // ---- 生产日期 ----
        // 先把所有候选收集齐，最后再挑，而不是"遇到第一个合法值就收工"。
        //
        // 为什么要这样：用户把标签对着屏幕截图/翻拍做测试时，OCR 会把**手机状态栏**
        // 也读进来（实测 OCR 开头混入了 `19:10` / `2026.09.11` / `星期五`），
        // 那个"今天"的日期会抢在标签真正的生产日期（`2024-0-07`）前面。
        //
        // 挑选策略：
        //  1. 带 DATE / 生产日期 / MFG 字段名的 → 最可信，直接用；
        //  2. 否则在裸日期里优先取"不等于今天"的那个（"今天"多半来自状态栏）；
        //  3. 若全部等于今天（确实当天生产），才用今天。
        val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
            .format(java.util.Date())
        val bareDates = mutableListOf<String>()
        for (line in ocrLines) {
            val l = line.trim()
            if (l.isEmpty()) continue
            val upper = l.uppercase()
            if (date.isEmpty() && (upper.startsWith("DATE") || upper.startsWith("MFG") ||
                        upper.startsWith("生产日期") || upper.startsWith("PD"))) {
                (DATE_ANY.find(l)?.groupValues?.get(1)
                    ?: DATE_YM_ANY.find(l)?.groupValues?.get(1))
                    ?.let { raw -> normalizeDate(raw)?.let { n -> date = n } }
            }
            // 裸日期行：先整行归一；整行不是日期时再行内提取 —— 一行并排两字段
            // （如 `3011211002   2025-09-17`）时日期只是其中一段，行内取出来即可。
            // 只收合法的日期，不会引入新值。年月（2026-11）也收，补 01 当 1 号。
            val nd = normalizeDate(l)
                ?: DATE_ANY.find(l)?.groupValues?.get(1)?.let { raw -> normalizeDate(raw) }
                ?: DATE_YM_ANY.find(l)?.groupValues?.get(1)?.let { raw -> normalizeDate(raw) }
            if (nd != null) bareDates.add(nd)
        }
        if (date.isEmpty()) {
            date = bareDates.firstOrNull { it != today }
                ?: bareDates.firstOrNull()
                ?: ""
        }

        // 型号：OCR 的排版不一定"字段名: 值"同行，甚至常常是**字段名一列、值一列**
        // （实测奔图标签的 OCR 输出顺序就是：
        //     物料编码 / 型号 / 数量 / QTY. / PANTUM / 日期 / DATE / 序列号 / SN …
        //     201071000501 / CTO-850HK / 9 PCS / 2025-09-17 / PA40359P10035246 …）
        // 所以"顺着字段名往下一行找值"是不可靠的。
        //
        // 改为**全局候选 + 排除已知**：把所有像型号的候选收集起来，逐一排除
        // 字段名、中文、已识别出的物料/箱号/SN/日期/数量，剩下的就是型号。
        // 这样不管 OCR 怎么排版都不会取错。
        if (model.isEmpty()) {
            val labelWords = setOf(
                "MODEL", "SN", "SAP", "SAP.", "QTY", "QTY.", "DATE", "DATE.", "PANTUM",
                "序列号", "物料编码", "物料", "型号", "数量", "日期", "中国制造", "MADE IN CHINA",
            )
            val known = mutableSetOf<String>()
            if (material.isNotEmpty()) known.add(material)
            if (box.isNotEmpty()) known.add(box)
            if (ean.isNotEmpty()) known.add(ean)
            // 条码扫出的值绝不可能是型号（图12：CS2NV00RJN 是 SN，不能当型号）——
            // 但型号识别发生在 SN 解析之前，先把全部条码值排除掉。
            for (b in barcodes) if (b.trim().isNotEmpty()) known.add(b.trim())
            known.addAll(sns)

            val modelPrefix = mutableListOf<String>()
            val candidates = ocrLines
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .mapNotNull { l ->
                    // 带 "Model:" 前缀的行（图6/8：`Model:M9105DN`）直接取冒号后的值，
                    // 并记录到 modelPrefix —— 这类字段行最可信，优先采用；
                    // 其余行保留整行走候选过滤。
                    val m = Regex("^Model\\s*[:：]\\s*([A-Za-z0-9][A-Za-z0-9\\-]*)$", RegexOption.IGNORE_CASE)
                        .find(l)
                    if (m != null) {
                        m.groupValues[1].also { modelPrefix.add(it) }
                    } else l
                }
                .filter { l ->
                    val up = l.uppercase()
                    up !in labelWords &&
                        // 不含中文（型号是纯拉丁）
                        l.none { it.code in 0x4E00..0x9FFF } &&
                        l.length in 2..40 &&
                        l.all { it.isLetterOrDigit() || it == '-' || it == '.' } &&
                        l.any { it.isLetter() } &&
                        l !in known &&
                        // 排除数量（如 "9 PCS"）与日期（如 "2025-09-17"）
                        !Regex("^\\d+\\s*(?i)(PCS|PC|EA|个)?$").matches(l) &&
                        !DATE_SEP.matcher(l).matches() &&
                        !DATE8.matcher(l).matches()
                }
            // 型号优先级：Model: 字段行 > 带连字符的（型号惯例 CTO-850HK）> 首个候选
            model = modelPrefix.firstOrNull()
                ?: candidates.firstOrNull { it.contains('-') }
                ?: candidates.firstOrNull()
                ?: ""
        }

        // 第 3 轮条码：混合码区分 SN / 箱号（material 已定，才能判前缀）。
        // 箱号与 SN 规则明显不一致（SN=物料前缀+后缀；箱号=独立格式的码）——
        // 以物料开头的混合码 → SN；独立混合码 → 箱号候选（可人工改）。
        // ⚠️ 二维码可能是多值（逗号分隔的多个 SN，如 PANTUM 箱标签 QR：
        //    "SN1,SN2,...,SN32"）→ 先拆分再分类。
        val allMixedCodes = barcodes.map { it.trim() }.filter { c ->
            c.isNotEmpty() && c.any { it.isLetter() } &&
                !EAN13.matcher(c).matches() && !SAP_NUM.matcher(c).matches()
        }
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

            // 【关键修复】不再用"是否以物料编码开头"区分 SN 与箱号。
            // 真实标签上 SN 一律不以物料开头，例如：
            //   物料 201121001601 / SN SCAG2529704B3
            //   物料 303020000401 / SN ACP5P81000050
            //   物料 3011231031   / SN CV7DV0007F
            // 旧逻辑下第一个 SN 会被当成箱号、真正的箱号被丢掉、SN 少一个 ——
            // 这正是"取序列号取不对"的根因。
            // 改为按**格式特征**判定：箱号是 CA/PA 开头且明显更长的独立码。
            // ⚠️ 单台整箱标签（PANTUM 表格式：整箱/单台机器）只有一个 CA/PA 码、
            //    它就是序列号（"箱号=SN"，用户原话）——该码既要当箱号、也要进 SN
            //    列表，否则 UI 显示"序列号 0 个"、保存被拦。判断"唯一"要数**全部
            //    混合码**（含 SCAG 等多 SN），不能只看 CA/PA 数量（一箱 8 台标签：
            //    CA + 8×SN 是 9 个混合码，CA 只当箱号，SN 一个不少）。
            val looksLikeBoxCode = c.length >= 14 &&
                (c.startsWith("CA", ignoreCase = true) || c.startsWith("PA", ignoreCase = true))
            when {
                looksLikeBoxCode && box.isEmpty() -> {
                    box = c
                    if (allMixedCodes.size == 1 && c !in sns) sns.add(c)
                }
                looksLikeBoxCode && box == c -> { /* 同值重复，忽略 */ }
                else -> if (c != material && c != box && c !in sns) sns.add(c)
            }
        }

        // 序列号兜底：只有条码通道没给出 SN 时，才从 OCR 行里找。
        //
        // ⚠️ 这里曾经只要求「6~40 位、全字母数字、含字母」，判据太松，
        // 结果把公司名/地址词（PANTUM、Mijdrecht、Netherlands、ECIREP…）
        // 全塞进了序列号列表 —— 用户实测标签（PANTUM 荷兰外箱）就是这么发现的：
        // "物料编码和序列号都没识别出来，它把那些生产地址、中文内容都丢到序列号里去了"。
        //
        // SN 的真实特征是「**字母与数字混排**」，纯英文单词从来不是 SN，
        // 所以这里加三条硬门槛：
        //  ① 必须含数字（PANTUM / Mijdrecht 这类纯单词直接出局）；
        //  ② 必须"全字母数字"（含空格、连字符、点的一律排除 —— 那些是型号/地址）；
        //  ③ 不是字段名（MODEL / SN / QTY / DATE / PANTUM …）。
        // 这样 CS1RVO09B4 / SCAG2529704B3 / PA40359P10035246 能留下，
        // 而地址、公司名、日期、数量都会被挡住。
        if (sns.isEmpty()) {
            val fieldWords = setOf(
                "MODEL", "SERIAL", "SN", "SAP", "SAP.", "QTY", "QTY.", "DATE", "DATE.",
                "PANTUM", "PCS", "EA", "NO", "NO.", "MADE", "IN", "CHINA",
                "ADDRESS", "WAREHOUSE", "VAT", "EMAIL", "TEL", "FAX",
                "COMPATIBLE", "WITH", "POWER", "MODEL.", "PANTUM.",
            )
            val known = mutableSetOf<String>()
            for (v in listOf(material, box, ean, model)) if (v.isNotEmpty()) known.add(v)
            known.addAll(sns)
            for (line in ocrLines) {
                // OCR 常把标点/字段名残渣粘在值前面（实测行是 `: CS1RVO09B4`），
                // 不剥掉的话"全字母数字"这条就把它挡在外面，真 SN 反而丢了。
                var l = line.trim()
                while (l.isNotEmpty() && !l[0].isLetterOrDigit()) l = l.substring(1).trim()
                if (l.length !in 6..40) continue
                if (!l.all { it.isLetterOrDigit() }) continue  // 有空格/连字符/点 → 型号或地址
                if (!l.any { it.isLetter() }) continue
                if (!l.any { it.isDigit() }) continue          // ← 关键：SN 必含数字
                if (l.uppercase() in fieldWords) continue
                if (l in known) continue
                if (EAN13.matcher(l).matches()) continue
                sns.add(l)
                // 标记来源：这条是 OCR 兜底推定的，可靠性低于条码扫出的值
                ocrFallback.add(l)
            }
        }

        // 【业务规则】单台机器标签：箱号就是序列号。
        //
        // 用户原话："对于这种单台的机器来说，它的箱号就是序列号。"
        // （与他先前说的"打印机一箱一台、粉盒一箱多台"一致）
        //
        // 只在**确实像单台**时才套用，三个条件缺一不可：
        //  ① SN 恰好 1 个；
        //  ② 标签上没有独立印制的箱号（box 为空）；
        //  ③ 这个 SN **不是**从集成码拆出来的 —— 出现逗号/分号分隔的多值码
        //     说明是"一箱多台"，那种箱子箱号 ≠ SN，套上去就是错的。
        val hasIntegratedCode = barcodes.any {
            it.contains(',') || it.contains(';') || it.contains('，') || it.contains('；')
        }
        var boxFromSn = false
        if (box.isEmpty() && sns.size == 1 && !hasIntegratedCode) {
            box = sns.first()
            boxFromSn = true
        }

        // ---- 双物料编码（2026-11-17 奔图外箱标签）----
        // 只有两个编码同时出现才区分；单个编码走老路（material 保持上面的顺序逻辑结果）。
        // 判定只看来源，不看先后顺序：
        //  ① 条码来的 SAP（贴纸码）+ OCR “SAP.” 行后面的印制 SAP，同时出现且不同值
        //     → D 列 = 贴纸码，第二编码 = 印制 SAP；
        //  ② 贴纸条码没扫出来时，OCR 裸行读到的 11/12 位 SAP（贴纸上的印刷数字）+
        //     “SAP.” 行印制 SAP，同时出现且不同值 → 同样区分（裸行 10 位的不算，
        //     那更可能是订单号）。
        // 同一物料的两种写法（10 位与补 01 后的 12 位）不算两个编码。
        // 69 反查只在物料为空时才补位（见上），绝不能压过贴纸码：双码成立时
        // materialFromEan69 强制清掉，D 列以来源判定为准。
        var secondMaterial = ""
        val dualByBarcode = barcodeMaterial.isNotEmpty() && sapLineMaterial.isNotEmpty() &&
            barcodeMaterial != sapLineMaterial && !isPadPair(barcodeMaterial, sapLineMaterial)
        val dualByOcr = !dualByBarcode && barcodeMaterial.isEmpty() &&
            bareOcrMaterial.length in 11..12 && sapLineMaterial.isNotEmpty() &&
            bareOcrMaterial != sapLineMaterial && !isPadPair(bareOcrMaterial, sapLineMaterial)
        if (dualByBarcode) {
            material = barcodeMaterial
            secondMaterial = sapLineMaterial
            materialFromEan69 = false
        } else if (dualByOcr) {
            material = bareOcrMaterial
            secondMaterial = sapLineMaterial
            materialFromEan69 = false
        }

        // 物料编码统一补位：10 位 SAP 号 → 补 01 成 12 位（与 LabelParser 一致，
        // 用户既定规则"SAP 物料 10 位导出补 01"；69 反查回来的已是规范值不受影响）。
        // 第二编码保持印制原值，不补位（它是备查的原始值，补了反而失真）。
        if (material.length == 10) material += "01"

        return BoxParseResult(
            materialCode = material,
            secondMaterialCode = secondMaterial,
            boxCode = box,
            productionDate = date,
            model = model,
            ean69 = ean,
            materialFromEan69 = materialFromEan69,
            serialNumbers = sns,
            ocrFallbackSns = ocrFallback,
            allBarcodes = classified,
            boxFromSn = boxFromSn,
        )
    }
}
