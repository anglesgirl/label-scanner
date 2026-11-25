package com.anglesgirl.labelscanner.model

/**
 * 成品进仓单 OCR 解析：从 ML Kit 文本里读 数量 / 箱数 / 物料编码。
 *
 * 单据是表格（一般只有一行明细 + 合计行）。解析不依赖表格排版（ML Kit 的
 * 行切分不可靠），只靠三处强锚点：
 *  - 数量 / 箱数：明细行里的 "[数量] [箱数] PCS"（PCS 前最后两个数字，
 *    列序是 仓库 / 工厂 / 数量 / 箱数 / 单位，数量与箱数紧贴 PCS）；
 *    找不到时退到合计行 "合计: 288.000 2"（第一个数是总量，第二个数是总箱数）。
 *  - 物料编码：全文最长的数字串（≥10 位）；进仓单上生产单号 / 销售订单 /
 *    过账凭证都是 10 位，物料码 15 位是最长的。物料码在格子里会折行
 *    （"2110050012140" + 下行 "02"），13–14 位的断码 + 下行行首 1–3 位
 *    数字就拼起来。
 * OCR 常见误读（O↔0、S↔5、l/I↔1、Z↔2）在数字串里先归一化再认。
 * 全离线、纯规则；读不到就留空，不猜。
 */
object SlipOcrParser {

    data class Result(
        val quantity: Int? = null,
        val boxCount: Int? = null,
        val materialCode: String? = null,
        /** OCR 原文（给单号点选候选与数量核对用，不解析时为空）。 */
        val rawText: String = "",
    ) {
        val hasAny: Boolean get() = quantity != null || boxCount != null || materialCode != null
    }

    /** 物料编码归一化：10 位补 01 成 12 位（与台账/采集记录的 12 位形式对齐），其余原样。 */
    fun normalizeMaterial(code: String): String {
        val c = code.trim()
        return if (c.length == 10 && c.all { it.isDigit() }) c + "01" else c
    }

    /**
     * 单据编号候选：从 OCR 原文里抽全部“像单号”的串，返回给用户点选，不自动挑
     * （采集层不取舍，候选全量返回、去重；没想要的就手填）。
     * 优先“单号/单据号/凭证号…”标签后紧跟的值，其次是 ≥6 位字母数字串
     * （单号常带字母，如 RK20261125001）。物料编码本身排除掉。
     */
    fun slipNoCandidates(ocrText: String, excludeMaterial: String? = null): List<String> {
        val lines = ocrText.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return emptyList()
        val out = linkedSetOf<String>()
        val excluded = excludeMaterial?.let { normalizeMaterial(it) }.orEmpty()

        // ① 标签后的值：单号/单据号/申请单号/凭证号/单据编号/送货单号/退货单号/进仓单号/NO。
        val labels = listOf(
            "申请单号", "单据编号", "单据号", "送货单号", "退货单号", "进仓单号",
            "凭证号", "单号", "单据", "NO.", "No.", "NO", "No",
        )
        for (line in lines) {
            for (label in labels) {
                val idx = line.indexOf(label)
                if (idx < 0) continue
                val rest = line.substring(idx + label.length)
                    .trimStart(' ', ':', '：', '-', '—')
                val token = rest.split(Regex("[\\s,;，；:：。]+")).firstOrNull().orEmpty()
                    .trimEnd { !it.isLetterOrDigit() }
                if (token.length >= 3 && token.any { it.isDigit() }) out.add(token)
            }
        }
        // ② 兜底：≥6 位字母数字串（含数字），排除物料编码与纯 8 位日期。
        val tokenRe = Regex("[A-Za-z0-9][A-Za-z0-9\\-]{5,29}")
        for (line in lines) {
            for (m in tokenRe.findAll(line)) {
                val token = m.value
                if (!token.any { it.isDigit() }) continue
                if (token.length == 8 && token.all { it.isDigit() }) continue
                if (excluded.isNotEmpty() && normalizeMaterial(token) == excluded) continue
                out.add(token)
            }
        }
        return out.take(8)
    }

    // 数字串里的易混字母归一化。
    private fun normalizeDigits(raw: String): String = buildString(raw.length) {
        for (ch in raw) append(
            when (ch) {
                'O', 'o', 'Q', 'q' -> '0'
                'S', 's' -> '5'
                'l', 'I', 'i', '|' -> '1'
                'Z', 'z' -> '2'
                else -> ch
            },
        )
    }

