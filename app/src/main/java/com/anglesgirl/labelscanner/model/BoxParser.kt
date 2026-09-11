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

    /** 行内任意位置的日期（用于「DATE: 2024-0-7」这类字段行）。 */
    private val DATE_ANY = Regex("(\\d{4}[-/. ]\\d{1,2}[-/. ]\\d{1,2})")

    /**
     * 把各种写法的日期归一成 yyyyMMdd；不合法返回 null。
     * 支持 20240907 / 2024-0-07 / 2024.9.7 / 2024/09/07 —— 月、日都允许 1 位。
     */
    private fun normalizeDate(raw: String): String? {
        val s = raw.trim().trim('.', ',', ';', ':', '"', '\'', '(', ')', '[', ']')
        if (s.length == 8 && s.all { it.isDigit() }) {
            val y = s.substring(0, 4).toIntOrNull() ?: return null
            val m = s.substring(4, 6).toIntOrNull() ?: return null
            val d = s.substring(6, 8).toIntOrNull() ?: return null
            return if (y in 1900..2100 && m in 1..12 && d in 1..31) s else null
        }
        val g = Regex("^(\\d{4})[-/. ](\\d{1,2})[-/. ](\\d{1,2})$").find(s) ?: return null
        val y = g.groupValues[1].toIntOrNull() ?: return null
        val m = g.groupValues[2].toIntOrNull() ?: return null
        val d = g.groupValues[3].toIntOrNull() ?: return null
        if (y !in 1900..2100 || m !in 1..12 || d !in 1..31) return null
        return "%04d%02d%02d".format(y, m, d)
    }

    fun parse(barcodes: List<String>, ocrText: String, lookup69: ((String) -> String?)? = null): BoxParseResult {
        var material = ""
        var ean = ""
        var materialFromEan69 = false
        var box = ""
        var date = ""
        var model = ""
        val sns = mutableListOf<String>()
        val classified = mutableListOf<String>()

        // 第 1 轮条码：EAN13(69 开头 13 位) / 纯数字 SAP 物料号(10~12 位)
        for (code in barcodes) {
            val c = code.trim()
            if (c.isEmpty()) continue
            classified.add(c)
            when {
                EAN13.matcher(c).matches() && ean.isEmpty() -> ean = c
                SAP_NUM.matcher(c).matches() && material.isEmpty() -> material = c
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
                material.isEmpty() && SAP_NUM.matcher(l).matches() -> material = l
                material.isEmpty() && (upper.startsWith("SAP") || upper.startsWith("SAP.")) -> {
                    Regex("(\\d{10,12})").find(l)?.groupValues?.get(1)?.let { material = it }
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
        for (line in ocrText.lines()) {
            val l = line.trim()
            if (l.isEmpty()) continue
            val upper = l.uppercase()
            if (date.isEmpty() && (upper.startsWith("DATE") || upper.startsWith("MFG") ||
                        upper.startsWith("生产日期") || upper.startsWith("PD"))) {
                DATE_ANY.find(l)?.groupValues?.get(1)
                    ?.let { raw -> normalizeDate(raw)?.let { n -> date = n } }
            }
            normalizeDate(l)?.let { bareDates.add(it) }
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
            known.addAll(sns)

            val candidates = ocrText.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
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
            // 优先取带连字符的（型号惯例，如 CTO-850HK / M9105DN-xx），否则取第一个候选
            model = candidates.firstOrNull { it.contains('-') }
                ?: candidates.firstOrNull()
                ?: ""
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

            // 【关键修复】不再用"是否以物料编码开头"区分 SN 与箱号。
            // 真实标签上 SN 一律不以物料开头，例如：
            //   物料 201121001601 / SN SCAG2529704B3
            //   物料 303020000401 / SN ACP5P81000050
            //   物料 3011231031   / SN CV7DV0007F
            // 旧逻辑下第一个 SN 会被当成箱号、真正的箱号被丢掉、SN 少一个 ——
            // 这正是"取序列号取不对"的根因。
            // 改为按**格式特征**判定：箱号是 CA/PA 开头且明显更长的独立码。
            val looksLikeBoxCode = c.length >= 14 &&
                (c.startsWith("CA", ignoreCase = true) || c.startsWith("PA", ignoreCase = true))
            when {
                looksLikeBoxCode && box.isEmpty() -> box = c
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
            for (line in ocrText.lines()) {
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
