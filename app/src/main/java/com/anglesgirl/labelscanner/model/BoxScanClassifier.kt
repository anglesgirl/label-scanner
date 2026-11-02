package com.anglesgirl.labelscanner.model

/**
 * 整箱直播扫描的累计状态与分类逻辑（单箱入库「扫整箱」用，纯逻辑、无 Android 依赖）。
 *
 * 用户定下的规则（2026-11-02 CTL-2300HK 标签，9 个 SN 只出 2 个之后重想）：
 * - QTY 就是数量：标签写 9，就是 9 个 SN，够数才算完。
 * - SAP 是物料编码，简单识别（纯数字码）。
 * - 箱号和序列号都是条码，但明显不一样：长得差不多的一批是序列号，
 *   不一样的那一个是箱号。
 * - 序列号没够数就不自动关闭，继续移动相机把全部序列号识别完。
 *
 * 分类顺序（与 BoxParser 一致）：EAN69 → 物料 → SN 批 → 箱号。
 * 先定下的值不再改（后到的帧可能 OCR 糊，不能把已定的物料/QTY/箱号翻掉）。
 */
data class BoxScanState(
    val materialCode: String = "",
    val qty: Int = 0,
    val boxCode: String = "",
    val productionDate: String = "",
    val serialNumbers: List<String> = emptyList(),
    val allCodes: List<String> = emptyList(),
) {
    val targetKnown: Boolean get() = qty > 0
    val isComplete: Boolean get() = targetKnown && serialNumbers.size >= qty
}

object BoxScanClassifier {

    private val EAN13 = Regex("^69\\d{11}$")
    private val SAP_NUM = Regex("^\\d{10,12}$")
    private val SAP_LABELED = Regex("(?i)SAP[^0-9]{0,3}(\\d{10,12})")
    private val QTY_INLINE = Regex("(?i)QTY[^0-9]{0,3}(\\d{1,3})")
    private val QTY_BARE = Regex("(?i)^QTY\\.?$")
    private val QTY_VALUE = Regex("^(\\d{1,3})\\s*(PCS|PC)?\\.?$")
    private val DATE_SEP = Regex("(\\d{4})[-/. ](\\d{1,2})[-/. ](\\d{1,2})")
    private val DATE8 = Regex("^\\d{8}$")

    /**
     * 累计一步：新条码 + 新 OCR 行并入状态，返回新状态。
     * 条码与 OCR 行分开传：OCR 行只用来定物料/QTY/日期，不进条码集合。
     */
    fun accumulate(
        state: BoxScanState,
        newBarcodes: Collection<String>,
        ocrLines: List<String>,
    ): BoxScanState {
        var material = state.materialCode
        var qty = state.qty
        var date = state.productionDate

        // OCR 行：定物料 / QTY / 日期（先到先得）。
        // 物料只认两种：整行纯数字，或 "SAP" 后跟的数字 —— SN 条码下方的人读数
        // （如 201220700301V005GN）里截一段 12 位数字不能算物料，UPC 13 位
        // 的前 12 位也不能算，所以不用 find 直接取数字串。
        for (i in ocrLines.indices) {
            val line = ocrLines[i].trim()
            if (line.isEmpty()) continue
            if (qty <= 0) {
                QTY_INLINE.find(line)?.groupValues?.get(1)?.toIntOrNull()
                    ?.takeIf { it in 1..999 }?.let { qty = it }
                if (qty <= 0 && QTY_BARE.matches(line) && i + 1 < ocrLines.size) {
                    QTY_VALUE.find(ocrLines[i + 1].trim())?.groupValues?.get(1)?.toIntOrNull()
                        ?.takeIf { it in 1..999 }?.let { qty = it }
                }
            }
            if (material.isEmpty()) {
                if (SAP_NUM.matches(line)) material = line
                else SAP_LABELED.find(line)?.groupValues?.get(1)?.let { material = it }
            }
            if (date.isEmpty()) {
                DATE_SEP.find(line)?.let { g ->
                    normalizeDate(
                        g.groupValues[1].toIntOrNull(),
                        g.groupValues[2].toIntOrNull(),
                        g.groupValues[3].toIntOrNull(),
                    )?.let { date = it }
                } ?: run {
                    if (DATE8.matches(line)) {
                        normalizeDate(
                            line.substring(0, 4).toIntOrNull(),
                            line.substring(4, 6).toIntOrNull(),
                            line.substring(6, 8).toIntOrNull(),
                        )?.let { date = it }
                    }
                }
            }
        }

        // 条码集合：去重、保首次出现顺序。
        val codes = (state.allCodes + newBarcodes.map { it.trim() }.filter { it.isNotEmpty() }).distinct()
        // 条码里的纯数字码也是物料（BoxParser 规则 2）。
        if (material.isEmpty()) {
            material = codes.firstOrNull { SAP_NUM.matches(it) } ?: ""
        }
        // 混合码：排除 EAN69 与物料码，剩下的才参与 SN/箱号之分。
        val mixed = codes.filter { !EAN13.matches(it) && !SAP_NUM.matches(it) }
        val sns = when {
            material.isNotEmpty() -> {
                val prefixed = mixed.filter { it.startsWith(material) }
                if (prefixed.isNotEmpty()) prefixed else clusterSn(mixed)
            }
            else -> clusterSn(mixed)
        }
        // 箱号：第一个不属于 SN 批的混合码，定下后不再换。
        val box = state.boxCode.ifEmpty { mixed.firstOrNull { it !in sns } ?: "" }

        return BoxScanState(
            materialCode = material,
            qty = qty,
            boxCode = box,
            productionDate = date,
            serialNumbers = sns,
            allCodes = codes,
        )
    }

    /**
     * 无物料时的 SN 批判定：按前 10 位分组，最大的一批（≥2 个）就是序列号批。
     * 原理：同一箱的 SN 共享物料前缀（12 位 SAP 时前 10 位必然相同），
     * 而箱号（CA/TP/PA…）与 UPC 的前 10 位各不相同，落不到同一组。
     * 没有任何一组 ≥2 个时无法区分，返回空（箱号留空，等物料读到后再分）。
     */
    private fun clusterSn(mixed: List<String>): List<String> {
        if (mixed.isEmpty()) return emptyList()
        val groups = mixed.groupBy { it.take(10) }.values
        val batch = groups.maxByOrNull { it.size } ?: return emptyList()
        return if (batch.size >= 2) batch else emptyList()
    }

    private fun normalizeDate(y: Int?, m: Int?, d: Int?): String? {
        if (y == null || m == null || d == null) return null
        if (y !in 1900..2100 || m !in 1..12 || d !in 1..31) return null
        return "%04d%02d%02d".format(y, m, d)
    }
}
