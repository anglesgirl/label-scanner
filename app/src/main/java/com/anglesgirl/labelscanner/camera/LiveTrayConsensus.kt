package com.anglesgirl.labelscanner.camera

import com.anglesgirl.labelscanner.model.TrayCodeParser

/** Requires the same unambiguous pallet candidate to recur across separate preview frames. */
class LiveTrayConsensus {
    data class Progress(
        val candidateCount: Int,
        val preferredCode: String?,
        val matchingCount: Int,
        val result: String?,
    )

    private val recentReads = mutableListOf<String?>()

    @Synchronized
    fun observe(barcodes: List<String>, ocrText: String): Progress {
        val candidates = TrayCodeParser.candidates(barcodes, ocrText)
        val preferred = TrayCodeParser.preferred(candidates)
        recentReads += preferred
        while (recentReads.size > WINDOW_SIZE) recentReads.removeAt(0)

        val counts = recentReads.filterNotNull().groupingBy { it }.eachCount()
        val maxCount = counts.values.maxOrNull() ?: 0
        val winners = counts.filterValues { it == maxCount }.keys
        val result = preferred?.takeIf {
            counts[it] == maxCount && maxCount >= REQUIRED_MATCHES && winners.size == 1
        }
        return Progress(candidates.size, preferred, if (preferred == null) 0 else counts[preferred] ?: 0, result)
    }

    @Synchronized
    fun reset() = recentReads.clear()

    companion object {
        const val REQUIRED_MATCHES = 3
        private const val WINDOW_SIZE = 5
    }
}
