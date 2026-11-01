package com.anglesgirl.labelscanner.camera

import com.anglesgirl.labelscanner.model.LabelParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSampleConsensusTest {
    private val barcodeA = "6901234567892"
    private val barcodeB = "6912345678901"
    private val materialA = "1234567890"
    private val materialB = "9876543210"
    private val dateA = "2026-09-17"
    private val dateB = "2026-09-18"

    @Test fun twoMatchingFramesAreNotEnoughButThirdSucceedsImmediately() {
        val consensus = LiveSampleConsensus()
        assertEquals(3, LiveSampleConsensus.REQUIRED_MATCHES)
        repeat(2) { index ->
            val progress = consensus.observe(listOf(barcodeA), "$materialA\n$dateA")
            assertEquals(index + 1, progress.matchingCount)
            assertNull(progress.result)
        }
        val completed = consensus.observe(listOf(barcodeA), "$materialA\n$dateA")
        assertEquals(3, completed.sampleCount)
        assertEquals(3, completed.matchingCount)
        assertNotNull(completed.result)
        assertEquals(listOf(barcodeA), completed.result!!.barcodes)
        assertTrue(completed.result.unconfirmedBarcodes.isEmpty())
        assertTrue(completed.result.unconfirmedOcrLines.isEmpty())
    }

    @Test fun listHeaderUsesLabeledFieldsWithoutBarcodeAndBadFramesDoNotResetVotes() {
        val consensus = LiveSampleConsensus(headerOnly = true)
        val header = "终端物料编号 208130300401\n需求日期 09/19/2026"
        assertEquals(0, consensus.observe(emptyList(), "Lexmark物料编号 CTL-35\n需求日期 09/19/2026").matchingCount)
        assertEquals(1, consensus.observe(emptyList(), header).matchingCount)
        assertEquals(2, consensus.observe(emptyList(), header).matchingCount)
        assertEquals(2, consensus.observe(emptyList(), "机种 CTL-35\n需求日期 09/31/2026").matchingCount)

        val confirmed = consensus.observe(emptyList(), header)
        assertEquals(3, confirmed.matchingCount)
        assertNotNull(confirmed.result)
        assertTrue(confirmed.result!!.barcodes.isEmpty())
        assertEquals("2026-09-19", confirmed.result.ocrText.substringAfter("生产日期："))
        assertEquals("208130300401", com.anglesgirl.labelscanner.model.BatchHeaderParser.extractMaterialCode(confirmed.result.ocrText))
        assertEquals("20260919", com.anglesgirl.labelscanner.model.BatchHeaderParser.extractProductionDate(confirmed.result.ocrText))
    }

    @Test fun terminalMaterialNumberAndDemandDateConfirmAfterThreeReads() {
        val consensus = LiveSampleConsensus(headerOnly = true)
        val header = "Lexmark物料编号 CTL-350Y\n终端物料编号\n208130300401\n需求日期 09/19/2026"
        assertEquals(1, consensus.observe(emptyList(), header).matchingCount)
        assertEquals(2, consensus.observe(emptyList(), header).matchingCount)
        assertEquals(2, consensus.observe(emptyList(), "终端物料编号\n208130300401\n需求日期 09/31/2026").matchingCount)
        val confirmed = consensus.observe(emptyList(), header)
        assertEquals(3, confirmed.matchingCount)
        assertNotNull(confirmed.result)
        assertTrue(confirmed.result!!.ocrText.contains("208130300401"))
        assertTrue(confirmed.result.ocrText.contains("2026-09-19"))
    }

    @Test fun duplicateCodesAndRepeatedOcrInOneFrameAreOnlyOneVote() {
        val consensus = LiveSampleConsensus()
        val duplicatedOcr = "$materialA\n$dateA\n$materialA\n$dateA"
        val first = consensus.observe(listOf(barcodeA, barcodeA, barcodeA), duplicatedOcr)
        assertEquals(1, first.matchingCount)
        assertNull(first.result)
        assertNull(consensus.observe(listOf(barcodeA), "$materialA\n$dateA").result)
        assertNotNull(consensus.observe(listOf(barcodeA), "$materialA\n$dateA").result)
    }

    @Test fun emptyAndNoisyFramesDoNotErasePriorVotesOrBecomeCandidates() {
        val consensus = LiveSampleConsensus()
        consensus.observe(listOf(barcodeA), "$materialA\n$dateA")
        consensus.observe(emptyList(), "")
        consensus.observe(listOf("noise"), "unreadable")
        consensus.observe(listOf(barcodeA), "$materialA\n$dateB")
        consensus.observe(listOf(barcodeA), "$materialA\n$dateA")
        consensus.observe(emptyList(), "")
        val third = consensus.observe(listOf(barcodeA), "$materialA\n$dateA")
        assertEquals(7, third.sampleCount)
        assertEquals(3, third.matchingCount)
        assertNotNull(third.result)
        assertTrue(third.result!!.unconfirmedBarcodes.isEmpty())
        assertTrue(third.result.unconfirmedOcrLines.isEmpty())
    }

    @Test fun fieldsFromDifferentFramesAreNeverCombinedAndLabelsStayIsolated() {
        val consensus = LiveSampleConsensus()
        repeat(2) { assertNull(consensus.observe(listOf(barcodeA), "$materialA\n$dateA").result) }
        repeat(2) { assertNull(consensus.observe(listOf(barcodeB), "$materialB\n$dateB").result) }
        val completedA = consensus.observe(listOf(barcodeA), "$materialA\n$dateA")
        assertEquals(3, completedA.matchingCount)
        assertNotNull(completedA.result)
        assertEquals(listOf(barcodeA), completedA.result!!.barcodes)
        assertTrue(completedA.result.unconfirmedBarcodes.isEmpty())
    }

    @Test fun sameMaterialWithDifferentDatesCannotBeCombinedIntoOneTuple() {
        val consensus = LiveSampleConsensus()
        repeat(2) { assertNull(consensus.observe(listOf(barcodeA), "$materialA\n$dateA").result) }
        repeat(2) { assertNull(consensus.observe(listOf(barcodeA), "$materialA\n$dateB").result) }
        val progress = consensus.observe(listOf(barcodeA), "$materialA\n$dateB")
        assertEquals(3, progress.matchingCount)
        assertNotNull(progress.result)
        assertEquals("20260918", LabelParser.parse(progress.result!!.barcodes, progress.result.ocrText).productionDate)
    }

    @Test fun parserDefaultAndInvalidDatesAreNotTreatedAsRealDateEvidence() {
        val consensus = LiveSampleConsensus()
        repeat(5) {
            val progress = consensus.observe(listOf(barcodeA), "$materialA\n2026-02-29")
            assertEquals(0, progress.matchingCount)
            assertNull(progress.result)
        }
        assertEquals("19000101", LabelParser.parse(emptyList(), "2026-02-29").productionDate)
        repeat(2) { assertNull(consensus.observe(listOf(barcodeA), "$materialA\n$dateA").result) }
        assertNotNull(consensus.observe(listOf(barcodeA), "$materialA\n$dateA").result)
    }

    @Test fun onlyStableWinnerCodesReachResultsAndNoiseDoesNotMakeLongLists() {
        val consensus = LiveSampleConsensus()
        val extra1 = "CA70450P10052278"
        val extra2 = "PA70450P10052279"
        consensus.observe(listOf(barcodeA, extra1, "UNCLASSIFIED-1"), "$materialA\n$dateA")
        consensus.observe(listOf(barcodeA, extra2, "UNCLASSIFIED-2"), "$materialA\n$dateA")
        val result = consensus.observe(listOf(barcodeA, extra1), "$materialA\n$dateA").result!!
        assertEquals(listOf(barcodeA), result.barcodes)
        assertTrue(result.unconfirmedBarcodes.isEmpty())
        assertTrue(result.unconfirmedOcrLines.isEmpty())
        val reparsed = LabelParser.parse(result.barcodes, result.ocrText)
        assertEquals("123456789001", reparsed.materialCode)
        assertEquals("20260917", reparsed.productionDate)
        assertEquals("", reparsed.serialNumber)
    }

    @Test fun allIndependentlyStableBarcodesAreKept() {
        val consensus = LiveSampleConsensus()
        val extra = "CA70450P10052278"
        repeat(2) { assertNull(consensus.observe(listOf(barcodeA, extra), "$materialA\n$dateA").result) }
        val result = consensus.observe(listOf(barcodeA, extra), "$materialA\n$dateA").result!!
        assertEquals(setOf(barcodeA, extra), result.barcodes.toSet())
    }

    @Test fun serialNumberIsPartOfTheTupleAndBarcodeSubsetsCanVary() {
        val consensus = LiveSampleConsensus()
        val code1 = "UNIT0001"
        val code2 = "UNIT0002"
        consensus.observe(listOf(barcodeA, code1), "$materialA\n$dateA\nSN: UNIT0001")
        consensus.observe(listOf(barcodeA, code2), "$materialA\n$dateA\nSN: UNIT0002")
        consensus.observe(listOf(code1, barcodeA), "$materialA\n$dateA\nSN: UNIT0001")
        val split = consensus.observe(listOf(barcodeA, code2), "$materialA\n$dateA\nSN: UNIT0002")
        assertEquals(2, split.matchingCount)
        assertNull(split.result)
        val winner = consensus.observe(listOf(code1, barcodeA), "$materialA\n$dateA\nSN: UNIT0001")
        assertEquals(3, winner.matchingCount)
        assertNotNull(winner.result)
        assertTrue(winner.result!!.ocrText.contains("SN: UNIT0001"))
        assertTrue(winner.result.unconfirmedBarcodes.isEmpty())
        assertEquals(setOf(barcodeA, code1), winner.result.barcodes.toSet())
        val reparsed = LabelParser.parse(winner.result.barcodes, winner.result.ocrText)
        assertEquals("123456789001", reparsed.materialCode)
        assertEquals("20260917", reparsed.productionDate)
        assertEquals("UNIT0001", reparsed.serialNumber)
    }

    @Test fun incompleteFramesCannotManufactureACompleteTuple() {
        val consensus = LiveSampleConsensus()
        repeat(4) { assertNull(consensus.observe(listOf(barcodeA), materialA).result) }
        repeat(4) { assertNull(consensus.observe(listOf(barcodeA), dateA).result) }
        repeat(2) { assertNull(consensus.observe(listOf(barcodeA), "$materialA\n$dateA").result) }
        assertNotNull(consensus.observe(listOf(barcodeA), "$materialA\n$dateA").result)
    }

    @Test fun nonCoreAndInconsistentOcrIsNotReturnedForManualReview() {
        val consensus = LiveSampleConsensus()
        repeat(2) { index ->
            assertNull(consensus.observe(listOf(barcodeA), "$materialA\n$dateA\n供应商：示例工厂 $index").result)
        }
        val result = consensus.observe(listOf(barcodeA), "$materialA\n$dateA\n供应商：其他噪声").result!!
        assertTrue(result.unconfirmedOcrLines.isEmpty())
        val parsed = LabelParser.parse(result.barcodes, result.ocrText)
        assertEquals("123456789001", parsed.materialCode)
        assertEquals("20260917", parsed.productionDate)
    }

    @Test fun historyIsBoundedAndResetClearsIt() {
        val consensus = LiveSampleConsensus()
        repeat(40) { consensus.observe(emptyList(), "noise $it") }
        assertEquals(32, consensus.observe(emptyList(), "").sampleCount)
        consensus.reset()
        assertEquals(1, consensus.observe(emptyList(), "").sampleCount)
        assertEquals(0, consensus.observe(emptyList(), "").matchingCount)
    }
}
