package com.anglesgirl.labelscanner.model

import org.junit.Assert.assertEquals
import org.junit.Test

class LabelParserAdjacentFieldsTest {
    private val serial = "CU5NV00013"
    private val material10 = "3011062024"

    @Test
    fun whitespaceSeparatedSerialAndMaterialOnOneOcrLineAreBothParsed() {
        val parsed = LabelParser.parse(emptyList(), "$serial $material10\n20250213")

        assertEquals(serial, parsed.serialNumber)
        assertEquals("301106202401", parsed.materialCode)
        assertEquals("20250213", parsed.productionDate)
    }

    @Test
    fun realDecodedSerialBarcodeDoesNotGetConsumedByAdjacentMaterial() {
        val parsed = LabelParser.parse(listOf(serial), "$serial $material10\n20250213")

        assertEquals(serial, parsed.serialNumber)
        assertEquals("301106202401", parsed.materialCode)
        assertEquals(listOf(serial), parsed.barcodes)
    }

    @Test
    fun materialDigitsMayContainOcrSpacesAfterTheSerialSeparator() {
        val parsed = LabelParser.parse(emptyList(), "$serial 3011 062024\n20250213")

        assertEquals(serial, parsed.serialNumber)
        assertEquals("301106202401", parsed.materialCode)
        assertEquals("20250213", parsed.productionDate)
    }

    @Test
    fun serialLabelPrefixDoesNotHideAdjacentMaterial() {
        for (prefix in listOf("SN: ", "S/N: ")) {
            val parsed = LabelParser.parse(emptyList(), "$prefix$serial $material10\n20250213")

            assertEquals(serial, parsed.serialNumber)
            assertEquals("301106202401", parsed.materialCode)
            assertEquals("20250213", parsed.productionDate)
        }
    }

    @Test
    fun serialActuallyBeginningWithSnIsNotMistakenForTheSnLabelPrefix() {
        val serialStartingWithSn = "SN5NV00013"
        val parsed = LabelParser.parse(emptyList(), "$serialStartingWithSn $material10\n20250213")

        assertEquals(serialStartingWithSn, parsed.serialNumber)
        assertEquals("301106202401", parsed.materialCode)
    }

    @Test
    fun materialThenSerialWithWhitespaceAlsoParsesBothFields() {
        val parsed = LabelParser.parse(emptyList(), "$material10 $serial\n20250213")

        assertEquals(serial, parsed.serialNumber)
        assertEquals("301106202401", parsed.materialCode)
        assertEquals("20250213", parsed.productionDate)
    }

    @Test
    fun gluedFieldsAreSplitOnlyWhenDecodedBarcodeExactlyAnchorsTheSerial() {
        val parsed = LabelParser.parse(
            listOf(serial),
            "CU5NV000133011062024\n20250213",
        )

        assertEquals(serial, parsed.serialNumber)
        assertEquals("301106202401", parsed.materialCode)
        assertEquals("20250213", parsed.productionDate)
        assertEquals(listOf(serial), parsed.barcodes)
    }

    @Test
    fun arbitraryUnspacedLongMixedValueIsNotBlindlySplitIntoSerialAndMaterial() {
        val gluedValue = "CU5NV000133011062024"
        val parsed = LabelParser.parse(emptyList(), "$gluedValue\n20250213")

        assertEquals(gluedValue, parsed.serialNumber)
        assertEquals("", parsed.materialCode)
        assertEquals("20250213", parsed.productionDate)
    }

    @Test
    fun decodedSerialHasPriorityOverConflictingOcrSerial() {
        val barcodeSerial = "ABCD123456"
        val parsed = LabelParser.parse(
            listOf(barcodeSerial),
            "SN: $serial $material10\n20250213",
        )

        assertEquals(barcodeSerial, parsed.serialNumber)
        assertEquals("301106202401", parsed.materialCode)
        assertEquals(listOf(barcodeSerial), parsed.barcodes)
    }

    @Test
    fun existingDateEanAndStandaloneNumericMaterialRulesRemainUnchanged() {
        assertEquals("date" to "20250213", LabelParser.classify("2025-02-13"))
        assertEquals("material10" to material10, LabelParser.classify(material10))
        assertEquals("ean" to "6901234567892", LabelParser.classify("6901234567892"))

        val ean = "6901234567892"
        val parsed = LabelParser.parse(listOf(ean), "$material10\n20250213")
        assertEquals(ean, parsed.ean69)
        assertEquals("301106202401", parsed.materialCode)
        assertEquals("20250213", parsed.productionDate)
    }
}
