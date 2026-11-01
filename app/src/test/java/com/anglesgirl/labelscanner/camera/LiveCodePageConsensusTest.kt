package com.anglesgirl.labelscanner.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveCodePageConsensusTest {
    @Test
    fun sweepsAndCollectsEveryCodeInOrderAfterThreeReadsDespiteBadFrames() {
        val consensus = LiveCodePageConsensus()
        assertTrue(consensus.observe(listOf("CODE-1", "CODE-2", "CODE-1")).confirmedCodes.isEmpty())
        assertTrue(consensus.observe(listOf("CODE-2", "CODE-1")).newlyConfirmed.isEmpty())
        consensus.observe(emptyList()) // shake/blur: does not erase earlier votes
        val third = consensus.observe(listOf("CODE-1", "CODE-2"))

        assertEquals(listOf("CODE-1", "CODE-2"), third.newlyConfirmed)
        assertEquals(listOf("CODE-1", "CODE-2"), third.confirmedCodes)
        assertEquals(listOf("CODE-1", "CODE-2"), consensus.confirmedCodes())
    }

    @Test
    fun laterCodesAreAppendedOnceAndUnconfirmedReadsAreNotExported() {
        val consensus = LiveCodePageConsensus()
        repeat(2) { consensus.observe(listOf("FIRST", "NOISY")) }
        consensus.observe(listOf("FIRST", "SECOND"))
        consensus.observe(listOf("SECOND"))
        val progress = consensus.observe(listOf("SECOND"))

        assertEquals(listOf("FIRST", "SECOND"), progress.confirmedCodes)
        assertEquals(2, progress.pending.single { it.value == "NOISY" }.reads)
    }
}
