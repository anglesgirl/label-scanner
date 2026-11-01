package com.anglesgirl.labelscanner.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchHeaderParserTest {
    @Test
    fun prefersTerminalMaterialNumberToPartNumberAndIncidentalOrderNumbers() {
        val text = """
            Production Order 126006629
            Part Number CTL-350Y
            Terminal Material Number 208130300401
        """.trimIndent()
        assertEquals("208130300401", BatchHeaderParser.extractMaterialCode(text))
    }

    @Test
    fun extractsChineseTerminalMaterialNumberAndDemandDate() {
        val text = "Lexmark物料编号 CTL-350Y\n终端物料编号\n208130300401\n需求日期 09/19/2026"
        assertEquals("208130300401", BatchHeaderParser.extractMaterialCode(text))
        assertEquals("20260919", BatchHeaderParser.extractProductionDate(text))
    }

    @Test
    fun rejectsLexmarkModelAndMachineTypeAsMaterialCode() {
        assertNull(BatchHeaderParser.extractMaterialCode("Lexmark物料编号 CTL-350Y\n内部物料编号 1002-CTL-350Y-0"))
        assertNull(BatchHeaderParser.extractMaterialCode("机种 CTL-350Y"))
        assertNull(BatchHeaderParser.extractMaterialCode("生产工单号 126006629"))
    }

    @Test
    fun recoversUnlabeledTwelveDigitCodeButNotTenDigitOrderNumber() {
        assertEquals("208130300401", BatchHeaderParser.extractMaterialCode("机种 CTL-35\n4501171839\n208130 300401"))
        assertNull(BatchHeaderParser.extractMaterialCode("机种 CTL-35\n4501171839\n126006629"))
    }

    @Test
    fun manufacturingDateWinsAndBothDateConventionsNormalize() {
        assertEquals(
            "20260919",
            BatchHeaderParser.extractProductionDate("Manufacturing Date\n(11) 2026/09/19\nDelivery Date 09/20/2026"),
        )
        assertEquals(
            "20260919",
            BatchHeaderParser.extractProductionDate("需求日期 09/19/2026\n交货日期 09/20/2026"),
        )
    }

    @Test
    fun invalidDatesAreIgnored() {
        assertNull(BatchHeaderParser.extractProductionDate("生产日期 2026/02/30"))
    }

    @Test
    fun helperCodesRemainVisibleButAreSuggestedUnchecked() {
        assertTrue(BatchHeaderParser.isLikelyHeaderCode("(P)CTL-350Y", "CTL-350Y"))
        assertTrue(BatchHeaderParser.isLikelyHeaderCode("(11)20260919", "CTL-350Y"))
        assertTrue(BatchHeaderParser.isLikelyHeaderCode("144", "CTL-350Y"))
        assertFalse(BatchHeaderParser.isLikelyHeaderCode("SERIAL-ABC-00001", "CTL-350Y"))
    }
}
