package com.anglesgirl.labelscanner.model.v2

/**
 * v2 解析内核回归测试。
 *
 * 所有用例都来自用户 2026-09-11 提供的**真实标签照片**，
 * 不是编造的样例 —— 这些形态一旦被解析错，真机上就会产生"看起来对"的错数据。
 */
object LabelParserV2Test {

    private var pass = 0
    private var fail = 0

    private fun check(name: String, cond: Boolean, detail: String = "") {
        if (cond) {
            pass += 1
            println("  ✅ $name")
        } else {
            fail += 1
            println("  ❌ $name  $detail")
        }
    }

    // ── 用例 1：打印机本体标签（箱内，一箱一台）──────────────────────────
    // 实物：条码 CV7DV0007F(SN) / 20250902(日期) / 3011231031(物料 10 位)
    private fun case1PrinterBody() {
        println("[1] 打印机本体标签（CV7DV0007F / 3011231031 / 20250902）")
        val r = LabelParserV2.parse(codes = listOf("CV7DV0007F", "3011231031", "20250902"))
        check("物料编码补 01", r.materialCode == "301123103101", "实际=${r.materialCode}")
        check("日期归一", r.productionDate == "20250902", "实际=${r.productionDate}")
        check("SN 识别", r.serialNumbers == listOf("CV7DV0007F"), "实际=${r.serialNumbers}")
        check("出 1 条记录", r.recordCount == 1, "实际=${r.recordCount}")
        check("F=1", r.qtyForRow(0) == 1)
        check("无告警", r.warnings.isEmpty(), "实际=${r.warnings}")
    }

    // ── 用例 2：外购 A3 机外箱（关键：前缀抑制）─────────────────────────
    // 实物：ACP5P81(产品代码) + ACP5P81000050(SN) ×2 + 303020000401(P/N) + 2020/09/02
    // v1 在这里会：把 ACP5P81 也当 SN，且把 SN 误判成箱号
    private fun case2A3OuterBox() {
        println("[2] 外购 A3 机外箱（产品代码是 SN 的前缀 —— v1 的采错点）")
        val r = LabelParserV2.parse(
            codes = listOf(
                "ACP5P81",           // 产品代码 = SN 的前缀，必须被丢弃
                "ACP5P81000050",     // Serial Number
                "ACP5P81000050",     // Factory Original #（同值，去重）
                "303020000401",      // P/N
                "2020/09/02",        // 带斜杠日期
            ),
        )
        check("物料 = P/N 12 位", r.materialCode == "303020000401", "实际=${r.materialCode}")
        check("日期归一（斜杠）", r.productionDate == "20200902", "实际=${r.productionDate}")
        check("前缀码已被抑制（只剩 1 个 SN）", r.serialNumbers == listOf("ACP5P81000050"), "实际=${r.serialNumbers}")
        check("出 1 条记录", r.recordCount == 1, "实际=${r.recordCount}")
        check("前缀/去重有告警", r.warnings.any { it.contains("重复") || it.contains("前缀") }, "实际=${r.warnings}")
    }

    // ── 用例 3：整箱粉盒外箱（8 件，多 SN 条码）─────────────────────────
    // 实物：SCAG2529704B3..B4..B2..B1..AF..BB..AE..AD（8 个）
    //       + CA70450P10052278(箱号) + 201121001601(SAP) + 2025.10.24 + 6936358018630(UPC)
    // v1 在这里会：把第一个 SCAG 当箱号 → 箱号错、SN 少 1 个
    private fun case3CartridgeBox8() {
        println("[3] 整箱粉盒（8 件，箱号 CA + 8 个 SN 条码）")
        val codes = listOf(
            "SCAG2529704B3", "SCAG2529704B4", "SCAG2529704B2", "SCAG2529704B1",
            "SCAG2529704AF", "SCAG2529704BB", "SCAG2529704AE", "SCAG2529704AD",
            "CA70450P10052278", "201121001601", "2025.10.24", "6936358018630",
        )
        val r = LabelParserV2.parse(codes = codes)
        check("物料 = SAP 12 位", r.materialCode == "201121001601", "实际=${r.materialCode}")
        check("日期归一（点分隔）", r.productionDate == "20251024", "实际=${r.productionDate}")
        check("箱号 = CA 开头（不是被误当 SN）", r.boxCode == "CA70450P10052278", "实际=${r.boxCode}")
        check("SN 恰好 8 个", r.serialNumbers.size == 8, "实际=${r.serialNumbers.size}")
        check("SN 里不含箱号", !r.serialNumbers.contains("CA70450P10052278"))
        check("出 8 条记录", r.recordCount == 8, "实际=${r.recordCount}")
        check("EAN13 不进模板", r.ean13 == listOf("6936358018630"), "实际=${r.ean13}")
        check("每行 F=1", (0 until 8).all { r.qtyForRow(it) == 1 })
        check("每行序列号各不相同", r.serialNumbers.toSet().size == 8)
    }

