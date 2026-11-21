package com.anglesgirl.labelscanner.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多行入库单（免费入库申请单）解析：整张单 4 行，行间只有物料和数量不同。
 * 用用户 2026-11-21 拍的真单做用例。
 */
class SlipOcrParserTableTest {

    private val slipText = """
        免费入库申请单
        内部资料 注意保密
        表格编号 P1-Q-085-47-00-03/V1 保存部门 成品仓储物流部 保存期限 1年
        退仓部门 物料编码 物料描述 交货数量 单位 工厂 仓位 交货日期
        三部OQC
        203200048601 F-粉盒其他组件-2023系列(K)-回收空粉盒（不含芯片） 267 PCS 6013 3511 2026/11/23
        203200048701 F-粉盒其他组件-2023系列(C)-回收空粉盒（不含芯片） 267 PCS 6013 3511 2026/11/23
        203200048801 F-粉盒其他组件-2023系列(M)-回收空粉盒（不含芯片） 267 PCS 6013 3511 2026/11/23
        203200048901 F-粉盒其他组件-2023系列(Y)-回收空粉盒（不含芯片） 178 PCS 6013 3511 2026/11/23
        备注（入库原因）：OQC测试损耗，无法再次使用，故申请回收报废。
        编制（需求人） 陈仕宏 批准（需求部门负责人） 接收（仓管员） 李世富
    """.trimIndent()

    @Test
    fun parsesAllFourRowsWithQuantities() {
        val r = SlipOcrParser.parseInboundTable(slipText)
        assertTrue(r.hasAny)
        assertEquals(4, r.rows.size)
        assertEquals("203200048601", r.rows[0].materialCode)
        assertEquals(267, r.rows[0].quantity)
        assertEquals("203200048701", r.rows[1].materialCode)
        assertEquals(267, r.rows[1].quantity)
        assertEquals("203200048801", r.rows[2].materialCode)
        assertEquals(267, r.rows[2].quantity)
        assertEquals("203200048901", r.rows[3].materialCode)
        assertEquals(178, r.rows[3].quantity)
    }

    @Test
    fun extractsSharedLocationFactoryDateDepartment() {
        val r = SlipOcrParser.parseInboundTable(slipText)
        assertEquals("3511", r.location)
        assertEquals("6013", r.factory)
        assertEquals("2026/11/23", r.date)
        assertEquals("三部OQC", r.department)
    }

    @Test
    fun descriptionYearIsNotTakenAsQuantity() {
        // 描述里的 2023 在数量前面，只取 PCS 前最后一个整数才不会误认。
        val r = SlipOcrParser.parseInboundTable(
            "203200048601 F-粉盒其他组件-2023系列(K) 267 PCS 6013 3511 2026/11/23",
        )
        assertEquals(1, r.rows.size)
        assertEquals(267, r.rows[0].quantity)
    }

    @Test
    fun handlesRowSplitAcrossLinesAndSplitCode() {
        val text = """
            三部OQC
            20320004860
            1 F-粉盒其他组件-2023系列(K)-回收空粉盒
            （不含芯片） 267 PCS 6013 3511 2026/11/23
            203200048701 F-粉盒其他组件-2023系列(C)-回收空粉盒（不含芯片）
            267 PCS 6013 3511 2026/11/23
        """.trimIndent()
        val r = SlipOcrParser.parseInboundTable(text)
        assertEquals(2, r.rows.size)
        assertEquals("203200048601", r.rows[0].materialCode)
        assertEquals(267, r.rows[0].quantity)
        assertEquals("203200048701", r.rows[1].materialCode)
        assertEquals(267, r.rows[1].quantity)
    }

    @Test
    fun normalizesConfusableLettersInCode() {
        val r = SlipOcrParser.parseInboundTable(
            "2032000486O1 F-粉盒其他组件-2023系列(K) 267 PCS 6013 3511 2026/11/23",
        )
        assertEquals("203200048601", r.rows[0].materialCode)
        assertEquals(267, r.rows[0].quantity)
    }

    @Test
    fun emptyTextYieldsNoRows() {
        val r = SlipOcrParser.parseInboundTable("")
        assertTrue(r.rows.isEmpty())
        assertEquals("", r.location)
    }
}
