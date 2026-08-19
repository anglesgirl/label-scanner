package com.anglesgirl.labelscanner.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * 四角拖拽层：用户在预览图上拖四个角点框住标签。
 * 默认四角贴合 ImageView 显示区域，拍正时无需调整直接确认。
 */
class CropOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    /** 角点（View 坐标）。未初始化时为空。 */
    var corners: List<PointF> = emptyList()
        private set

    private val paintLine = Paint().apply {
        color = Color.parseColor("#1B9E4B")
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }
    private val paintHandle = Paint().apply {
        color = Color.parseColor("#1B9E4B")
        style = Paint.Style.FILL
    }
    private val handleRadius = 22f
    private var dragging = -1

    /** 用显示区域初始化四角（贴合图边） */
    fun initCorners(left: Float, top: Float, right: Float, bottom: Float) {
        if (corners.isEmpty()) {
            corners = listOf(
                PointF(left, top), PointF(right, top),
                PointF(right, bottom), PointF(left, bottom)
            )
            invalidate()
        }
    }

    /** 当前拖拽角在 View 内夹取 */
    fun setCorner(i: Int, x: Float, y: Float) {
        if (i in corners.indices) {
            corners[i].set(
                x.coerceIn(0f, width.toFloat()),
                y.coerceIn(0f, height.toFloat())
            )
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (corners.size != 4) return
        // 连线
        for (i in 0..3) {
            val a = corners[i]
            val b = corners[(i + 1) % 4]
            canvas.drawLine(a.x, a.y, b.x, b.y, paintLine)
        }
        // 角点手柄
        for (c in corners) {
            canvas.drawCircle(c.x, c.y, handleRadius, paintHandle)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                dragging = nearestCorner(event.x, event.y)
                return dragging >= 0
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging >= 0) {
                    setCorner(dragging, event.x, event.y)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = -1
            }
        }
        return super.onTouchEvent(event)
    }

    private fun nearestCorner(x: Float, y: Float): Int {
        var best = -1
        var bestD = handleRadius * 3f
        corners.forEachIndexed { i, c ->
            val d = hypot(c.x - x, c.y - y)
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }
}
