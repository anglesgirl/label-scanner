package com.anglesgirl.labelscanner.camera

import kotlin.math.abs

/** Quality-first handheld shutter gate. Motion is allowed; blur and unrelated targets are not. */
internal class HandheldCaptureGate {
    data class Result(val geometryStable: Boolean, val ready: Boolean)
    private data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val x get() = (left + right) / 2f
        val y get() = (top + bottom) / 2f
        val width get() = right - left
        val height get() = bottom - top
    }
    private data class Sample(val at: Long, val clear: Boolean)

    companion object {
        const val SHARPNESS_THRESHOLD = 120.0
        private const val WINDOW_MS = 900L
        private const val MISS_GRACE_MS = 650L
        private const val MIN_CLEAR_SPAN_MS = 120L
    }

    private val samples = ArrayDeque<Sample>()
    private var previous: Box? = null
    private var anchor: Box? = null
    private var lastSeenAt: Long? = null

    fun reset() {
        samples.clear()
        previous = null
        anchor = null
        lastSeenAt = null
    }

    /** Brief misses preserve evidence, but never trigger a photo on their own. */
    fun miss(now: Long) {
        if (lastSeenAt?.let { now < it || now - it > MISS_GRACE_MS } != false) reset()
    }

    fun observe(
        now: Long,
        corners: List<Pair<Float, Float>>,
        sharpness: Double,
        cameraAdjusting: Boolean,
    ): Result {
        if (cameraAdjusting || corners.size != 4 || corners.any { (x, y) ->
                !x.isFinite() || !y.isFinite() || x !in 0f..1f || y !in 0f..1f
            }) {
            reset()
            return Result(false, false)
        }
        val box = Box(corners.minOf { it.first }, corners.minOf { it.second },
            corners.maxOf { it.first }, corners.maxOf { it.second })
        if (box.width <= 0f || box.height <= 0f) {
            reset()
            return Result(false, false)
        }
        if (lastSeenAt?.let { now < it || now - it > MISS_GRACE_MS } == true) reset()
        while (samples.isNotEmpty() && now - samples.first().at > WINDOW_MS) samples.removeFirst()
        if (samples.isEmpty()) anchor = null
        // These are target-identity limits, NOT a stillness requirement. Ordinary tremor may move
        // the label by several percent in opposite directions between two perfectly sharp frames.
        val compatible = previous?.let { similar(box, it, 0.20f) } == true &&
            anchor?.let { similar(box, it, 0.22f) } == true
        if (!compatible) {
            samples.clear()
            anchor = box
        }
        previous = box
        lastSeenAt = now
        while (samples.size >= 5) samples.removeFirst()
        val clear = sharpness.isFinite() && sharpness >= SHARPNESS_THRESHOLD
        samples.addLast(Sample(now, clear))
        val clearSamples = samples.filter { it.clear }
        // Two fresh clear observations suffice. Intervening shaky/blurred frames do not restart
        // a stationary-frame countdown; the CURRENT frame must still be sharp.
        val ready = compatible && clear && clearSamples.size >= 2 &&
            clearSamples.last().at - clearSamples.first().at >= MIN_CLEAR_SPAN_MS
        return Result(compatible, ready)
    }

    private fun similar(a: Box, b: Box, centerLimit: Float): Boolean {
        val overlapWidth = (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0f)
        val overlapHeight = (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0f)
        val overlap = overlapWidth * overlapHeight / minOf(a.width * a.height, b.width * b.height)
        return abs(a.x - b.x) <= centerLimit && abs(a.y - b.y) <= centerLimit &&
            a.width / b.width in 0.60f..1.65f && a.height / b.height in 0.60f..1.65f && overlap >= 0.35f
    }
}
