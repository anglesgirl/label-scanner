package com.anglesgirl.labelscanner.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 扫整箱（BoxScanClassifier）的双码规则与 VA 型 SN 聚类
 * （2026-11-17 奔图外箱标签 MODEL CPJ3000，与 BoxParser 同规则）。
 */
class BoxScanClassifierDualTest {

    private val vaSns = listOf(
        "VA1RV000LY", "VA1RV000JC", "VA1RV000WH", "VA1RV000VN",
        "VA1RV000H9", "VA1RV000HP", "VA1RV000U2", "VA1RV000U7",
    )

    @Test
    fun dualLabel_stickerWins_snClusteredByFirst8Chars_boxIsPmCode() {
        val barcodes = listOf("308649300001", "6936358094955") + vaSns + listOf("PM016NP10054022")
        val ocrLines = listOf(
            "MODEL", "CPJ3000", "QTY.", "8 PCS",
            "SAP. 301603100201", "C/NO", "N16401",
            "DATE", "2026.11.17", "308649300001",
        )

        val s = BoxScanClassifier.accumulate(BoxScanState(), barcodes, ocrLines)

        assertEquals("308649300001", s.materialCode)
        assertEquals("301603100201", s.secondMaterialCode)
        assertEquals(8, s.qty)
        // VA1RV000xx 只共享前 8 位，前 10 位分组永远成不了批，必须走前 8 位兜底。
        assertEquals(8, s.serialNumbers.size)
        assertTrue(s.serialNumbers.containsAll(vaSns))
        assertEquals("PM016NP10054022", s.boxCode)
        assertEquals("20261117", s.productionDate)
        assertTrue(s.isComplete)
    }

    @Test
    fun dualLabel_printedSapSeenFirst_barcodeLaterStillWins() {
        // 早先帧 OCR 先把印制 SAP 定进物料框，后来帧贴纸条码扫出来：D 列改成贴纸码。
        val ocrOnly = BoxScanClassifier.accumulate(
            BoxScanState(), emptyList(),
            listOf("SAP. 301603100201", "QTY.", "8 PCS"),
        )
        assertEquals("301603100201", ocrOnly.materialCode)
        assertEquals("", ocrOnly.secondMaterialCode)

        val s = BoxScanClassifier.accumulate(
            ocrOnly,
            listOf("308649300001", "6936358094955") + vaSns + listOf("PM016NP10054022"),
            listOf("DATE", "2026.11.17"),
        )

        assertEquals("308649300001", s.materialCode)
        assertEquals("301603100201", s.secondMaterialCode)
        assertEquals(8, s.serialNumbers.size)
        assertTrue(s.isComplete)
    }

    @Test
    fun singleLabel_unchanged_noSecondCode() {
        val s = BoxScanClassifier.accumulate(
            BoxScanState(),
            listOf("201071000501", "SCAG2529704B3", "SCAG2529704B4", "CA70565P10013014"),
            listOf("SAP. 201071000501", "QTY. 2 PCS", "2025-09-17"),
        )

        assertEquals("201071000501", s.materialCode)
        assertEquals("", s.secondMaterialCode)
        assertEquals(2, s.qty)
        assertEquals(listOf("SCAG2529704B3", "SCAG2529704B4"), s.serialNumbers)
        assertEquals("CA70565P10013014", s.boxCode)
        assertTrue(s.isComplete)
    }

    @Test
    fun extractQty_fromOcrText() {
        assertEquals(8, BoxScanClassifier.extractQty("MODEL CPJ3000\nQTY. 8 PCS\nSAP. 301603100201"))
        assertEquals(8, BoxScanClassifier.extractQty("QTY.\n8 PCS"))
        assertEquals(0, BoxScanClassifier.extractQty("MODEL CPJ3000\nSAP. 301603100201"))
    }
}
