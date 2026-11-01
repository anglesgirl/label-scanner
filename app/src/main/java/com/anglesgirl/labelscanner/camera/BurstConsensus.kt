package com.anglesgirl.labelscanner.camera

import com.anglesgirl.labelscanner.model.LabelParser
import java.util.Locale
import kotlin.math.max

/**
 * Conservative, offline voting over independent OCR/barcode observations from a burst.
 * A frame contributes at most one vote for a normalized candidate. No values are synthesized.
 */
object BurstConsensus {
    data class Frame(val barcodes: List<String>, val ocrText: String)

    data class Result(
        val barcodes: List<String>,
        val ocrText: String,
        val unconfirmedBarcodes: List<String>,
        val unconfirmedOcrLines: List<String>,
        val frameCount: Int,
    )

    private const val MIN_VOTES = 3
    private const val MIN_SHARE = 0.60
    private val contestedClasses = setOf("material", "date", "ean")

    private data class Candidate(
        val category: String?,
        val key: String,
        val rawValues: MutableSet<String> = linkedSetOf(),
        val frames: MutableSet<Int> = linkedSetOf(),
    ) {
        val id: String get() = "${category ?: "?"}\u0000$key"
    }

    /** Per-source votes: barcode evidence and OCR evidence intentionally never reinforce each other. */
    private class Votes {
        val candidates = linkedMapOf<String, Candidate>()
        val classFrames = mutableMapOf<String, MutableSet<Int>>()

        fun add(frame: Int, category: String?, key: String, raw: String) {
            val id = "${category ?: "?"}\u0000$key"
            val candidate = candidates.getOrPut(id) { Candidate(category, key) }
            candidate.rawValues += raw
            // A repeated decode/line in one frame is still only one independent observation.
            candidate.frames += frame
            if (category != null) classFrames.getOrPut(category) { linkedSetOf() }.add(frame)
        }
    }

    fun resolve(frames: List<Frame>): Result {
        val barcodeVotes = Votes()
        val ocrVotes = Votes()
        val ocrLines = linkedMapOf<String, MutableSet<Int>>()
        val lineCoreCandidates = mutableMapOf<String, MutableSet<Pair<String, String>>>()

        frames.forEachIndexed { frameIndex, frame ->
            // Preserve every distinct decoded value; trim only accidental surrounding whitespace.
            frame.barcodes.map(String::trim).filter(String::isNotEmpty).distinct().forEach { raw ->
                val candidate = classifyValue(raw)
                barcodeVotes.add(frameIndex, candidate?.first, candidate?.second ?: raw, raw)
            }

            // Newlines are observations, not material to concatenate across frames. Collapse only
            // horizontal/repeated whitespace within each individual line.
            val linesThisFrame = frame.ocrText.lines().map(::normalizeLine).filter(String::isNotEmpty).distinct()
            linesThisFrame.forEach { line ->
                ocrLines.getOrPut(line) { linkedSetOf() }.add(frameIndex)
                val candidates = coreCandidates(line)
                lineCoreCandidates.getOrPut(line) { linkedSetOf() }.also { it.addAll(candidates) }
                candidates.forEach { (category, key) -> ocrVotes.add(frameIndex, category, key, line) }
            }
        }

        val acceptedBarcodes = acceptedCandidates(barcodeVotes)
        val barcodeOutput = barcodeVotes.candidates.values
            .filter { it.id in acceptedBarcodes }
            .flatMap { it.rawValues }
            .distinct()
            .sorted()
        val allBarcodeValues = barcodeVotes.candidates.values.flatMap { it.rawValues }.distinct().sorted()

        val acceptedOcr = acceptedCandidates(ocrVotes)
        val confirmedLines = ocrLines.keys.filter { line ->
            val count = ocrLines[line]?.size ?: 0
            count >= MIN_VOTES && lineCoreCandidates[line].orEmpty().all { (category, key) ->
                "${category}\u0000$key" in acceptedOcr
            }
        }.sorted()
        val confirmedLineSet = confirmedLines.toSet()
        val allOcrLines = ocrLines.keys.sorted()

        return Result(
            barcodes = barcodeOutput,
            ocrText = confirmedLines.joinToString("\n"),
            unconfirmedBarcodes = allBarcodeValues.filterNot { raw ->
                val candidate = classifyValue(raw)
                val id = "${candidate?.first ?: "?"}\u0000${candidate?.second ?: raw}"
                id in acceptedBarcodes
            },
            unconfirmedOcrLines = allOcrLines.filterNot { it in confirmedLineSet },
            frameCount = frames.size,
        )
    }

    /** Candidate keys for a complete value, including LabelParser's material/date normalization. */
    private fun classifyValue(value: String): Pair<String, String>? {
        val (field, normalized) = LabelParser.classify(value) ?: return null
        return when (field) {
            "material10", "material12" -> "material" to LabelParser.normalizeMaterial(normalized)
            "date" -> "date" to normalized
            "ean" -> "ean" to normalized
            "sn" -> "sn" to normalized.uppercase(Locale.ROOT)
            "box" -> "box" to normalized
            else -> field to normalized
        }
    }

