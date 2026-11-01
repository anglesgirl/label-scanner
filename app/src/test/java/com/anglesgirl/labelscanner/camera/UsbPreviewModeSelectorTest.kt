package com.anglesgirl.labelscanner.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsbPreviewModeSelectorTest {
    private val mjpeg = 6
    private val yuyv = 4

    @Test fun triesBothFormatsAtPreferredSizeBeforeFallbackSizes() {
        val sizes = listOf(UsbPreviewSize(640, 480), UsbPreviewSize(800, 600))
        val attempts = mutableListOf<Pair<UsbPreviewSize, Int>>()

        val result = UsbPreviewModeSelector.firstWorking(sizes, listOf(mjpeg, yuyv)) { size, format ->
            attempts += size to format
            size == UsbPreviewSize(800, 600) && format == mjpeg
        }

        assertEquals(UsbPreviewMode(UsbPreviewSize(800, 600), mjpeg), result)
        assertEquals(
            listOf(
                UsbPreviewSize(640, 480) to mjpeg,
                UsbPreviewSize(640, 480) to yuyv,
                UsbPreviewSize(800, 600) to mjpeg,
            ),
            attempts,
        )
    }

    @Test fun returnsNullWhenAllAdvertisedCombinationsFail() {
        val result = UsbPreviewModeSelector.firstWorking(
            listOf(UsbPreviewSize(640, 480)),
            listOf(mjpeg, yuyv),
        ) { _, _ -> false }

        assertNull(result)
    }
}
