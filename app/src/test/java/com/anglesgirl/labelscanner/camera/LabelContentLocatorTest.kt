package com.anglesgirl.labelscanner.camera

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream

class LabelContentLocatorTest {
    @Test fun rejectsBlankAndSmoothGradient() {
        assertNull(LabelContentLocator.locate(image(320, 480) { _, _ -> 0xffeeeeee.toInt() }, 320, 480))
        val gradient = image(320, 480) { x, y ->
            val v = 205 + (x * 24 / 320) + (y * 18 / 480)
            argb(v.coerceAtMost(247))
        }
        assertNull(LabelContentLocator.locate(gradient, 320, 480))
    }

    @Test fun rejectsIsolatedPlasticFoldAndFlatPanel() {
        val fold = image(320, 480) { x, y ->
            when {
                y in 227..230 -> 0xff8d8d8d.toInt()
                y in 231..234 -> 0xfff8f8f8.toInt()
                else -> 0xffe7e7e7.toInt()
            }
        }
        assertNull(LabelContentLocator.locate(fold, 320, 480))

        val flatPanel = image(320, 480) { x, y ->
            if (x in 55..272 && y in 70..420) 0xfffafafa.toInt() else 0xffeeeeee.toInt()
        }
        assertNull(LabelContentLocator.locate(flatPanel, 320, 480))
    }

    @Test fun rejectsSingleTextRowEvenWhenBarcodeExists() {
        val frame = frameWithContent(textLines = 1)
        assertNull(LabelContentLocator.locate(frame, WIDTH, HEIGHT))
    }

    @Test fun locatesSyntheticTextAndStackedBarcodesWithoutUsingPanelEdges() {
        val bounds = LabelContentLocator.locate(frameWithContent(textLines = 2), WIDTH, HEIGHT)
        assertNotNull("expected text associated with a dense barcode", bounds)
        bounds!!
        assertTrue(bounds.left in 0.15f..0.30f)
        assertTrue(bounds.right in 0.72f..0.94f)
        assertTrue(bounds.top in 0.17f..0.32f)
        assertTrue(bounds.bottom in 0.78f..0.94f)
    }

    @Test fun locatesMainLabelInCroppedRealCameraScreenshot() {
        val resource = javaClass.getResourceAsStream("/com/anglesgirl/labelscanner/camera/label-camera-regression.gray")
        assertNotNull("real screenshot regression resource missing", resource)
        val input = DataInputStream(resource!!)
        val width = input.readInt()
        val height = input.readInt()
        val pixels = input.use { stream -> IntArray(width * height) { argb(stream.readUnsignedByte()) } }
        val bounds = LabelContentLocator.locate(pixels, width, height)
        assertNotNull("expected primary text+barcode label, not the partial QR at left", bounds)
        bounds!!
        // Original screenshot crop (0,330)-(720,1400), excluding status/torch/shutter UI.
        // Main label text starts near y=415 and the lower barcode ends near y=1100.
        assertTrue("left edge should exclude the partial left-hand label: $bounds", bounds.left > 0.12f)
        assertTrue("right edge should include main barcode: $bounds", bounds.right > 0.90f)
        assertTrue("top should include main label text: $bounds", bounds.top in 0.0f..0.11f)
        assertTrue("bottom should include both barcode rows: $bounds", bounds.bottom in 0.70f..0.95f)

        val lowContrast = pixels.map { pixel -> changeContrast(pixel, 0.72f) }.toIntArray()
        assertScreenshotStillLocated(lowContrast, width, height, "low contrast")
        val blurred = boxBlur3x3(pixels, width, height)
        assertScreenshotStillLocated(blurred, width, height, "slightly blurred")
    }

    private fun frameWithContent(textLines: Int): IntArray {
        val pixels = image(WIDTH, HEIGHT) { x, y ->
            // A weak, non-bordered plastic background and a broad flat label surface.
            val base = 226 + ((x + y) % 3)
            argb(base)
        }
        // Text strokes are composed of short dark/light transitions across two aligned rows.
        val textY = if (textLines == 1) listOf(178) else listOf(112, 169)
        for ((lineNo, baseline) in textY.withIndex()) {
            var x = 88
            var glyph = 0
            while (x < 264) {
                val gw = 5 + (glyph * 7 % 7)
                val gh = 11 + (glyph * 5 % 9)
                val top = baseline - gh
                for (yy in top until baseline) {
                    for (xx in x until minOf(x + gw, 264)) {
                        if (((xx + yy + glyph) % 4) != 0) pixels[yy * WIDTH + xx] = 0xff333333.toInt()
                    }
                }
                x += gw + 4 + ((glyph + lineNo) % 3)
                glyph++
            }
        }
        drawBarcode(pixels, 83, 285, 263, 347, seed = 2)
        drawBarcode(pixels, 92, 360, 272, 416, seed = 7)
        return pixels
    }

    private fun drawBarcode(pixels: IntArray, left: Int, top: Int, right: Int, bottom: Int, seed: Int) {
        var x = left
        var dark = true
        var i = 0
        while (x < right) {
            val barWidth = 2 + ((i * 7 + seed) % 4)
            val color = if (dark) 0xff161616.toInt() else 0xfff8f8f8.toInt()
            for (y in top until bottom) for (xx in x until minOf(x + barWidth, right)) {
                pixels[y * WIDTH + xx] = color
            }
            x += barWidth
            dark = !dark
            i++
        }
    }

    private fun image(width: Int, height: Int, color: (Int, Int) -> Int): IntArray =
        IntArray(width * height) { index -> color(index % width, index / width) }

    private fun assertScreenshotStillLocated(pixels: IntArray, width: Int, height: Int, variant: String) {
        val bounds = LabelContentLocator.locate(pixels, width, height)
        assertNotNull("expected main label in $variant screenshot variant", bounds)
        bounds!!
        assertTrue("$variant result must exclude the partial left label: $bounds", bounds.left > 0.12f)
        assertTrue("$variant result must include the main barcode: $bounds", bounds.right > 0.90f)
        assertTrue("$variant result must include upper text and lower barcode: $bounds", bounds.top < 0.11f && bounds.bottom > 0.70f)
    }

    private fun changeContrast(pixel: Int, factor: Float): Int {
        fun channel(shift: Int): Int = ((((pixel ushr shift) and 0xff) - 128) * factor + 128)
            .toInt().coerceIn(0, 255)
        return (pixel and -0x1000000) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun boxBlur3x3(source: IntArray, width: Int, height: Int): IntArray {
        val result = IntArray(source.size)
        for (y in 0 until height) for (x in 0 until width) {
            var sum = 0
            var count = 0
            for (dy in -1..1) for (dx in -1..1) {
                val xx = (x + dx).coerceIn(0, width - 1)
                val yy = (y + dy).coerceIn(0, height - 1)
                val pixel = source[yy * width + xx]
                sum += (77 * ((pixel ushr 16) and 0xff) + 150 * ((pixel ushr 8) and 0xff) + 29 * (pixel and 0xff)) ushr 8
                count++
            }
            result[y * width + x] = argb(sum / count)
        }
        return result
    }

    private fun argb(gray: Int): Int = 0xff000000.toInt() or
        (gray.coerceIn(0, 255) shl 16) or (gray.coerceIn(0, 255) shl 8) or gray.coerceIn(0, 255)

    companion object {
        private const val WIDTH = 360
        private const val HEIGHT = 480
    }
}
