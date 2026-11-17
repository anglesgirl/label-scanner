package com.anglesgirl.labelscanner.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 双物料编码（2026-11-17 奔图外箱标签 MODEL CPJ3000）：
 * 印的 SAP 301603100201（二中心生产编码，OCR “SAP.” 行后面）与单独贴的
 * 小贴纸条码 308649300001（一中心自己的编码）—— D 列填贴纸码，印的 SAP
 * 进第二编码字段；只有一个编码的标签行为完全不变。
 */
class BoxParserDualMaterialTest {

    private val vaSns = listOf(
        "VA1RV000LY", "VA1RV000JC", "VA1RV000WH", "VA1RV000VN",
        "VA1RV000H9", "VA1RV000HP", "VA1RV000U2", "VA1RV000U7",
    )

    /** 真实标签的 OCR 行序（字段名与值分行，OCR 列优先排版）。 */
    private val ocrLines = listOf(
        "MODEL", "CPJ3000",
        "PO#", "ZDWW_20260503002",
        "SO#", "5003067146",
        "QTY.", "8 PCS",
        "SAP.", "301603100201",
        "C/NO", "N16401",
        "DATE", "2026.11.17",
        "UPC", "6936358094955",
        "308649300001",
    )

    @Test
    fun dualLabel_stickerGoesToMaterial_printedSapGoesToSecond() {
        val barcodes = listOf("308649300001", "6936358094955") + vaSns + listOf("PM016NP10054022")

        val r = BoxParser.parse(barcodes, ocrLines.joinToString("\n"))

        assertEquals("308649300001", r.materialCode)
        assertEquals("301603100201", r.secondMaterialCode)
        assertEquals("6936358094955", r.ean69)
        assertFalse(r.materialFromEan69)
        assertEquals("20261117", r.productionDate)
        assertEquals("CPJ3000", r.model)
        assertTrue(r.serialNumbers.containsAll(vaSns))
        // PM016NP10054022 目前也被算进 SN（旧规则：非 CA/PA 前缀的混合码都当 SN）；
        // 箱号仍为空，需人工填 —— 都是旧行为，本次不动。
        assertEquals(9, r.serialNumbers.size)
        assertEquals("", r.boxCode)
    }

    @Test
    fun dualLabel_sapLineSameRow() {
        val ocr = "SAP. 301603100201\nQTY. 8 PCS\n308649300001"
        val barcodes = listOf("308649300001", "6936358094955") + vaSns

        val r = BoxParser.parse(barcodes, ocr)

        assertEquals("308649300001", r.materialCode)
        assertEquals("301603100201", r.secondMaterialCode)
    }

    @Test
    fun dualLabel_stickerBarcodeMissed_fallsBackToOcrDigits() {
        // 贴纸条码没扫出来：OCR 裸行读到贴纸上的印刷数字，同样区分。
        val barcodes = listOf("6936358094955") + vaSns + listOf("PM016NP10054022")

        val r = BoxParser.parse(barcodes, ocrLines.joinToString("\n"))

        assertEquals("308649300001", r.materialCode)
        assertEquals("301603100201", r.secondMaterialCode)
        assertFalse(r.materialFromEan69)
    }

    @Test
    fun dualLabel_lookup69NeverOverridesSticker() {
        val barcodes = listOf("6936358094955") + vaSns
        val lookup: (String) -> String? = { "999999999901" }

        val r = BoxParser.parse(barcodes, ocrLines.joinToString("\n"), lookup69 = lookup)

        assertEquals("308649300001", r.materialCode)
        assertEquals("301603100201", r.secondMaterialCode)
        assertFalse(r.materialFromEan69)
    }

    @Test
    fun singleLabel_unchanged_noSecondCode() {
        val r = BoxParser.parse(
            listOf("201071000501", "6936358094955"),
            "SAP. 201071000501\n2025-09-17",
        )

        assertEquals("201071000501", r.materialCode)
        assertEquals("", r.secondMaterialCode)
        assertEquals("20250917", r.productionDate)
    }

    @Test
    fun padPair_notTreatedAsDual() {
        // 10 位与补 01 后的 12 位是同一物料的两种写法，不算两个编码。
        val r = BoxParser.parse(
            listOf("3011225058"),
            "SAP. 301122505801",
        )

        assertEquals("301122505801", r.materialCode)
        assertEquals("", r.secondMaterialCode)
    }

    @Test
    fun singleLabel_lookup69StillWorks() {
        val r = BoxParser.parse(
            listOf("6936358094955"),
            "SO#\n5003067146\n2026.11.17",
            lookup69 = { "201071000501" },
        )

        assertEquals("201071000501", r.materialCode)
        assertEquals("", r.secondMaterialCode)
        assertTrue(r.materialFromEan69)
    }
}
