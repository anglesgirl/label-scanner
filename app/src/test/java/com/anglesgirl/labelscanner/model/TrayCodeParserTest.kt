package com.anglesgirl.labelscanner.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrayCodeParserTest {
    @Test
    fun prefersSingleSsccAmongOtherLabelBarcodes() {
        val sscc = "00907346462626020366"
        val candidates = TrayCodeParser.candidates(
            listOf("L1260066290100144", "6936358013017", sscc),
            "栈板信息",
        )

        assertEquals(sscc, TrayCodeParser.preferred(candidates))
        assertEquals(sscc, TrayCodeParser.prioritize(candidates).first())
    }

    @Test
    fun leavesMultipleNonSsccCodesForUserChoice() {
        val candidates = TrayCodeParser.candidates(
            listOf("L1260066290100144", "6936358013017"),
            "",
        )

        assertNull(TrayCodeParser.preferred(candidates))
        assertEquals(listOf("L1260066290100144", "6936358013017"), candidates)
    }

    @Test
    fun extractsWhitespaceSeparatedSsccFromOcr() {
        val expected = "00907346462626020366"

        assertEquals(
            expected,
            TrayCodeParser.preferred(TrayCodeParser.candidates(emptyList(), "00 907346462626020366")),
        )
    }
}
