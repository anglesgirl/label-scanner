package com.anglesgirl.labelscanner.camera

/** Power-of-two decoding keeps even 48/108 MP camera images within a bounded bitmap size. */
internal object ImageDecodePolicy {
    fun sampleSize(width: Int, height: Int, maxEdge: Int = 4096): Int {
        require(maxEdge > 0)
        val longest = maxOf(width, height).toLong()
        var sample = 1
        while ((longest + sample - 1L) / sample > maxEdge) sample *= 2
        return sample
    }
}
