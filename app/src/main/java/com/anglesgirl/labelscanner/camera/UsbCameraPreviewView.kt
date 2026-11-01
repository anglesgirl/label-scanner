package com.anglesgirl.labelscanner.camera

import android.content.Context
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.TextureView

/** Direct UVC producer surface: no legacy GL thread, EGL wrappers or common.jar dependency. */
class UsbCameraPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : TextureView(context, attrs, defStyleAttr) {
    private var frameWidth = 640
    private var frameHeight = 480

    fun setAspectRatio(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        frameWidth = width
        frameHeight = height
        updateTransform()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateTransform()
    }

    private fun updateTransform() {
        if (width <= 0 || height <= 0) return
        val frameRatio = frameWidth.toFloat() / frameHeight
        val viewRatio = width.toFloat() / height
        val scaleX = if (frameRatio < viewRatio) frameRatio / viewRatio else 1f
        val scaleY = if (frameRatio > viewRatio) viewRatio / frameRatio else 1f
        setTransform(Matrix().apply { setScale(scaleX, scaleY, width / 2f, height / 2f) })
    }
}
