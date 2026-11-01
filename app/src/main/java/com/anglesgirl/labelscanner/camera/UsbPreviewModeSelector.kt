package com.anglesgirl.labelscanner.camera

/** A USB preview size, kept Android-free so negotiation order can be unit-tested. */
internal data class UsbPreviewSize(val width: Int, val height: Int)

/** The first size/format pair accepted by the camera. */
internal data class UsbPreviewMode(val size: UsbPreviewSize, val format: Int)

/** Tries sizes in caller-provided priority order and every format before moving to the next size. */
internal object UsbPreviewModeSelector {
    fun firstWorking(
        sizes: List<UsbPreviewSize>,
        formats: List<Int>,
        trySetPreview: (UsbPreviewSize, Int) -> Boolean,
    ): UsbPreviewMode? {
        for (size in sizes) {
            for (format in formats) {
                if (trySetPreview(size, format)) return UsbPreviewMode(size, format)
            }
        }
        return null
    }
}
