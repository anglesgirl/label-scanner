package com.anglesgirl.labelscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntegratedCodeTest {
    @Test
    fun recognizesMultiSerialSeparatorsButNotSingleBarcode() {
        assertTrue(IntegratedCode.isPayload("SN001,SN002"))
        assertTrue(IntegratedCode.isPayload("SN001，SN002"))
        assertTrue(IntegratedCode.isPayload("SN001;SN002"))
        assertTrue(IntegratedCode.isPayload("SN001；SN002"))
        assertFalse(IntegratedCode.isPayload("6936358047906"))
        assertFalse(IntegratedCode.isPayload("CT6JV0000D"))
    }

    @Test
    fun expandsIntegratedPayloadIntoTrimmedUniqueSerialNumbers() {
        assertEquals(
            listOf("SN001", "SN002", "SN003"),
            IntegratedCode.serialNumbers(" SN001, SN002，，SN001；SN003 "),
        )
    }

    @Test
    fun excludesPreviouslySavedSerialsAcrossSelectedCodes() {
        assertEquals(
            listOf("SN002", "SN003"),
            IntegratedCode.serialNumbersToSave(
                values = listOf("SN001,SN002", "SN002;SN003"),
                alreadySaved = setOf("SN001"),
            ),
        )
    }
}