    /**
     * Extract whole, independently present core values from a line. This is only a veto/gate for
     * confirming the original line; it never constructs a replacement line from fragments.
     */
    private fun coreCandidates(line: String): Set<Pair<String, String>> {
        val out = linkedSetOf<Pair<String, String>>()
        classifyValue(line)?.let(out::add)
        LabelParser.extractDateInline(line)?.let { out += "date" to it }

        // A line may contain more than one intact field (for example material + date). Classifying
        // whitespace-delimited tokens is safe: each token must itself be a complete recognized value.
        line.split(Regex("\\s+")).forEach { token -> classifyValue(token)?.let(out::add) }

        // Labeled SN values are not classifiable as a whole line, so recognize the intact token.
        if (Regex("(?:S\\s*/\\s*N|\\bSN\\b|序列号)", RegexOption.IGNORE_CASE).containsMatchIn(line)) {
            Regex("[A-Za-z0-9]{6,30}").findAll(line).forEach { match ->
                classifyValue(match.value)?.takeIf { it.first == "sn" }?.let(out::add)
            }
        }
        // BoxParser also extracts a 10–12 digit material from a SAP-prefixed line.
        if (Regex("^SAP\\.?\\b", RegexOption.IGNORE_CASE).containsMatchIn(line)) {
            Regex("\\d{10,12}").find(line)?.value?.let { classifyValue(it)?.let(out::add) }
        }

        // Downstream callers may parse these confirmed lines again. Include every core field that
        // LabelParser itself would expose, so a line with an uncertain value cannot leak through.
        // In particular, ignore its synthetic 19000101 missing-date default: it is not evidence.
        val parsed = LabelParser.parse(emptyList(), line)
        listOf(parsed.materialCode, parsed.ean69, parsed.serialNumber, parsed.boxCode)
            .filter(String::isNotBlank)
            .forEach { value -> classifyValue(value)?.let(out::add) }
        if (parsed.productionDate.isNotBlank() && parsed.productionDate != "19000101") {
            classifyValue(parsed.productionDate)?.let(out::add)
        }
        return out
    }

    private fun normalizeLine(line: String): String = line.trim().replace(Regex("\\s+"), " ")

    /** Apply the three-vote floor and conflict rules independently within each evidence source. */
    private fun acceptedCandidates(votes: Votes): Set<String> {
        val accepted = linkedSetOf<String>()
        val byCategory = votes.candidates.values.filter { it.category != null }.groupBy { it.category!! }

        // Material10/material12 share a canonical group. For material, date and EAN conflicts,
        // count only frames that contain a valid candidate of that field; ties never win.
        contestedClasses.forEach { category ->
            val candidates = byCategory[category].orEmpty()
            if (candidates.isEmpty()) return@forEach
            val denominator = votes.classFrames[category]?.size ?: 0
            val ranked = candidates.sortedWith(compareByDescending<Candidate> { it.frames.size }.thenBy { it.key })
            val leader = ranked.first()
            val strictLeader = ranked.size == 1 || leader.frames.size > ranked[1].frames.size
            if (leader.frames.size >= MIN_VOTES && denominator > 0 && strictLeader &&
                leader.frames.size.toDouble() / denominator >= MIN_SHARE
            ) accepted += leader.id
        }

        // Ordinary multi-code classes (including box and unrelated SNs) may contain multiple
        // legitimate values in one view: retain every independently repeated candidate.
        byCategory.filterKeys { it !in contestedClasses && it != "sn" }.values.flatten()
            .filter { it.frames.size >= MIN_VOTES }
            .forEach { accepted += it.id }

        val snCandidates = byCategory["sn"].orEmpty()
        for (candidate in snCandidates) {
            // Similar values are competitors only if they never co-occurred in a physical frame;
            // simultaneous reads are evidence that the label really contains two distinct codes.
            val competitors = snCandidates.filter { other ->
                other !== candidate && editDistanceAtMostOne(candidate.key, other.key) &&
                    candidate.frames.intersect(other.frames).isEmpty()
            }
            if (candidate.frames.size < MIN_VOTES) continue
            if (competitors.isEmpty()) {
                accepted += candidate.id
                continue
            }
            val denominator = (candidate.frames + competitors.flatMap { it.frames }).toSet().size
            val strictLeader = competitors.all { candidate.frames.size > it.frames.size }
            if (denominator > 0 && strictLeader &&
                candidate.frames.size.toDouble() / denominator >= MIN_SHARE
            ) accepted += candidate.id
        }

        // Unclassified barcodes/lines are voted by exact normalized value only.
        votes.candidates.values.filter { it.category == null && it.frames.size >= MIN_VOTES }
            .forEach { accepted += it.id }
        return accepted
    }

    /** Exact match, or one insertion/deletion/substitution; O and 0 are deliberately not merged. */
    private fun editDistanceAtMostOne(a: String, b: String): Boolean {
        if (a == b) return false
        if (max(a.length, b.length) - minOf(a.length, b.length) > 1) return false
        var i = 0
        var j = 0
        var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) { i++; j++ } else {
                edits++
                if (edits > 1) return false
                if (a.length > b.length) i++ else if (b.length > a.length) j++ else { i++; j++ }
            }
        }
        if (i < a.length || j < b.length) edits++
        return edits == 1
    }
}
