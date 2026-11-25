package com.anglesgirl.labelscanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.min

/**
 * 可缩放查看的图片：双指缩放、单指拖动、双击放大/还原。
 * 单据照片点开放大时用，字小能拉近了看。
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val drawMatrix = Matrix()
    private var viewW = 0f
    private var viewH = 0f
    private var drawableW = 0f
    private var drawableH = 0f

    /** 相对初始适配的缩放倍数。 */
    private var scale = 1f
    private val maxScale = 4f

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val newScale = (scale * detector.scaleFactor).coerceIn(1f, maxScale)
                val applied = newScale / scale
                scale = newScale
                drawMatrix.postScale(applied, applied, detector.focusX, detector.focusY)
                fixBounds()
                imageMatrix = drawMatrix
                return true
            }
        })

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float,
            ): Boolean {
                drawMatrix.postTranslate(-dx, -dy)
                fixBounds()
                imageMatrix = drawMatrix
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val target = if (scale > 1.5f) 1f else 2f
                val factor = target / scale
                scale = target
                drawMatrix.postScale(factor, factor, e.x, e.y)
                fixBounds()
                imageMatrix = drawMatrix
                return true
            }
        })

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewW = w.toFloat()
        viewH = h.toFloat()
        fitImage()
    }

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        drawableW = (bm?.width ?: 0).toFloat()
        drawableH = (bm?.height ?: 0).toFloat()
        scale = 1f
        fitImage()
    }

    /** 初始按宽高比适配进视图并居中。 */
    private fun fitImage() {
        if (viewW <= 0 || viewH <= 0 || drawableW <= 0 || drawableH <= 0) return
        val s = min(viewW / drawableW, viewH / drawableH)
        drawMatrix.setScale(s, s)
        drawMatrix.postTranslate((viewW - drawableW * s) / 2f, (viewH - drawableH * s) / 2f)
        imageMatrix = drawMatrix
    }

    /** 图片别滑出视图：放大时边缘顶住，缩小时居中。 */
    private fun fixBounds() {
        val rect = RectF(0f, 0f, drawableW, drawableH)
        drawMatrix.mapRect(rect)
        var dx = 0f
        var dy = 0f
        if (rect.width() <= viewW) {
            dx = (viewW - rect.width()) / 2f - rect.left
        } else {
            if (rect.left > 0) dx = -rect.left
            if (rect.right < viewW) dx = viewW - rect.right
        }
        if (rect.height() <= viewH) {
            dy = (viewH - rect.height()) / 2f - rect.top
        } else {
            if (rect.top > 0) dy = -rect.top
            if (rect.bottom < viewH) dy = viewH - rect.bottom
        }
        drawMatrix.postTranslate(dx, dy)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        // 双指缩放进行时不处理单指拖动，免得两手一动图片乱跑。
        if (!scaleDetector.isInProgress) gestureDetector.onTouchEvent(event)
        return true
    }
}
