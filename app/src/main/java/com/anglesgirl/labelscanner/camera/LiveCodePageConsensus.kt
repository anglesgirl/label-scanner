package com.anglesgirl.labelscanner.camera

/** Keeps every distinct code found while the camera is swept over one page. */
internal class LiveCodePageConsensus {
    data class Pending(val value: String, val reads: Int)
    data class Progress(
        val sampleCount: Int,
        val confirmedCodes: List<String>,
        val newlyConfirmed: List<String>,
        val pending: List<Pending>,
    )

    private data class Candidate(val value: String, var reads: Int = 0, var confirmed: Boolean = false)

    private val candidates = linkedMapOf<String, Candidate>()
    private var sampleCount = 0

    @Synchronized
    fun observe(codes: List<String>): Progress {
        sampleCount++
        val newlyConfirmed = mutableListOf<String>()
        codes.map(String::trim).filter(String::isNotEmpty).distinct().forEach { value ->
            val candidate = candidates.getOrPut(value) { Candidate(value) }
            if (!candidate.confirmed) {
                candidate.reads++
                if (candidate.reads >= REQUIRED_READS) {
                    candidate.confirmed = true
                    newlyConfirmed += candidate.value
                }
            }
        }
        return Progress(
            sampleCount = sampleCount,
            confirmedCodes = candidates.values.filter { it.confirmed }.map { it.value },
            newlyConfirmed = newlyConfirmed,
            pending = candidates.values.filterNot { it.confirmed }
                .map { Pending(it.value, it.reads) }.takeLast(MAX_PENDING_SHOWN),
        )
    }

    @Synchronized
    fun confirmedCodes(): List<String> = candidates.values.filter { it.confirmed }.map { it.value }

    @Synchronized
    fun reset() {
        candidates.clear()
        sampleCount = 0
    }

    companion object {
        const val REQUIRED_READS = 3
        private const val MAX_PENDING_SHOWN = 5
    }
}
