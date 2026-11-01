package com.anglesgirl.labelscanner.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageDecodePolicyTest {
    @Test fun previewAndFourKPhotosKeepTheirOriginalPixels() {
        assertEquals(1, ImageDecodePolicy.sampleSize(1920, 1080))
        assertEquals(1, ImageDecodePolicy.sampleSize(4096, 3072))
    }

    @Test fun fortyEightMegapixelPhotoMustNotDecodeAtFullSize() {
        assertEquals(2, ImageDecodePolicy.sampleSize(8064, 6048))
    }

    @Test fun justOverTheLimitMustAlreadyBeSampled() {
        assertEquals(2, ImageDecodePolicy.sampleSize(4097, 3072))
        assertEquals(4, ImageDecodePolicy.sampleSize(8193, 6144))
    }

    @Test fun portraitUsesTheSameMemoryBound() {
        assertEquals(2, ImageDecodePolicy.sampleSize(6048, 8064))
    }

    @Test fun oneHundredEightMegapixelPhotoIsBounded() {
        val sample = ImageDecodePolicy.sampleSize(12000, 9000)
        assertEquals(4, sample)
        assertTrue((12000 + sample - 1) / sample <= 4096)
    }

    @Test fun invalidBoundsStillGiveASafeDecoderDefault() {
        assertEquals(1, ImageDecodePolicy.sampleSize(-1, -1))
    }
}