    /**
     * 一段 token 是否“基本是数字”（数字占比 ≥80%，其余是易混字母）。
     * 是就归一化后返回纯数字串，否则 null。
     */
    private fun asDigitRun(token: String): String? {
        if (token.length < 8) return null
        var digits = 0
        for (ch in token) {
            if (ch.isDigit()) digits++
            else if (ch !in "OoQqSsLlIi|Zz") return null
        }
        if (digits * 5 < token.length * 4) return null
        val norm = normalizeDigits(token)
        return norm.takeIf { it.length >= 8 && it.all { c -> c.isDigit() } }
    }

    /** 从一行里按出现顺序抽全部“长数字串”（≥8 位，含易混字母归一化）。 */
    private fun digitRuns(line: String): List<String> =
        line.split(Regex("[\\s,;，；/]+")).mapNotNull { asDigitRun(it) }

    /** 从一段文本里按出现顺序抽全部整数（含 "000010" 这种前导零，取数值）。 */
    private fun intsIn(text: String): List<Int> =
        Regex("\\d+").findAll(text).mapNotNull { it.value.toIntOrNull() }.toList()

    fun parse(ocrText: String): Result {
        val lines = ocrText.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return Result()

        var quantity: Int? = null
        var boxCount: Int? = null

        // ---- 数量 / 箱数：先找明细行 "[数量] [箱数] PCS" ----
        val pcsIdx = lines.indexOfFirst { it.contains("PCS", ignoreCase = true) }
        if (pcsIdx >= 0) {
            // PCS 行及其上一行都可能含数字（明细行被 OCR 切成两行时，
            // "288 2" 在上一行、"PCS" 在下一行）。拼起来再取 PCS 前最后两个数。
            val pcsLine = lines[pcsIdx]
            val beforePcs = pcsLine.substring(0, pcsLine.indexOf("PCS", ignoreCase = true))
            var nums = intsIn(beforePcs)
            if (nums.size < 2 && pcsIdx > 0) {
                nums = intsIn(lines[pcsIdx - 1] + " " + beforePcs)
            }
            if (nums.size >= 2) {
                val boxes = nums[nums.size - 1]
                val qty = nums[nums.size - 2]
                if (qty in 1..999999 && boxes in 1..99999) {
                    quantity = qty
                    boxCount = boxes
                }
            }
        }

        // ---- 数量 / 箱数兜底：合计行 "合计: 288.000 2" ----
        if (quantity == null || boxCount == null) {
            val totalIdx = lines.indexOfFirst { it.contains("合计") }
            if (totalIdx >= 0) {
                // 合计行及其下一行都可能含数字（"合计:" 与数字可能分行）。
                val scope = lines.subList(totalIdx, minOf(totalIdx + 2, lines.size)).joinToString(" ")
                val nums = Regex("\\d+(?:\\.\\d+)?").findAll(scope)
                    .mapNotNull { it.value.toDoubleOrNull()?.toInt() }.toList()
                if (nums.size >= 2) {
                    if (quantity == null) quantity = nums[0].takeIf { it in 1..999999 }
                    if (boxCount == null) boxCount = nums[1].takeIf { it in 1..99999 }
                }
            }
        }

        // ---- 物料编码：最长的数字串（≥10 位），断码拼下行行首 ----
        // 有“物料编码”表头时只搜表头之后的行，否则搜全文。
        val headerIdx = lines.indexOfFirst { it.contains("物料编码") }
        val searchFrom = if (headerIdx >= 0) headerIdx + 1 else 0
        var best: String? = null
        var bestLine = -1
        for (i in searchFrom until lines.size) {
            for (run in digitRuns(lines[i])) {
                if (run.length >= 10 && (best == null || run.length > best.length)) {
                    best = run
                    bestLine = i
                }
            }
        }
        // 断码拼接：13–14 位的断码 + 下几行里行首 1–3 位纯数字。
        // 10/12 位是标准完整码长，不拼；13–14 位几乎一定是折行截断。
        // 明细行被 OCR 切开时 "PCS" 行会插在中间，所以往下找 3 行；
        // 行首数字所在行不能含长数字串（否则是新的一行明细，不是折行余部）。
        if (best != null && best.length in 13..14) {
            for (j in bestLine + 1 until minOf(bestLine + 4, lines.size)) {
                val cand = lines[j]
                val head = cand.split(Regex("[\\s,;，；/]+")).firstOrNull().orEmpty()
                if (head.length in 1..3 && head.all { it.isDigit() }) {
                    val hasLongRun = digitRuns(cand).any { it.length >= 10 }
                    if (!hasLongRun) {
                        val joined = best + head
                        if (joined.length in 14..17) { best = joined; break }
                    }
                }
            }
        }

        return Result(quantity = quantity, boxCount = boxCount, materialCode = best, rawText = ocrText)
    }

    // ================= 多行入库单（免费入库申请单） =================

