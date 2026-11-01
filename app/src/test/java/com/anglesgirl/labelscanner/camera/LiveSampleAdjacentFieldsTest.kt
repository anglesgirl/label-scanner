package com.anglesgirl.labelscanner.camera

import com.anglesgirl.labelscanner.model.LabelParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class LiveSampleAdjacentFieldsTest {
    private val serialBarcode = "CU5NV00013"
    private val eanBarcode = "6901234567892"
    private val adjacentFields = "CU5NV00013 3011062024\n20250213"

    @Test
    fun adjacentSerialAndMaterialNeedThreeMatchingFramesAndSurviveAnInterveningBadFrame() {
        val consensus = LiveSampleConsensus()
        val labelBarcodes = listOf(serialBarcode, eanBarcode)

        val first = consensus.observe(labelBarcodes, adjacentFields)
        assertEquals(1, first.matchingCount)
        assertNull(first.result)

        val second = consensus.observe(labelBarcodes, adjacentFields)
        assertEquals(2, second.matchingCount)
        assertNull(second.result)

        val interrupted = consensus.observe(listOf("unrelated-noise"), "unreadable")
        assertEquals(2, interrupted.matchingCount)
        assertNull(interrupted.result)

        val confirmed = consensus.observe(labelBarcodes, adjacentFields)
        assertEquals(4, confirmed.sampleCount)
        assertEquals(3, confirmed.matchingCount)
        assertNotNull(confirmed.result)

        val result = confirmed.result!!
        assertEquals(labelBarcodes.toSet(), result.barcodes.toSet())
        assertFalse(result.barcodes.contains("unrelated-noise"))
        assertEquals(emptyList<String>(), result.unconfirmedBarcodes)
        assertEquals(emptyList<String>(), result.unconfirmedOcrLines)

        val parsed = LabelParser.parse(result.barcodes, result.ocrText)
        assertEquals(serialBarcode, parsed.serialNumber)
        assertEquals("301106202401", parsed.materialCode)
        assertEquals("20250213", parsed.productionDate)
    }
}
