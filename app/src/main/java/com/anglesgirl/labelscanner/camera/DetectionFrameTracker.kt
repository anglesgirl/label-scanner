package com.anglesgirl.labelscanner.camera

import kotlin.math.abs

/** UI-only tracking. Never smooth geometry used to approve sharpness or automatic capture. */
internal class DetectionFrameTracker {
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val centerX get() = (left + right) / 2f
        val centerY get() = (top + bottom) / 2f
        private val width get() = right - left
        private val height get() = bottom - top
        fun valid() = listOf(left, top, right, bottom).all { it.isFinite() && it in 0f..1f } &&
            width > 0f && height > 0f
        fun matches(other: Box): Boolean =
            abs(centerX - other.centerX) <= 0.075f && abs(centerY - other.centerY) <= 0.075f &&
                width / other.width in 0.72f..1.38f && height / other.height in 0.72f..1.38f &&
                maxOf(abs(left - other.left), abs(top - other.top),
                    abs(right - other.right), abs(bottom - other.bottom)) <= 0.10f
        fun follow(target: Box): Box {
            fun edge(current: Float, next: Float): Float {
                val delta = next - current
                return if (abs(delta) <= 0.006f) current
                    else current + (delta * 0.30f).coerceIn(-0.018f, 0.018f)
            }
            return Box(edge(left, target.left), edge(top, target.top),
                edge(right, target.right), edge(bottom, target.bottom))
        }
    }

    companion object { const val MISS_GRACE_MS = 450L }
    private var shown: Box? = null
    private var previous: Box? = null
    private var anchor: Box? = null
    private var kind: Int? = null
    private var samples = 0
    private var candidateAt = 0L
    private var lastSeenAt: Long? = null

    fun reset() {
        shown = null
        previous = null
        anchor = null
        kind = null
        samples = 0
        lastSeenAt = null
    }

    fun miss(now: Long): Box? {
        if (lastSeenAt?.let { now < it || now - it > MISS_GRACE_MS } != false) reset()
        return shown
    }

    /** Large jumps/type switches disappear, then reacquire; never animate across unrelated labels. */
    fun observe(now: Long, box: Box, sourceKind: Int, adjusting: Boolean = false): Box? {
        if (adjusting || !box.valid()) { reset(); return null }
        if (lastSeenAt?.let { now < it || now - it > MISS_GRACE_MS } == true) reset()
        val compatible = kind == sourceKind && previous?.matches(box) == true &&
            (shown?.matches(box) ?: anchor?.matches(box)) == true
        if (!compatible) {
            shown = null
            anchor = box
            candidateAt = now
            samples = 1
        } else samples++
        previous = box
        kind = sourceKind
        lastSeenAt = now
        if (shown == null && samples >= 3 && now - candidateAt >= 180L) shown = box
        else shown = shown?.follow(box)
        return shown
    }
}
