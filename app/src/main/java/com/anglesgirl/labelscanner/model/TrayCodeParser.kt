package com.anglesgirl.labelscanner.model

/**
 * Offline candidate extraction for pallet labels. SSCC-like 18–22 digit codes are
 * preferred, while every barcode remains available when the image contains several.
 * 托盘号就是 TP 开头后面 8 个数字（如 TP12345678），同样是标准托盘码。
 */
object TrayCodeParser {
    private val ssccPattern = Regex("^\\d{18,22}$")
    /** 托盘号：TP 开头后面 8 个数字。 */
    private val tpPattern = Regex("^(?i)TP\\d{8}$")
    private val labeledTrayCode = Regex("(?i)(?:栈板|托盘|卡板|SSCC|pallet).{0,32}?([A-Za-z0-9]{10,24})")

    fun candidates(barcodes: List<String>, ocrText: String): List<String> {
        val barcodeValues = barcodes.map(String::trim).filter(String::isNotBlank)
        val ocrValues = buildList {
            for (line in ocrText.lines()) {
                val compact = line.filterNot(Char::isWhitespace)
                if (ssccPattern.matches(compact)) add(compact)
                if (tpPattern.matches(compact)) add(compact.uppercase())
                labeledTrayCode.find(line)?.groupValues?.getOrNull(1)?.let { add(it) }
            }
        }
        return (barcodeValues + ocrValues).distinct()
    }

    /** Return a code only when a single SSCC/TP stands out, or there is only one candidate. */
    fun preferred(candidates: List<String>): String? {
        val distinct = candidates.distinct()
        val ssccCandidates = distinct.filter(ssccPattern::matches)
        val tpCandidates = distinct.filter(tpPattern::matches)
        val stdCandidates = (ssccCandidates + tpCandidates).distinct()
        return when {
            stdCandidates.size == 1 -> stdCandidates.single()
            stdCandidates.isEmpty() && distinct.size == 1 -> distinct.single()
            else -> null
        }
    }

    /** Keep all scanned values but show likely pallet SSCCs/TP first in a chooser. */
    fun prioritize(candidates: List<String>): List<String> =
        candidates.distinct().sortedBy {
            if (ssccPattern.matches(it) || tpPattern.matches(it)) 0 else 1
        }
}
