package com.anglesgirl.labelscanner.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LabelParserDateTest {

    @Test
    fun mixedWhitespaceAndPunctuationDateIsNormalized() {
        assertEquals("date" to "20260829", LabelParser.classify("2026 08-29"))
        assertEquals("20260829", LabelParser.parse(emptyList(), "2026 08-29").productionDate)
        assertEquals("20260829", LabelParser.parse(emptyList(), "生产日期: 2026 08-29").productionDate)
        assertEquals("20260829", LabelParser.parse(listOf("2026 08-29"), "").productionDate)
    }

    @Test
    fun missingLeadingZeroAndSpacesInsideDigitsAreNormalized() {
        assertEquals("date" to "20260809", LabelParser.classify("2026 8 9"))
        assertEquals("date" to "20260829", LabelParser.classify("2026-08-2 9"))
        assertEquals("date" to "20260829", LabelParser.classify("202 6-08-29"))
        assertEquals("20260829", LabelParser.parse(emptyList(), "LOT-A / 2026-08-2 9").productionDate)
    }

    @Test
    fun impossibleCalendarDatesAreRejectedButLeapDayIsAccepted() {
        assertNull(LabelParser.classify("2026-02-29"))
        assertNull(LabelParser.classify("2026-04-31"))
        assertEquals("date" to "20240229", LabelParser.classify("2024-02-29"))
        assertEquals("19000101", LabelParser.parse(emptyList(), "生产日期: 2026-02-29").productionDate)
    }

    @Test
    fun otherMaterialAndSerialClassificationIsUnchanged() {
        assertEquals("material10" to "3011211002", LabelParser.classify("30112 11002"))
        assertEquals("sn" to "SCAG2529704B3", LabelParser.classify("SCAG2529704B3"))
    }
}
