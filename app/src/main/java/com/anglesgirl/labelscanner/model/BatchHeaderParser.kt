package com.anglesgirl.labelscanner.model

import java.time.DateTimeException
import java.time.LocalDate

/** 双页清单公共字段的保守 OCR 规则；物料只认终端料号，防止把型号/订单号写入。 */
object BatchHeaderParser {
    private val terminalMaterialLabels = listOf(
        Regex("终端物料(?:编码|编号)"),
        Regex("(?i)\\bTerminal\\s+Material\\s+(?:Number|Code)\\b"),
    )
    private val materialNumberToken = Regex("(?<!\\d)\\d(?:\\s*\\d){9,11}(?!\\d)")
    private val unlabeledTwelveDigitToken = Regex("(?<!\\d)\\d(?:\\s*\\d){11}(?!\\d)")
    private val dateToken = Regex(
        "(?<!\\d)(?:\\d{4}\\s*[-/.]\\s*\\d{1,2}\\s*[-/.]\\s*\\d{1,2}|\\d{1,2}\\s*[-/.]\\s*\\d{1,2}\\s*[-/.]\\s*\\d{4}|\\d(?:\\s*\\d){7})(?!\\d)"
    )

    /** 仅接受终端料号的 10/12 位数字；型号/Part Number 不会被当成物料编码。 */
    fun extractMaterialCode(ocrText: String): String? {
        val lines = ocrText.lines().map(String::trim)
        for (pattern in terminalMaterialLabels) {
            for (index in lines.indices) {
                val match = pattern.find(lines[index]) ?: continue
                val inline = lines[index].substring(match.range.last + 1)
                extractLabeledMaterialNumber(inline)?.let { return it }
                extractLabeledMaterialNumber(lines.getOrNull(index + 1).orEmpty())?.let { return it }
            }
        }
        // Some OCR frames read the 12-digit value but miss its Chinese label. Recover it only
        // when the page has exactly one distinct 12-digit candidate; never promote 10-digit POs.
        val unlabeledCandidates = lines.flatMap { line ->
            unlabeledTwelveDigitToken.findAll(line).map { it.value.filter(Char::isDigit) }.toList()
        }.distinct()
        return unlabeledCandidates.singleOrNull()
    }

    private fun extractLabeledMaterialNumber(text: String): String? =
        materialNumberToken.findAll(text).firstNotNullOfOrNull { match ->
            match.value.filter(Char::isDigit).takeIf { it.length == 10 || it.length == 12 }
        }

    /**
     * 生产/制造日期优先；若单据只有需求日期，则取需求日期；最后按 OCR 中首次出现的有效日期兜底。
     * 覆盖标签常见的 2026/09/19 与美国格式 09/19/2026。
     */
    fun extractProductionDate(ocrText: String): String? {
        val lines = ocrText.lines().map(String::trim)
        fun firstNearLabel(predicate: (String) -> Boolean): String? {
            for (index in lines.indices) {
                if (!predicate(lines[index])) continue
                for (near in index..minOf(index + 2, lines.lastIndex)) {
                    extractDate(lines[near])?.let { return it }
                }
            }
            return null
        }
        firstNearLabel { line ->
            line.contains("生产日期") || line.contains("制造日期") ||
                Regex("(?i)manufacturing\\s*date|production\\s*date|\\bmfg\\b|\\bmfd\\b").containsMatchIn(line)
        }?.let { return it }
        firstNearLabel { line ->
            line.contains("需求日期") || Regex("(?i)\\bdemand\\s*date\\b").containsMatchIn(line)
        }?.let { return it }
        for (line in lines) extractDate(line)?.let { return it }
        return null
    }

    /** 预选时仅识别明显的标签辅助码；所有码仍留在 UI 中供人工勾选，绝不静默删除。 */
    fun isLikelyHeaderCode(code: String, materialCode: String): Boolean {
        val raw = code.trim()
        if (raw.isEmpty()) return true
        when (LabelParser.classify(raw)?.first) {
            "date", "ean", "material10", "material12" -> return true
        }
        if (raw.length <= 6 && raw.all(Char::isDigit)) return true // 如 QTY 条码 144
        if (raw.length == 8 && raw.all(Char::isDigit)) {
            val monthFirstDate = "${raw.substring(0, 2)}/${raw.substring(2, 4)}/${raw.substring(4, 8)}"
            if (normalizeDateToken(monthFirstDate) != null) return true
        }
        fun compact(value: String): String = value.uppercase().filter(Char::isLetterOrDigit)
        val normalizedMaterial = compact(materialCode)
        val normalizedCode = compact(raw).removePrefix("P")
        if (normalizedMaterial.isNotEmpty() && normalizedCode == normalizedMaterial) return true
        val digitsAndLetters = compact(raw)
        if (Regex("^(?:00)?\\d{18,22}$").matches(digitsAndLetters)) return true // SCC / SSCC 包装码
        if (Regex("^11\\d{8}$").matches(digitsAndLetters)) return true // GS1 生产日期字段
        if (Regex("^37\\d{1,6}$").matches(digitsAndLetters)) return true // GS1 数量字段
        return false
    }

    private fun extractDate(line: String): String? {
        for (match in dateToken.findAll(line)) {
            normalizeDateToken(match.value)?.let { return it }
        }
        return null
    }

    private fun normalizeDateToken(raw: String): String? {
        val value = raw.replace(Regex("\\s+"), "")
        val y: Int
        val m: Int
        val d: Int
        val yearFirst = Regex("^(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})$").matchEntire(value)
        val monthFirst = Regex("^(\\d{1,2})[-/.](\\d{1,2})[-/.](\\d{4})$").matchEntire(value)
        val compact = Regex("^\\d{8}$").matches(value)
        when {
            yearFirst != null -> {
                y = yearFirst.groupValues[1].toInt()
                m = yearFirst.groupValues[2].toInt()
                d = yearFirst.groupValues[3].toInt()
            }
            monthFirst != null -> {
                m = monthFirst.groupValues[1].toInt()
                d = monthFirst.groupValues[2].toInt()
                y = monthFirst.groupValues[3].toInt()
            }
            compact -> {
                y = value.substring(0, 4).toInt()
                m = value.substring(4, 6).toInt()
                d = value.substring(6, 8).toInt()
            }
            else -> return null
        }
        if (y !in 2000..2050) return null
        return try {
            LocalDate.of(y, m, d)
            "%04d%02d%02d".format(y, m, d)
        } catch (_: DateTimeException) {
            null
        }
    }
}
