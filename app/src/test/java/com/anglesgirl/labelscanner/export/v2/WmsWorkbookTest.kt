package com.anglesgirl.labelscanner.export.v2

import com.anglesgirl.labelscanner.data.v2.BoxRecordV2
import com.anglesgirl.labelscanner.data.v2.TraySessionV2
import java.io.File

/**
 * WMS 模板导出回归测试（纯逻辑，可离线跑）。
 *
 * 重点验证**列位置**：曾因列左移被用户当场纠正"填歪了"，
 * 所以这里逐列断言，不只看行数。
 */
object WmsWorkbookTest {

    private var pass = 0
    private var fail = 0

    private fun check(name: String, ok: Boolean, detail: String = "") {
        if (ok) { pass++; println("  ✅ $name") } else { fail++; println("  ❌ $name  $detail") }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println("=== WMS 模板导出测试 ===\n")

        val tray = TraySessionV2("T-20260911-001")

        // 箱1：粉盒 8 件（CA 箱号 + 8 个 SN）
        tray.addBox(
            BoxRecordV2(
                boxCode = "CA70450P10052278",
                materialCode = "201121001601",
                productionDate = "20251024",
                serialNumbers = listOf(
                    "SCAG2529704B3", "SCAG2529704B4", "SCAG2529704B2", "SCAG2529704B1",
                    "SCAG2529704AF", "SCAG2529704BB", "SCAG2529704AE", "SCAG2529704AD",
                ),
                declaredQty = 8,
            )
        )

        // 箱2：只有箱号、无 SN，一箱 8 件
        tray.addBox(
            BoxRecordV2(
                boxCode = "CA99999P10099999",
                materialCode = "201095000601",
                productionDate = "20250721",
                serialNumbers = emptyList(),
                declaredQty = 8,
            )
        )

        val rows = WmsWorkbook.buildRows(tray)

        println("[1] 行数")
        check("8 个 SN → 8 行，无 SN 的箱 → 1 行，共 9 行", rows.size == 9, "实际=${rows.size}")

        println("\n[2] 列位置（0-based 数组下标 = 列序数 - 2）")
        val r0 = rows[0]
        check("DATA01 库位 = STAGE", r0[0] == "STAGE", "实际=${r0[0]}")
        check("DATA02 卡板 = 托盘号", r0[1] == "T-20260911-001", "实际=${r0[1]}")
        check("DATA03 物料编码", r0[2] == "201121001601", "实际=${r0[2]}")
        check("DATA04 箱号", r0[3] == "CA70450P10052278", "实际=${r0[3]}")
        check("DATA05 数量 = 1（逐 SN 一行）", r0[4] == "1", "实际=${r0[4]}")
        check("DATA06 工厂 留空", r0[5] == "", "实际=${r0[5]}")
        check("DATA07 库存地 留空", r0[6] == "", "实际=${r0[6]}")
        check("DATA08 生产日期", r0[7] == "20251024", "实际=${r0[7]}")
        check("DATA09 销售公司 留空", r0[8] == "", "实际=${r0[8]}")
        check("DATA10 销售订单|行号 = |", r0[9] == "|", "实际=${r0[9]}")
        check("DATA13 WCS 留空", r0[12] == "", "实际=${r0[12]}")
        check("DATA14 SN码", r0[13] == "SCAG2529704B3", "实际=${r0[13]}")

        println("\n[3] 无 SN 的箱（F=QTY，O 列回退箱号）")
        val last = rows[8]
        check("数量 = 8", last[4] == "8", "实际=${last[4]}")
        check("箱号 = CA99999P10099999", last[3] == "CA99999P10099999", "实际=${last[3]}")
        check("SN 列回退为箱号", last[13] == "CA99999P10099999", "实际=${last[13]}")

        println("\n[4] 8 个 SN 各不相同")
        val snSet = rows.take(8).map { it[13] }.toSet()
        check("8 个 SN 无重复", snSet.size == 8, "实际=${snSet.size}")

        println("\n[5] 生成 xlsx 文件")
        val bytes = WmsWorkbook.buildBytes(rows)
        check("字节非空", bytes.isNotEmpty(), "实际=${bytes.size}")
        val out = File("/tmp/tray_test.xlsx")
        out.writeBytes(bytes)
        check("已写出 /tmp/tray_test.xlsx（${bytes.size} 字节）", out.length() == bytes.size.toLong())

        println("\n=== 通过 $pass / 失败 $fail ===")
        if (fail > 0) throw AssertionError("有 $fail 项断言失败")
    }
}
