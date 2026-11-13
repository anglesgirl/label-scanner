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
    /** 箱号是否由单台机器规则（箱号 = 序列号）推得，见 accumulate。 */
    val boxFromSn: Boolean = false,
) {
    val targetKnown: Boolean get() = qty > 0
    val isComplete: Boolean get() = targetKnown && serialNumbers.size >= qty
}

object BoxScanClassifier {

    private val EAN13 = Regex("^69\\d{11}$")
    private val SAP_NUM = Regex("^\\d{10,12}$")
    private val SAP_LABELED = Regex("(?i)SAP[^0-9]{0,3}(\\d{10,12})")
    /**
     * QTY 四档判定（2026-11-02 TL-413 标签教训；2026-11-13 PCS 后缀教训）：
     * 表格是两列，ML Kit 经常把标签列和值列拆成两组行——"QTY." 和 "10 PCS"
     * 未必相邻，只看下一行必然漏读（实测：列优先顺序下旧逻辑 QTY=0，
     * 导致 10 个 SN 已齐却显示"未读到 QTY"、不自动回）。
     * ① 同行 "QTY. 10 PCS"；② 全文有 QTY 标签 + 有 "N PCS" 值行（不要求相邻，
     * 大小写不敏感）；③ 裸 "QTY." 行后 6 行内找裸数字/PCS 值；
     * ④ 兜底：直接拿 PCS 定位——PCS 前面的数字就是数量，不要求先找到 QTY 标签
     * （用户 2026-11-13：QTY 标签本身经常认不出来，绕开它直接找 PCS 更稳）。
     * ① 的后瞻 (?![0-9A-Za-z]) 防 OCR 把 0 读成 O："QTY. 1O PCS" 不能
     * 误判成 1（错目标比没目标更糟：1 个就自动回了）。
     * 2026-11-13：数量后面跟 PCS 时①会漏——"QTY 10PCS" 里数字后紧跟 P，
     * 后瞻把 P 当字母拦掉。改法：数字后先消费可选单位（PCS/PC5/PC/EA，
     * PC5 是 PCS 被 OCR 读成 5 的常见误读），再做后瞻；"1O" 仍被拦住，
     * 因为 O 不是单位开头。
     */
    private val QTY_INLINE = Regex("(?i)QTY[^0-9]{0,3}(\\d{1,3})\\s*(?:PCS|PC5|PC|EA)?(?![0-9A-Za-z])")
    private val QTY_BARE = Regex("(?i)^QTY\\.?$")
    private val QTY_PCS_ANY = Regex("^(\\d{1,3})\\s*(?:PCS|PC5|PC|EA)\\.?\\s*$", RegexOption.IGNORE_CASE)
    private val QTY_VALUE = Regex("^(\\d{1,3})\\s*(PCS|PC5|PC|EA)?\\.?$")
    /** ④ 兜底：行内任意位置的 "N PCS"，数字前面不能是字母数字（防 "ABC10PCS" 这种码内数字）。 */
    private val QTY_PCS_BEFORE = Regex("(?<![0-9A-Za-z])(\\d{1,3})\\s*(?:PCS|PC5|PC|EA)(?![0-9A-Za-z])")
    private val DATE_SEP = Regex("(\\d{4})[-/. ](\\d{1,2})[-/. ](\\d{1,2})")
    private val DATE8 = Regex("^\\d{8}$")

    private fun parseQty(lines: List<String>): Int {
        for (line in lines) {
            val v = QTY_INLINE.find(line)?.groupValues?.get(1)?.toIntOrNull()
            if (v != null && v in 1..999) return v
        }
        val hasLabel = lines.any { it.contains("QTY", ignoreCase = true) }
        if (hasLabel) {
            for (line in lines) {
                val v = QTY_PCS_ANY.find(line)?.groupValues?.get(1)?.toIntOrNull()
                if (v != null && v in 1..999) return v
            }
            for (i in lines.indices) {
                if (!QTY_BARE.matches(lines[i])) continue
                for (next in lines.subList(i + 1, minOf(i + 7, lines.size))) {
                    val v = QTY_VALUE.find(next)?.groupValues?.get(1)?.toIntOrNull()
                    if (v != null && v in 1..999) return v
                }
            }
        }
        // ④ 兜底：QTY 标签找不到时，直接拿 PCS 定位——PCS 前面的数字就是数量。
        for (line in lines) {
            val v = QTY_PCS_BEFORE.find(line)?.groupValues?.get(1)?.toIntOrNull()
            if (v != null && v in 1..999) return v
        }
        return 0
    }

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
        // QTY 整批判定一次（标签列与值列可能不相邻，见 parseQty），不在逐行里只看下一行。
        if (qty <= 0) {
            qty = parseQty(ocrLines.map { it.trim() }.filter { it.isNotEmpty() })
        }
        for (i in ocrLines.indices) {
            val line = ocrLines[i].trim()
            if (line.isEmpty()) continue
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
        var sns = when {
            material.isNotEmpty() -> {
                val prefixed = mixed.filter { it.startsWith(material) }
                if (prefixed.isNotEmpty()) prefixed else clusterSn(mixed)
            }
            else -> clusterSn(mixed)
        }
        // 箱号（2026-11-13 用户实测纠正：箱号扫不到时绝不拿 SN 顶替）。
        // 旧逻辑是“第一个不在 SN 批里的混合码就是箱号”，但 SN 批没确立时
        // sns 为空，第一个混合码（往往是 SN）就被当成箱号填进去，之后还
        // 锁死不再改。改成三档，只在有把握时才定箱号：
        //  ① SN 批已确立（≥2 个）：批外的第一个混合码就是箱号；
        //  ② 单台机器：唯一的 SN 就是箱号（业务规则“箱号=SN”，与 BoxParser 一致）；
        //  ③ 只有一个混合码、SN 批没确立、QTY=1：不像箱号的码就是 SN（单台），
        //     像箱号（CA/PA 长码）的码就是箱号本身，SN 还没扫到；
        //  其余情况（SN 批没确立）：箱号留空，等扫到再说，绝不猜。
        var box = state.boxCode
        var boxFromSn = state.boxFromSn
        if (box.isEmpty()) {
            val hasIntegrated = codes.any { it.contains(',') || it.contains(';') }
            when {
                sns.size >= 2 -> {
                    box = mixed.firstOrNull { it !in sns } ?: ""
                }
                sns.size == 1 && qty == 1 && !hasIntegrated -> {
                    box = sns.first()
                    boxFromSn = true
                }
                sns.isEmpty() && mixed.size == 1 && qty == 1 -> {
                    val only = mixed.first()
                    val looksBox = only.length >= 14 &&
                        (only.startsWith("CA", ignoreCase = true) || only.startsWith("PA", ignoreCase = true))
                    if (looksBox) {
                        box = only
                    } else {
                        sns = listOf(only)
                        box = only
                        boxFromSn = true
                    }
                }
            }
        }

        return BoxScanState(
            materialCode = material,
            qty = qty,
            boxCode = box,
            productionDate = date,
            serialNumbers = sns,
            allCodes = codes,
            boxFromSn = boxFromSn,
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
