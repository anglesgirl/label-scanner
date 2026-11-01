package com.anglesgirl.labelscanner.camera

import com.anglesgirl.labelscanner.model.LabelParser
import com.anglesgirl.labelscanner.model.BatchHeaderParser
import java.util.Locale

/**
 * Incremental, offline consensus for live camera frames. Every observation is parsed on its own;
 * fields from different frames are never combined to manufacture a complete label.
 */
class LiveSampleConsensus(private val headerOnly: Boolean = false) {
    data class Progress(
        val sampleCount: Int,
        val matchingCount: Int,
        val result: BurstConsensus.Result?,
    )

    private data class LabelKey(
        val material: String,
        val date: String,
        val serial: String,
    )

    private data class Sample(
        val sequence: Long,
        val barcodes: List<String>,
        val barcodeIdentities: Set<String>,
        val ocrText: String,
        val label: LabelKey?,
    )

    private data class Cluster(val label: LabelKey, val samples: List<Sample>)

    private val history = ArrayDeque<Sample>()
    private var nextSequence = 0L

    @Synchronized
    fun observe(barcodes: List<String>, ocrText: String): Progress {
        // Do not select a first barcode: retain every distinct non-empty value from this frame.
        val codes = barcodes.map(String::trim).filter(String::isNotEmpty).distinct()
        val label = parseCompleteLabel(codes, ocrText)
        val sample = Sample(
            sequence = nextSequence++,
            barcodes = codes,
            barcodeIdentities = codes.map(::barcodeIdentity).toSet(),
            ocrText = ocrText,
            label = label,
        )
        history.addLast(sample)
        while (history.size > MAX_SAMPLES) history.removeFirst()

        val clusters = matchingClusters()
        val largest = clusters.maxOfOrNull { it.samples.size } ?: 0
        val winners = clusters.filter { it.samples.size == largest && largest >= REQUIRED_MATCHES }
        val result = if (winners.size == 1) buildResult(winners.single()) else null
        return Progress(history.size, largest, result)
    }

    @Synchronized
    fun reset() {
        history.clear()
    }

    /** Complete fields must all come from this one frame; LabelParser supplies existing rules. */
    private fun parseCompleteLabel(barcodes: List<String>, ocrText: String): LabelKey? {
        if (barcodes.isEmpty() && !headerOnly) return null
        if (headerOnly) {
            val material = BatchHeaderParser.extractMaterialCode(ocrText)
                ?.trim()?.uppercase(Locale.ROOT)?.takeIf(String::isNotEmpty) ?: return null
            val date = BatchHeaderParser.extractProductionDate(ocrText) ?: return null
            return LabelKey(material, date, "")
        }
        // LabelParser keeps the first serial-like value it sees. Sort only its parsing input so
        // decode-order jitter cannot change the tuple; the original complete code set is retained.
        val parsed = LabelParser.parse(barcodes.sortedWith(compareBy<String> { barcodeIdentity(it) }.thenBy { it }), ocrText)
        val materialClass = LabelParser.classify(parsed.materialCode.trim()) ?: return null
        if (materialClass.first !in setOf("material10", "material12")) return null
        val material = LabelParser.normalizeMaterial(materialClass.second)

        // LabelParser intentionally fills a missing date with 19000101. That synthetic fallback
        // is not evidence and is excluded; classify also enforces the parser's real-date rules.
        val dateClass = LabelParser.classify(parsed.productionDate.trim()) ?: return null
        if (dateClass.first != "date" || dateClass.second == "19000101") return null

        val serial = parsed.serialNumber.trim().let { raw ->
            if (raw.isEmpty()) "" else {
                val classified = LabelParser.classify(raw)
                if (classified?.first == "sn") classified.second.uppercase(Locale.ROOT)
                else raw.uppercase(Locale.ROOT)
            }
        }
        return LabelKey(material, dateClass.second, serial)
    }

    /**
     * Group only complete tuples with a common decoded barcode identity. The per-frame barcode
     * set may vary; frames sharing any one stable code can vote together. Do not use transitive
     * overlap here: a frame that sees codes from two labels must not bridge their vote clusters.
     */
    private fun matchingClusters(): List<Cluster> {
        val byLabel = history.filter { it.label != null }.groupBy { it.label!! }
        val result = mutableListOf<Cluster>()
        for ((label, samples) in byLabel) {
            if (headerOnly) {
                result += Cluster(label, samples)
                continue
            }
            val seenSampleSets = mutableSetOf<Set<Long>>()
            val byIdentity = mutableMapOf<String, MutableList<Sample>>()
            for (sample in samples) {
                for (identity in sample.barcodeIdentities) {
                    byIdentity.getOrPut(identity) { mutableListOf() }.add(sample)
                }
            }
            for (matchingSamples in byIdentity.values) {
                val sampleSet = matchingSamples.map { it.sequence }.toSet()
                if (seenSampleSets.add(sampleSet)) result += Cluster(label, matchingSamples)
            }
        }
        return result
    }

    private fun buildResult(cluster: Cluster): BurstConsensus.Result {
        val identityCounts = mutableMapOf<String, Int>()
        val rawByIdentity = mutableMapOf<String, MutableSet<String>>()
        for (sample in cluster.samples) {
            for (raw in sample.barcodes) {
                val identity = barcodeIdentity(raw)
                identityCounts[identity] = (identityCounts[identity] ?: 0) + 1
                rawByIdentity.getOrPut(identity) { linkedSetOf() }.add(raw)
            }
        }
        val confirmed = identityCounts.filterValues { it >= REQUIRED_MATCHES }.keys
            .flatMap { rawByIdentity[it].orEmpty() }.toSet()

        // Canonical fields are safer than selecting a potentially noisy individual OCR frame.
        val date = cluster.label.date
        val readableDate = "${date.substring(0, 4)}-${date.substring(4, 6)}-${date.substring(6, 8)}"
        val canonicalOcr = if (headerOnly) {
            "机种：${cluster.label.material}\n生产日期：$readableDate"
        } else {
            val serialLine = cluster.label.serial.takeIf(String::isNotEmpty)?.let { "\nSN: $it" }.orEmpty()
            // Pure value lines avoid LabelParser classifying an English field label as an SN.
            "${cluster.label.material}\n$readableDate$serialLine"
        }

        // Keep noisy observations internal to voting; only confirmed data reaches result screens.
        return BurstConsensus.Result(
            barcodes = confirmed.sorted(),
            ocrText = canonicalOcr,
            unconfirmedBarcodes = emptyList(),
            unconfirmedOcrLines = emptyList(),
            frameCount = history.size,
        )
    }

    private fun barcodeIdentity(raw: String): String {
        val trimmed = raw.trim()
        val classified = LabelParser.classify(trimmed)
        val normalized = when (classified?.first) {
            "material10", "material12" -> "material:${LabelParser.normalizeMaterial(classified.second)}"
            "date" -> "date:${classified.second}"
            "ean" -> "ean:${classified.second}"
            "sn" -> "sn:${classified.second.uppercase(Locale.ROOT)}"
            "box" -> "box:${classified.second.uppercase(Locale.ROOT)}"
            else -> "raw:$trimmed"
        }
        return normalized
    }

    companion object {
        const val REQUIRED_MATCHES = 3
        private const val MAX_SAMPLES = 32
    }
}
