package com.anglesgirl.labelscanner.data.v2

/**
 * 托盘会话回归测试。
 * 用真实标签形态构造（8 件粉盒 / 6 件集成码 / 1 台打印机）。
 */
object TraySessionV2Test {

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

    private fun cartridge8(box: String, material: String = "201121001601") = BoxRecordV2(
        boxCode = box,
        materialCode = material,
        productionDate = "20251024",
        serialNumbers = (1..8).map { "SCAG2529704B$it" },
        declaredQty = 8,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        println("=== TraySessionV2 回归测试 ===\n")

        val tray = TraySessionV2(trayCode = "T-20260911-001")

        // 两箱同物料粉盒 + 一箱集成码粉盒 + 一台打印机
        tray.addBox(cartridge8("CA70450P10052278"))
        tray.addBox(cartridge8("CA70450P10052279"))
        tray.addBox(
            BoxRecordV2(
                boxCode = "PA40157P10025632",
                materialCode = "201095000601",
                productionDate = "20250721",
                serialNumbers = (1..6).map { "SCAG2529704C$it" },
                declaredQty = 6,
            ),
        )
        tray.addBox(
            BoxRecordV2(
                boxCode = "CL7LV015J0",
                materialCode = "301011005001",
                productionDate = "20260601",
                serialNumbers = listOf("CL7LV015J0"),
                declaredQty = 1,
            ),
        )

        println("[1] 托盘总量")
        check("箱数 = 4", tray.totalBoxes == 4, "实际=${tray.totalBoxes}")
        check("件数 = 8+8+6+1 = 23", tray.totalUnits == 23, "实际=${tray.totalUnits}")
        check("导出行数 = 8+8+6+1 = 23", tray.totalRows == 23, "实际=${tray.totalRows}")

        println("[2] 按物料汇总")
        val sum = tray.summary()
        check("分 3 组物料", sum.size == 3, "实际=${sum.size}")
        val m1 = sum.firstOrNull { it.materialCode == "201121001601" }
        check("201121001601 = 2 箱 / 16 件", m1?.boxCount == 2 && m1?.unitCount == 16,
            "实际=${m1?.boxCount}箱/${m1?.unitCount}件")
        check("该物料两个箱号都在", m1?.boxCodes?.size == 2, "实际=${m1?.boxCodes}")
        val m2 = sum.firstOrNull { it.materialCode == "201095000601" }
        check("201095000601 = 1 箱 / 6 件", m2?.boxCount == 1 && m2?.unitCount == 6,
            "实际=${m2?.boxCount}箱/${m2?.unitCount}件")

        println("[3] 只有箱号（无 SN）的一箱：F=QTY")
        val boxOnly = BoxRecordV2(
            boxCode = "CA99999P10000001",
            materialCode = "201121001601",
            productionDate = "20251024",
            serialNumbers = emptyList(),
            declaredQty = 8,
        )
        check("无 SN 时 rowCount=1", boxOnly.rowCount == 1)
        check("effectiveQty = 8", boxOnly.effectiveQty == 8, "实际=${boxOnly.effectiveQty}")
        tray.addBox(boxOnly)
        check("加入后总箱数 5", tray.totalBoxes == 5)
        check("加入后总件数 31", tray.totalUnits == 31, "实际=${tray.totalUnits}")
        check("加入后导出行数 24", tray.totalRows == 24, "实际=${tray.totalRows}")

        println("[4] 问题箱高亮")
        val bad = BoxRecordV2(
            boxCode = "PA40157P10025633",
            materialCode = "201095000601",
            productionDate = "20250721",
            serialNumbers = (1..5).map { "SCAG2529704D$it" },   // QTY=6 只扫到 5
            declaredQty = 6,
            warnings = listOf("标签数量 6 个，实际识别 5 个 SN，还差 1 个"),
        )
        tray.addBox(bad)
        check("能识别出问题箱", tray.boxesWithIssues.size == 1, "实际=${tray.boxesWithIssues.size}")
        check("问题箱就是它", tray.boxesWithIssues.first().boxCode == "PA40157P10025633")

        println("[5] 撤销 / 删除")
        check("撤销返回该箱", tray.undoLast()?.boxCode == "PA40157P10025633")
        check("撤销后无问题箱", tray.boxesWithIssues.isEmpty())
        check("删除首箱成功", tray.removeAt(0)?.boxCode == "CA70450P10052278")
        check("删除后箱数 4", tray.totalBoxes == 4, "实际=${tray.totalBoxes}")
        tray.clear()
        check("清空后为 0", tray.totalBoxes == 0 && tray.totalUnits == 0)

        println("\n=== 通过 $pass / 失败 $fail ===")
        if (fail > 0) throw AssertionError("有 $fail 项断言失败")
    }
}
