package com.anglesgirl.labelscanner.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BurstConsensusTest {
    @Test fun occasionalMisreadCannotBeatRepeatedValue() {
        val good = "6901234567892"
        val bad = "6912345678901"
        val result = BurstConsensus.resolve(
            listOf(
                Frame(barcodes = listOf(good), ocrText = ""),
                Frame(barcodes = listOf(good), ocrText = ""),
                Frame(barcodes = listOf(good), ocrText = ""),
                Frame(barcodes = listOf(good), ocrText = ""),
                Frame(barcodes = listOf(bad), ocrText = ""),
            ),
        )
        assertEquals(listOf(good), result.barcodes)
        assertEquals(listOf(bad), result.unconfirmedBarcodes)
    }

    @Test fun fewerThanThreeIndependentFramesNeverAutoConfirms() {
        val result = BurstConsensus.resolve(
            listOf(
                Frame(listOf("6901234567892"), "SN: AB123456"),
                Frame(listOf("6901234567892"), "SN: AB123456"),
            ),
        )
        assertTrue(result.barcodes.isEmpty())
        assertEquals(listOf("6901234567892"), result.unconfirmedBarcodes)
        assertTrue(result.ocrText.isEmpty())
        assertEquals(listOf("SN: AB123456"), result.unconfirmedOcrLines)
    }

    @Test fun duplicatesInsideOneFrameCountOnlyOnce() {
        val result = BurstConsensus.resolve(
            listOf(
                Frame(listOf("6901234567892", "6901234567892", "6901234567892"), ""),
                Frame(emptyList(), ""),
                Frame(emptyList(), ""),
            ),
        )
        assertTrue(result.barcodes.isEmpty())
        assertEquals(listOf("6901234567892"), result.unconfirmedBarcodes)
        assertEquals(3, result.frameCount)
    }

    @Test fun tiedConflictingEansAreBothLeftForReview() {
        val a = "6901234567892"
        val b = "6912345678901"
        val result = BurstConsensus.resolve(
            listOf(Frame(listOf(a), ""), Frame(listOf(a), ""), Frame(listOf(a), ""),
                Frame(listOf(b), ""), Frame(listOf(b), ""), Frame(listOf(b), "")),
        )
        assertTrue(result.barcodes.isEmpty())
        assertEquals(listOf(a, b).sorted(), result.unconfirmedBarcodes)
    }

    @Test fun allRepeatedSerialsAreKeptWhenTheyAreNotNearCompetitors() {
        val first = "ABCD1234"
        val second = "WXYZ9876"
        val result = BurstConsensus.resolve(
            listOf(
                Frame(listOf(first, second, first), ""),
                Frame(listOf(first, second), ""),
                Frame(listOf(first, second), ""),
            ),
        )
        assertEquals(listOf(first, second).sorted(), result.barcodes)
        assertTrue(result.unconfirmedBarcodes.isEmpty())
    }

    @Test fun emptyFramesDoNotDiluteAValidClassAndFrameCountIsStillReported() {
        val code = "6901234567892"
        val result = BurstConsensus.resolve(
            listOf(Frame(emptyList(), ""), Frame(listOf(code), ""), Frame(emptyList(), ""),
                Frame(listOf(code), ""), Frame(emptyList(), ""), Frame(listOf(code), "")),
        )
        assertEquals(listOf(code), result.barcodes)
        assertEquals(6, result.frameCount)
    }

    @Test fun barcodeAndOcrEvidenceAreVotedSeparately() {
        val barcode = "6901234567892"
        val ocrLine = "SN: ZXCV1234"
        val result = BurstConsensus.resolve(
            listOf(
                Frame(listOf(barcode), ocrLine),
                Frame(listOf(barcode), ocrLine),
                Frame(listOf(barcode), ""),
            ),
        )
        assertEquals(listOf(barcode), result.barcodes)
        assertTrue("two OCR frames cannot borrow the barcode's third vote", result.ocrText.isEmpty())
        assertEquals(listOf(ocrLine), result.unconfirmedOcrLines)
    }

    @Test fun nearCompetingSerialsNeedClearSixtyPercentWinnerAndOIsNotZero() {
        val letterO = "ABCDO123"
        val digitZero = "ABCD0123"
        val result = BurstConsensus.resolve(
            listOf(
                Frame(listOf(letterO), ""), Frame(listOf(letterO), ""),
                Frame(listOf(digitZero), ""), Frame(listOf(digitZero), ""),
            ),
        )
        assertTrue(result.barcodes.isEmpty())
        assertEquals(listOf(digitZero, letterO).sorted(), result.unconfirmedBarcodes)
    }

    @Test fun nearSerialsSeenTogetherInAFrameCanBothBeConfirmed() {
        val first = "ABCD1234"
        val second = "ABCD1235"
        val result = BurstConsensus.resolve(
            listOf(Frame(listOf(first, second), ""), Frame(listOf(first, second), ""),
                Frame(listOf(first, second), "")),
        )
        assertEquals(listOf(first, second).sorted(), result.barcodes)
        assertTrue(result.unconfirmedBarcodes.isEmpty())
    }

    @Test fun ocrWhitespaceIsNormalizedByLineWithoutCharacterLevelSplicing() {
        val line = "Material: 1234567890"
        val spaced = "  Material:    1234567890  "
        val result = BurstConsensus.resolve(
            listOf(Frame(emptyList(), line), Frame(emptyList(), spaced), Frame(emptyList(), line)),
        )
        assertEquals("Material: 1234567890", result.ocrText)
        assertTrue(result.unconfirmedOcrLines.isEmpty())
    }

    @Test fun repeatedButConflictingOcrMaterialsDoNotLeakIntoReparsing() {
        val good = "Material: 1234567890"
        val bad = "Material: 9876543210"
        val result = BurstConsensus.resolve(
            List(5) { Frame(emptyList(), good) } + List(3) { Frame(emptyList(), bad) },
        )
        assertEquals(good, result.ocrText)
        assertEquals(listOf(bad), result.unconfirmedOcrLines)
        val reparsed = com.anglesgirl.labelscanner.model.LabelParser.parse(result.barcodes, result.ocrText)
        assertEquals("123456789001", reparsed.materialCode)
    }

    @Test fun tiedOcrMaterialsAreBothManualEvenWhenBothHaveThreeVotes() {
        val first = "Material: 1234567890"
        val second = "Material: 9876543210"
        val result = BurstConsensus.resolve(
            List(3) { Frame(emptyList(), first) } + List(3) { Frame(emptyList(), second) },
        )
        assertTrue(result.ocrText.isEmpty())
        assertEquals(listOf(first, second).sorted(), result.unconfirmedOcrLines)
    }

    @Test fun aPluralityBelowSixtyPercentDoesNotWin() {
        val first = "6901234567892"
        val second = "6912345678901"
        val result = BurstConsensus.resolve(
            List(4) { Frame(listOf(first), "") } + List(3) { Frame(listOf(second), "") },
        )
        assertTrue(result.barcodes.isEmpty())
        assertEquals(listOf(first, second).sorted(), result.unconfirmedBarcodes)
    }

    @Test fun threeVersusTwoIsEnoughForARepeatedExactWinner() {
        val first = "ABCDO123"
        val second = "ABCD0123"
        val result = BurstConsensus.resolve(
            List(3) { Frame(listOf(first), "") } + List(2) { Frame(listOf(second), "") },
        )
        assertEquals(listOf(first), result.barcodes)
        assertEquals(listOf(second), result.unconfirmedBarcodes)
    }

    @Test fun duplicateOcrLinesInsideOnePhysicalFrameNeverBecomeThreeVotes() {
        val line = "SN: ABCD1234"
        val result = BurstConsensus.resolve(listOf(Frame(emptyList(), "$line\n$line\n$line")))
        assertTrue(result.ocrText.isEmpty())
        assertEquals(listOf(line), result.unconfirmedOcrLines)
    }

    @Test fun aUniqueHighResolutionBarcodeRemainsAvailableButNotAutomatic() {
        val result = BurstConsensus.resolve(
            List(15) { Frame(emptyList(), "") } + Frame(listOf("SN001,SN002,SN003"), ""),
        )
        assertEquals(16, result.frameCount)
        assertTrue(result.barcodes.isEmpty())
        assertEquals(listOf("SN001,SN002,SN003"), result.unconfirmedBarcodes)
    }

    private fun Frame(barcodes: List<String>, ocrText: String) =
        BurstConsensus.Frame(barcodes = barcodes, ocrText = ocrText)
}
