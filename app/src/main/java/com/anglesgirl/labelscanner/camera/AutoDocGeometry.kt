package com.anglesgirl.labelscanner.camera

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Pure geometry helpers. All coordinates are normalized in the upright, cropped analysis image. */
internal object AutoDocGeometry {
    const val TARGET_FILL = 0.80f
    const val ZOOM_DEADBAND_RATIO = 0.04f
    const val ZOOM_DEADBAND_ABSOLUTE = 0.06f

    /** An edge on the image border is not evidence that the whole paper was segmented. */
    fun hasCompleteOutline(corners: List<Pair<Float, Float>>): Boolean =
        corners.size == 4 && corners.all { (x, y) ->
            x.isFinite() && y.isFinite() && x in 0.008f..0.992f && y in 0.008f..0.992f
        }

    /**
     * Computes an absolute camera zoom ratio, not a zoom multiplier. The requested magnification
     * is applied to [currentZoom], then capped so a centered camera zoom cannot clip any side of
     * the detected label's axis-aligned envelope (including labels away from image center).
     * This is zoom-IN assistance: never zoom out because the label is already large, and never
     * request the sub-1x ultra-wide lens. Reopening the camera starts a fresh 1x session.
     */
    fun targetZoom(
        minX: Float,
        maxX: Float,
        minY: Float,
        maxY: Float,
        currentZoom: Float,
        minZoom: Float,
        maxZoom: Float,
        targetFill: Float = TARGET_FILL,
    ): Float {
        if (!listOf(minX, maxX, minY, maxY, currentZoom, minZoom, maxZoom, targetFill).all { it.isFinite() }) {
            return currentZoom.takeIf { it.isFinite() } ?: 1f
        }
        val left = minX.coerceIn(0f, 1f)
        val right = maxX.coerceIn(0f, 1f)
        val top = minY.coerceIn(0f, 1f)
        val bottom = maxY.coerceIn(0f, 1f)
        val width = right - left
        val height = bottom - top
        if (width <= 0f || height <= 0f) return currentZoom.coerceIn(minZoom, maxZoom)

        val wantedScale = (targetFill.coerceIn(0.05f, 0.95f) / max(width, height)).coerceAtLeast(1f)
        val safeScale = min(
            min(edgeSafeScale(left, right), edgeSafeScale(top, bottom)),
            Float.MAX_VALUE,
        ).coerceAtLeast(1f)
        val lower = max(minZoom, min(1f, maxZoom)).coerceAtLeast(0.1f)
        val upper = maxZoom.coerceAtLeast(lower)
        val current = currentZoom.coerceIn(lower, upper)
        val capped = (current * min(wantedScale, safeScale)).coerceIn(lower, upper)
        val deadband = max(ZOOM_DEADBAND_ABSOLUTE, current * ZOOM_DEADBAND_RATIO)
        return if (abs(capped - currentZoom) <= deadband) currentZoom else capped
    }

    /** Maximum multiplier that keeps an interval wholly within the frame while zooming at center. */
    private fun edgeSafeScale(low: Float, high: Float): Float {
        val leftScale = if (low < 0.5f) 0.5f / (0.5f - low) else Float.POSITIVE_INFINITY
        val rightScale = if (high > 0.5f) 0.5f / (high - 0.5f) else Float.POSITIVE_INFINITY
        return min(leftScale, rightScale).coerceAtLeast(1f)
    }

    fun geometryStable(current: List<Pair<Float, Float>>, previous: List<Pair<Float, Float>>?, threshold: Float): Boolean {
        if (current.size != 4 || previous?.size != 4) return false
        return current.indices.all { i ->
            abs(current[i].first - previous[i].first) < threshold &&
                abs(current[i].second - previous[i].second) < threshold
        }
    }

    fun zoomSettled(targetZoom: Float, currentZoom: Float): Boolean {
        return abs(targetZoom - currentZoom) <= max(ZOOM_DEADBAND_ABSOLUTE, currentZoom * ZOOM_DEADBAND_RATIO)
    }
}