    // ── 用例 4：整箱粉盒（集成码，6 件）────────────────────────────────
    // 实物：二维码内 6 个 SN（逗号分隔）+ PA40157P10025632(箱号) + 201095000601 + 2025-07-21 + QTY 6
    private fun case4CartridgeQR6() {
        println("[4] 整箱粉盒·集成码（二维码 6 个 SN）")
        val qr = "SCAG2529704B3,SCAG2529704B4,SCAG2529704B2,SCAG2529704B1,SCAG2529704AF,SCAG2529704BB"
        val r = LabelParserV2.parse(
            codes = listOf("PA40157P10025632", "201095000601", "2025-07-21"),
            qrPayloads = listOf(qr),
            ocrLines = listOf("QTY. 6 PCS"),
        )
        check("物料 = SAP 12 位", r.materialCode == "201095000601", "实际=${r.materialCode}")
        check("日期归一（横杠）", r.productionDate == "20250721", "实际=${r.productionDate}")
        check("箱号 = PA 开头", r.boxCode == "PA40157P10025632", "实际=${r.boxCode}")
        check("二维码拆出 6 个 SN", r.serialNumbers.size == 6, "实际=${r.serialNumbers.size}")
        check("数量对账通过（无\"还差\"告警）", r.warnings.none { it.contains("还差") }, "实际=${r.warnings}")
        check("出 6 条记录", r.recordCount == 6, "实际=${r.recordCount}")
    }

    // ── 用例 5：普通 A4 机外箱（一箱一台）──────────────────────────────
    // 实物：CL7LV015J0(SN) + 3010110050(物料 10 位) + 2026-06-01 + EAN13
    private fun case5A4OuterBox() {
        println("[5] 普通 A4 机外箱（CL7LV015J0 / 3010110050）")
        val r = LabelParserV2.parse(
            codes = listOf("CL7LV015J0", "3010110050", "2026-06-01", "6936358024976"),
        )
        check("物料补 01", r.materialCode == "301011005001", "实际=${r.materialCode}")
        check("日期归一（横杠）", r.productionDate == "20260601", "实际=${r.productionDate}")
        check("SN 识别", r.serialNumbers == listOf("CL7LV015J0"), "实际=${r.serialNumbers}")
        check("EAN13 不进模板", r.ean13.isNotEmpty())
        check("出 1 条记录", r.recordCount == 1)
    }

    // ── 用例 6：粉盒只有箱号（无序列号）—— 用户选定的方案 A ──────────────
    // 期望：1 条记录，F = QTY（而不是硬凑 N 行）
    private fun case6BoxOnly() {
        println("[6] 粉盒只有箱号（无 SN）→ 1 条 + F=QTY")
        val r = LabelParserV2.parse(
            codes = listOf("CA70450P10052278", "201121001601", "2025.10.24"),
            ocrLines = listOf("QTY. 8 PCS"),
        )
        check("无 SN", r.serialNumbers.isEmpty(), "实际=${r.serialNumbers}")
        check("箱号识别", r.boxCode == "CA70450P10052278", "实际=${r.boxCode}")
        check("出 1 条记录", r.recordCount == 1, "实际=${r.recordCount}")
        check("F = QTY = 8", r.qtyForRow(0) == 8, "实际=${r.qtyForRow(0)}")
        check("该行序列号回退为箱号", r.snForRow(0) == "CA70450P10052278")
    }

    // ── 用例 7：数量不符必须告警（防"静默通过"）────────────────────────
    private fun case7QtyMismatch() {
        println("[7] 数量不符必须告警（QTY=8 只扫到 6）")
        val qr = "SCAG2529704B3,SCAG2529704B4,SCAG2529704B2,SCAG2529704B1,SCAG2529704AF,SCAG2529704BB"
        val r = LabelParserV2.parse(
            codes = listOf("PA40157P10025632", "201095000601", "2025-07-21"),
            qrPayloads = listOf(qr),
            ocrLines = listOf("QTY. 8 PCS"),
        )
        check("识别出 6 个 SN", r.serialNumbers.size == 6)
        check("明确告警\"还差 2 个\"", r.warnings.any { it.contains("还差 2") }, "实际=${r.warnings}")
    }

    // ── 用例 8：物料/日期缺失要提示 ────────────────────────────────────
    private fun case8MissingFields() {
        println("[8] 关键字段缺失要提示")
        val r = LabelParserV2.parse(codes = listOf("CV7DV0007F"))
        check("缺物料告警", r.warnings.any { it.contains("物料") }, "实际=${r.warnings}")
        check("缺日期告警", r.warnings.any { it.contains("日期") }, "实际=${r.warnings}")
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println("=== LabelParserV2 回归测试（样本全部来自真实标签）===\n")
        case1PrinterBody()
        case2A3OuterBox()
        case3CartridgeBox8()
        case4CartridgeQR6()
        case5A4OuterBox()
        case6BoxOnly()
        case7QtyMismatch()
        case8MissingFields()
        println("\n=== 通过 $pass / 失败 $fail ===")
        if (fail > 0) throw AssertionError("有 $fail 项断言失败")
    }
}