    /** 入库单的一行明细。quantity 读不到时为 null（留空手填，不猜）。 */
    data class TableRow(
        val materialCode: String,
        val quantity: Int?,
    )

    /** 多行入库单解析结果。location/factory/date/department 读不到时为空串。 */
    data class TableResult(
        val rows: List<TableRow>,
        val location: String,
        val factory: String,
        val date: String,
        val department: String,
    ) {
        val hasAny: Boolean get() = rows.isNotEmpty()
    }

    /**
     * 解析多行入库单表格（免费入库申请单）：
     * 每行一个 10–12 位物料编码 + 该行 "PCS" 前最后一个整数是交货数量；
     * PCS 后紧跟的两个 4 位数是工厂、仓位；交货日期是 yyyy/M/d；退仓部门是
     * “×部××”字样。行可能被 OCR 切开，所以按物料编码切段、不依赖行对齐。
     * 数量读不到就留 null，不猜；描述里的 2023 这类数字不会被当成数量，
     * 因为只取 PCS 前最后一个整数。全部行都返回，不做取舍。
     */
    fun parseInboundTable(ocrText: String): TableResult {
        val lines = ocrText.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return TableResult(emptyList(), "", "", "", "")

        val full = lines.joinToString("\n")
        // 找全部 10–12 位数字串（物料编码），含易混字母归一化；用边界避免截断更长数字。
        val codeRe = Regex("(?<![0-9A-Za-z])[0-9OoQqSsLlIi|Zz]{10,12}(?![0-9A-Za-z])")
        data class CodeHit(val code: String, val start: Int, var end: Int)
        val hits = mutableListOf<CodeHit>()
        for (m in codeRe.findAll(full)) {
            val norm = normalizeDigits(m.value)
            if (norm.length in 10..12 && norm.all { it.isDigit() }) {
                hits.add(CodeHit(norm, m.range.first, m.range.last + 1))
            }
        }
        // 断码拼接：10–11 位的码 + 紧跟的 1–2 位数字（后面不是数字）拼成 12 位。
        // 单据上码后紧跟的是描述（字母开头），不会误拼数量。
        for (idx in hits.indices) {
            val hit = hits[idx]
            if (hit.code.length in 10..11) {
                val rest = full.substring(hit.end)
                val join = Regex("^\\s*(\\d{1,2})(?!\\d)").find(rest)
                if (join != null && hit.code.length + join.groupValues[1].length == 12) {
                    val joined = hit.code + join.groupValues[1]
                    hits[idx] = hit.copy(code = joined, end = hit.end + join.range.last + 1)
                }
            }
        }
        if (hits.isEmpty()) return TableResult(emptyList(), "", "", "", "")

        val rows = mutableListOf<TableRow>()
        val factories = mutableListOf<String>()
        val locations = mutableListOf<String>()
        for (i in hits.indices) {
            val hit = hits[i]
            val segEnd = if (i + 1 < hits.size) hits[i + 1].start else full.length
            val seg = full.substring(hit.end, segEnd)
            // 数量：该段里 PCS 前最后一个整数。
            var qty: Int? = null
            val pcsIdx = seg.indexOf("PCS", ignoreCase = true)
            if (pcsIdx >= 0) {
                val before = seg.substring(0, pcsIdx)
                val nums = Regex("\\d+").findAll(before).mapNotNull { it.value.toIntOrNull() }.toList()
                qty = nums.lastOrNull()?.takeIf { it in 1..999999 }
                // PCS 后两个 4 位数：工厂、仓位。
                val after = seg.substring(pcsIdx + 3)
                val afterNums = Regex("\\d+").findAll(after).map { it.value }.toList()
                if (afterNums.size >= 2) {
                    if (afterNums[0].length == 4) factories.add(afterNums[0])
                    if (afterNums[1].length == 4) locations.add(afterNums[1])
                }
            }
            rows.add(TableRow(hit.code, qty))
        }

        // 交货日期：全文第一个 yyyy/M/d。
        val date = Regex("(\\d{4})/(\\d{1,2})/(\\d{1,2})").find(full)?.value.orEmpty()
        // 退仓部门：第一个“×部××”字样（如 三部OQC）。部后必须跟字母，
        // 这样“保存部门”“退仓部门”这种表头（部后是“门”）不会被误认。
        val dept = Regex("[\\u4e00-\\u9fa5]{1,4}部[A-Za-z]{1,6}").find(full)?.value.orEmpty()
        // 工厂/仓位取出现次数最多的（各行一致，取众数抗个别误读）。
        val factory = factories.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key.orEmpty()
        val location = locations.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key.orEmpty()

        return TableResult(rows, location, factory, date, dept)
    }
}
