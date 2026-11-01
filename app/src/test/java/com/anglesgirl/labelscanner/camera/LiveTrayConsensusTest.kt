package com.anglesgirl.labelscanner.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveTrayConsensusTest {
    @Test
    fun confirmsTheSamePreferredPalletCodeAcrossThreeFrames() {
        val consensus = LiveTrayConsensus()
        val code = "00907346462626020366"

        assertNull(consensus.observe(listOf(code), "").result)
        assertNull(consensus.observe(listOf(code), "").result)
        assertEquals(code, consensus.observe(listOf(code), "").result)
    }

    @Test
    fun uniqueSsccCanBeConfirmedWhenOtherBarcodesArePresent() {
        val consensus = LiveTrayConsensus()
        val sscc = "00907346462626020366"
        val allCodes = listOf("L1260066290100144", "6936358013017", sscc)

        repeat(2) { assertNull(consensus.observe(allCodes, "").result) }
        assertEquals(sscc, consensus.observe(allCodes, "").result)
    }

    @Test
    fun multipleNonSsccCodesDoNotAutoSelect() {
        val consensus = LiveTrayConsensus()
        val ambiguous = listOf("L1260066290100144", "6936358013017")

        repeat(6) {
            val progress = consensus.observe(ambiguous, "")
            assertNull(progress.preferredCode)
            assertNull(progress.result)
            assertEquals(2, progress.candidateCount)
        }
    }

    @Test
    fun currentCodeMustMatchTheStableWinner() {
        val consensus = LiveTrayConsensus()
        val stable = "00907346462626020366"
        val different = "00812345678901234567"

        repeat(3) { consensus.observe(listOf(stable), "") }
        assertNull(consensus.observe(listOf(different), "").result)
    }

    @Test
    fun competingCandidatesDoNotTriggerUntilOneHasThreeVotes() {
        val consensus = LiveTrayConsensus()
        val first = "00907346462626020366"
        val second = "00812345678901234567"

        repeat(2) { assertNull(consensus.observe(listOf(first), "").result) }
        assertNull(consensus.observe(listOf(second), "").result)
        val tie = consensus.observe(listOf(second), "")
        assertNull(tie.result)
        assertEquals(first, consensus.observe(listOf(first), "").result)
    }
}
