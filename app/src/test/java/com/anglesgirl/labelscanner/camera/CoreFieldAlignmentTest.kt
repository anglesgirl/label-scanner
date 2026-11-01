package com.anglesgirl.labelscanner.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreFieldAlignmentTest {
    @Test fun centersFieldsBelowUpperBarcodeWithoutRequiringProductHeading() {
        val b = LabelContentLocator.locateCore(frame(), W, H)
        assertNotNull("fields below the upper barcode must be enough", b)
        b!!
        assertTrue("exclude upper barcode/heading from alignment: $b", b.top > 0.32f)
        assertTrue("include lower material/SN barcode: $b", b.bottom > 0.74f)
        assertTrue("keep field block near operator's center: $b", (b.top + b.bottom) / 2 in 0.45f..0.65f)
    }

    @Test fun addingProductHeadingDoesNotPullFieldFrameUpward() {
        val withoutHeading = LabelContentLocator.locateCore(frame(), W, H)!!
        val withHeading = LabelContentLocator.locateCore(frame(heading = true), W, H)!!
        assertEquals(withoutHeading.top, withHeading.top, 0.005f)
        assertEquals(withoutHeading.bottom, withHeading.bottom, 0.005f)
    }

    @Test fun followsFieldsWhenOperatorRepositionsLabel() {
        val initial = LabelContentLocator.locateCore(frame(), W, H)!!
        val moved = LabelContentLocator.locateCore(frame(dy = 35), W, H)!!
        assertEquals(35f / H, moved.top - initial.top, 0.015f)
        assertEquals(35f / H, moved.bottom - initial.bottom, 0.015f)
    }

    @Test fun doesNotInventCoreRegionForHeadingOnlyOrBlank() {
        assertNull(LabelContentLocator.locateCore(IntArray(W * H) { WHITE }, W, H))
        val pixels = IntArray(W * H) { WHITE }
        text(pixels, 45); text(pixels, 80); barcode(pixels, 120, 160)
        assertNull(LabelContentLocator.locateCore(pixels, W, H))
    }

    @Test fun singleCaptionBelowBarcodeIsNotEnough() {
        val pixels = IntArray(W * H) { WHITE }
        barcode(pixels, 120, 160); text(pixels, 190)
        assertNull(LabelContentLocator.locateCore(pixels, W, H))
    }

    private fun frame(dy: Int = 0, heading: Boolean = false): IntArray {
        val pixels = IntArray(W * H) { WHITE }
        if (heading) { text(pixels, 45 + dy); text(pixels, 80 + dy) }
        barcode(pixels, 110 + dy, 150 + dy)
        text(pixels, 190 + dy)
        text(pixels, 230 + dy)
        barcode(pixels, 245 + dy, 278 + dy)
        text(pixels, 310 + dy)
        barcode(pixels, 328 + dy, 365 + dy)
        return pixels
    }

    private fun text(p: IntArray, baseline: Int) {
        for (x in 85 until 275) for (y in baseline - 12 until baseline) {
            if (x % 10 < 5 && (x + y) % 4 != 0) p[y * W + x] = BLACK
        }
    }

    private fun barcode(p: IntArray, top: Int, bottom: Int) {
        for (x in 80 until 280) for (y in top until bottom) {
            p[y * W + x] = if (x % 7 < 3) BLACK else WHITE
        }
    }

    companion object {
        private const val W = 360
        private const val H = 480
        private val WHITE = 0xffeeeeee.toInt()
        private val BLACK = 0xff222222.toInt()
    }
}
