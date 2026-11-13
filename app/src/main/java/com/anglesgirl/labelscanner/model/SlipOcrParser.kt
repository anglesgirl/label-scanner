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
    ) {
        val hasAny: Boolean get() = quantity != null || boxCount != null || materialCode != null
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

        return Result(quantity = quantity, boxCount = boxCount, materialCode = best)
    }
}
