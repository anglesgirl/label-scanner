package com.anglesgirl.labelscanner.camera

/**
 * Conservative, lightweight label-content locator for camera frames.
 *
 * Finds barcode-and-text content rather than paper boundaries. Whole-content fallback associates
 * text above barcodes; core alignment instead prefers field rows below a persistent barcode near
 * the operator's center. Returned bounds are normalized, never paper/cropping corners. This is
 * not an OCR engine, barcode decoder, or semantic guarantee that a barcode begins with 69.
 */
internal object LabelContentLocator {
    data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float)

    private data class BarcodeRun(
        val top: Int,
        val bottom: Int,
        val left: Int,
        val right: Int,
        val strength: Long
    )

    private data class TextLines(val top: Int, val bottom: Int, val left: Int, val right: Int, val lineCount: Int)

    /** Locate a label's text + barcode content. Invalid, uniform, or weak evidence returns null. */
    fun locate(argb: IntArray, width: Int, height: Int): Bounds? =
        locateInternal(argb, width, height, coreOnly = false)

    /**
     * Alignment/AF region, NOT a crop. Prefer the field block below a barcode (typically the
     * 69 code), including subsequent SN/material barcodes. No product heading is required.
     * This is texture geometry, not semantic recognition of the barcode value or field names.
     */
    fun locateCore(argb: IntArray, width: Int, height: Int): Bounds? =
        locateInternal(argb, width, height, coreOnly = true)

    private fun locateInternal(argb: IntArray, width: Int, height: Int, coreOnly: Boolean): Bounds? {
        if (width < 24 || height < 24 || width.toLong() * height != argb.size.toLong()) return null

        // Bound work on large preview frames. Nearest sampling preserves barcode transitions;
        // coordinates remain equivalent because the final result is normalized.
        val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(width, height))
        val w = maxOf(1, (width * scale).toInt())
        val h = maxOf(1, (height * scale).toInt())
        val gray = IntArray(w * h)
        for (y in 0 until h) {
            val sy = minOf(height - 1, (y / scale).toInt())
            for (x in 0 until w) {
                val sx = minOf(width - 1, (x / scale).toInt())
                val pixel = argb[sy * width + sx]
                val r = (pixel ushr 16) and 0xff
                val g = (pixel ushr 8) and 0xff
                val b = pixel and 0xff
                gray[y * w + x] = (77 * r + 150 * g + 29 * b) ushr 8
            }
        }

        val edgeThreshold = 18
        val edge = Array(h) { y ->
            IntArray(maxOf(0, w - 1)).also { row ->
                val base = y * w
                for (x in row.indices) {
                    val delta = gray[base + x + 1] - gray[base + x]
                    row[x] = if (kotlin.math.abs(delta) >= edgeThreshold) 1 else 0
                }
            }
        }

        val runs = findBarcodeRuns(edge, w, h)
        if (runs.isEmpty()) return null
        if (coreOnly) return coreBounds(edge, runs, w, h)
        data class Located(val text: TextLines, val left: Int, val top: Int, val right: Int, val bottom: Int, val strength: Long)
        val located = runs.asSequence()
            // The target may contain weaker barcode bars than an adjacent partial label/QR.
            .filter { it.left > w * 0.12f }
            .mapNotNull { barcode ->
                val aligned = runs.filter { run ->
                    run !== barcode &&
                        overlapRatio(run.left, run.right, barcode.left, barcode.right) >= 0.35f &&
                        run.top >= barcode.top - h * 0.24f && run.top <= barcode.bottom + h * 0.12f
                }
                val barcodeTop = minOf(barcode.top, aligned.minOfOrNull { it.top } ?: barcode.top)
                val barcodeBottom = maxOf(barcode.bottom, aligned.maxOfOrNull { it.bottom } ?: barcode.bottom)
                val barcodeLeft = minOf(barcode.left, aligned.minOfOrNull { it.left } ?: barcode.left)
                val barcodeRight = maxOf(barcode.right, aligned.maxOfOrNull { it.right } ?: barcode.right)
                val text = findTextAbove(edge, w, h, barcodeLeft, barcodeRight, barcodeTop) ?: return@mapNotNull null
                Located(text, barcodeLeft, barcodeTop, barcodeRight, barcodeBottom,
                    barcode.strength + aligned.sumOf { it.strength })
            }
            .toList()
        val best = located.maxWithOrNull(
            compareBy<Located> { h - it.text.top }
                .thenBy { it.right - it.left }
                .thenBy { it.strength }
        ) ?: return null
        val contentLeft = minOf(best.left, best.text.left).toFloat()
        val contentRight = maxOf(best.right, best.text.right).toFloat()
        val horizontalMargin = w * 0.012f
        val verticalMargin = h * 0.012f
        val left = (contentLeft - horizontalMargin).coerceAtLeast(0f) / w
        val right = (contentRight + horizontalMargin).coerceAtMost(w.toFloat()) / w
        val top = (best.text.top - verticalMargin).coerceAtLeast(0f) / h
        val bottom = (best.bottom + verticalMargin).coerceAtMost(h.toFloat()) / h
        if (right - left < 0.12f || bottom - top < 0.08f) return null
        return Bounds(left, top, right, bottom)
    }

    private fun coreBounds(edge: Array<IntArray>, runs: List<BarcodeRun>, w: Int, h: Int): Bounds? {
        data class Candidate(val bounds: Bounds, val distance: Float, val lines: Int)
        // Text glyphs can also have many vertical edges. A field anchor needs bars persisting
        // through almost the entire band, not just a dense product-heading row.
        val barcodes = runs.filter { run ->
            val height = run.bottom - run.top
            if (height < maxOf(14, (h * 0.035f).toInt())) return@filter false
            var persistent = 0
            for (x in run.left until minOf(run.right, w - 1)) {
                var count = 0
                for (y in run.top until run.bottom) count += edge[y][x]
                if (count >= height * 0.85f) persistent++
            }
            persistent >= maxOf(8, (w * 0.025f).toInt())
        }
        val candidates = barcodes.mapNotNull { anchor ->
            val from = anchor.bottom + maxOf(2, (h * 0.008f).toInt())
            val to = minOf(h, anchor.bottom + (h * 0.55f).toInt())
            val text = findTextInRange(edge, w, h, anchor.left, anchor.right, from, to)
                ?: return@mapNotNull null
            // Do not join a distant, unrelated label below an isolated barcode.
            if (text.top - anchor.bottom > h * 0.16f) return@mapNotNull null
            val lowerCodes = barcodes.filter {
                it.top >= text.top && it.top <= text.bottom + h * 0.12f &&
                    overlapRatio(it.left, it.right, anchor.left, anchor.right) >= 0.45f
            }
            val left = minOf(text.left, lowerCodes.minOfOrNull { it.left } ?: text.left)
            val right = maxOf(text.right, lowerCodes.maxOfOrNull { it.right } ?: text.right)
            val bottom = maxOf(text.bottom, lowerCodes.maxOfOrNull { it.bottom } ?: text.bottom)
            val b = Bounds(
                (left.toFloat() / w - 0.018f).coerceAtLeast(0f),
                (text.top.toFloat() / h - 0.012f).coerceAtLeast(0f),
                (right.toFloat() / w + 0.018f).coerceAtMost(1f),
                (bottom.toFloat() / h + 0.012f).coerceAtMost(1f),
            )
            if (b.right - b.left < 0.12f || b.bottom - b.top < 0.08f) return@mapNotNull null
            val dx = (b.left + b.right) / 2 - 0.5f
            val dy = (b.top + b.bottom) / 2 - 0.5f
            Candidate(b, dx * dx + dy * dy, text.lineCount)
        }
        // Follow the block the operator centers, not whichever heading is highest in the frame.
        return candidates.minWithOrNull(compareBy<Candidate> { it.distance }.thenByDescending { it.lines })?.bounds
    }

    private fun findBarcodeRuns(edge: Array<IntArray>, w: Int, h: Int): List<BarcodeRun> {
        if (w < 32 || h < 24) return emptyList()
        val window = (w / 5).coerceIn(24, w - 1)
        val step = maxOf(3, window / 5)
        val minPeak = maxOf(7, (window * 0.105f).toInt())
        val minTotal = maxOf(12, (w * 0.035f).toInt())
        val peak = IntArray(h)
        val rowTotal = IntArray(h)

        for (y in 0 until h) {
            val row = edge[y]
            var total = 0
            for (v in row) total += v
            rowTotal[y] = total
            var best = 0
            var x = 0
            while (x + window <= row.size) {
                var count = 0
                for (i in x until x + window) count += row[i]
                if (count > best) best = count
                x += step
            }
            // Always include a window flush with the right edge.
            val endStart = row.size - window
            if (endStart >= 0) {
                var count = 0
                for (i in endStart until row.size) count += row[i]
                if (count > best) best = count
            }
            peak[y] = best
        }

        val active = BooleanArray(h) { y -> peak[y] >= minPeak && rowTotal[y] >= minTotal }
        val result = ArrayList<BarcodeRun>()
        var y = 0
        while (y < h) {
            while (y < h && !active[y]) y++
            if (y >= h) break
            val start = y
            var last = y
            var gap = 0
            var strength = 0L
            while (y < h) {
                if (active[y]) {
                    last = y
                    gap = 0
                    strength += peak[y].toLong()
                } else {
                    gap++
                    if (gap > 2) break
                }
                y++
            }
            val end = last + 1
            if (end - start >= maxOf(9, (h * 0.022f).toInt())) {
                val histogram = IntArray(w - 1)
                for (rowY in start..last) {
                    for (x in histogram.indices) histogram[x] += edge[rowY][x]
                }
                val threshold = maxOf(2, ((last - start + 1) * 0.06f).toInt())
                // Separate side labels/folds from the dense barcode band instead of using the
                // global left/right extrema (the same failure mode as brightness segmentation).
                // Barcode bars are separated by white gaps; bridge modest gaps but keep distinct
                // side labels/folds apart. The left-label candidate is rejected above by position.
                val maxGap = maxOf(5, (w * 0.07f).toInt())
                var first = -1
                var final = -1
                var count = 0
                var mass = 0L
                fun finishSegment() {
                    if (first >= 0 && final - first + 1 >= maxOf(18, (w * 0.055f).toInt()) &&
                        count >= maxOf(6, (w * 0.018f).toInt())) {
                        val candidate = BarcodeRun(start, end, first, final + 1, mass)
                        result += candidate
                    }
                }
                for (x in histogram.indices) {
                    if (histogram[x] < threshold) continue
                    if (first < 0 || x - final > maxGap) {
                        finishSegment()
                        first = x
                        count = 0
                        mass = 0
                    }
                    final = x
                    count++
                    mass += histogram[x]
                }
                finishSegment()
            }
            // Resume at the first inactive row after the tolerated gap, not past a following run.
            y = maxOf(y - gap, last + 1)
        }
        return result
    }

    private fun findTextAbove(
        edge: Array<IntArray>, w: Int, h: Int, barcodeLeft: Int, barcodeRight: Int, barcodeTop: Int
    ): TextLines? {
        return findTextInRange(edge, w, h, barcodeLeft, barcodeRight,
            maxOf(0, barcodeTop - (h * 0.64f).toInt()),
            (barcodeTop - maxOf(3, (h * 0.012f).toInt())).coerceAtLeast(0))
    }

    private fun findTextInRange(
        edge: Array<IntArray>, w: Int, h: Int, barcodeLeft: Int, barcodeRight: Int,
        from: Int, to: Int,
    ): TextLines? {
        val left = barcodeLeft.coerceIn(0, w - 2)
        val right = barcodeRight.coerceIn(left + 2, w - 1)
        val range = right - left
        val fromY = from.coerceIn(0, h)
        val toY = to.coerceIn(fromY, h)
        if (toY - fromY < 6) return null
        val rowCounts = IntArray(h)
        val minEdges = maxOf(3, (range * 0.018f).toInt())
        for (y in fromY until toY) {
            var count = 0
            for (x in left until right) count += edge[y][x]
            if (count >= minEdges) rowCounts[y] = count
        }

        val lines = ArrayList<IntRange>()
        var y = fromY
        while (y < toY) {
            while (y < toY && rowCounts[y] == 0) y++
            if (y >= toY) break
            val start = y
            var last = y
            var gap = 0
            while (y < toY) {
                if (rowCounts[y] > 0) {
                    last = y
                    gap = 0
                } else {
                    gap++
                    if (gap > 2) break
                }
                y++
            }
            val lineHeight = last - start + 1
            if (lineHeight in 1..maxOf(8, (h * 0.045f).toInt())) lines += start..last
            y = maxOf(y - gap, last + 1)
        }
        // Keep separated text blocks together: a label may have a short heading near its top and
        // detailed fields nearer the barcode. A single isolated text row remains insufficient.
        if (lines.size < 2) return null
        val selected = lines
        val firstY = selected.first().first
        val lastY = selected.last().last
        val activeRows = selected.sumOf { it.last - it.first + 1 }
        if (activeRows < 4 && selected.size < 3) return null

        val xHistogram = IntArray(range)
        for (line in selected) {
            for (yy in line) {
                for (x in left until right) xHistogram[x - left] += edge[yy][x]
            }
        }
        val xThreshold = maxOf(1, (activeRows * 0.10f).toInt())
        var textLeft = xHistogram.indexOfFirst { it >= xThreshold }
        var textRight = xHistogram.indexOfLast { it >= xThreshold }
        if (textLeft < 0 || textRight - textLeft < maxOf(5, range / 12)) return null
        textLeft += left
        textRight += left + 1
        return TextLines(firstY, lastY + 1, textLeft, textRight, selected.size)
    }

    private fun overlapRatio(aLeft: Int, aRight: Int, bLeft: Int, bRight: Int): Float {
        val overlap = (minOf(aRight, bRight) - maxOf(aLeft, bLeft)).coerceAtLeast(0)
        return overlap.toFloat() / minOf(aRight - aLeft, bRight - bLeft).coerceAtLeast(1)
    }

    private const val MAX_SIDE = 480
}
