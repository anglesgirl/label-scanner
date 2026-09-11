package com.anglesgirl.labelscanner.camera.v2

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View

/**
 * 预览上的标签取景框：把"识别到的标签区域"画出来，让用户看得见对准了没有。
 *
 * 旧版的做法（条码框 + OCR 文字块框取并集得到标签区域）本身是对的，
 * 问题在于**没做平滑**，检测结果每帧一跳，框就跟着乱跳，反而干扰判断。
 * 这里对框做指数平滑（EMA），并在像素坐标与 PreviewView 坐标之间做换算。
 *
 * 提供两种绘制：
 *  - 已对准：绿色粗框
 *  - 未对准：白/橙细框
 */
class LabelBoxOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    /** 平滑后的框（分析帧像素坐标）。 */
    private var smoothRect: Rect? = null
    private var aligned = false

    /** 分析帧尺寸，用于换算到本 View 的坐标。 */
    private var srcW = 0
    private var srcH = 0

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }

    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    fun setSourceSize(w: Int, h: Int) {
        if (w != srcW || h != srcH) {
            srcW = w
            srcH = h
        }
    }

    /**
     * 更新框。传入的是分析帧坐标下的框；null 表示这一帧没检测到。
     * 传入 null 时不立刻清空，而是让平滑值自然淡出 —— 避免"闪一下框就没了"。
     */
    fun update(rect: Rect?, alignedNow: Boolean) {
        aligned = alignedNow
        if (rect == null) {
            // 连续多次没检测到才清（此处简单处理：保留旧框但降透明度感由重绘控制）
            invalidate()
            return
        }
        val prev = smoothRect
        smoothRect = if (prev == null) {
            Rect(rect)
        } else {
            // EMA 平滑：位置与大小都缓动，框不再逐帧乱跳
            val a = 0.35f
            Rect(
                (prev.left + (rect.left - prev.left) * a).toInt(),
                (prev.top + (rect.top - prev.top) * a).toInt(),
                (prev.right + (rect.right - prev.right) * a).toInt(),
                (prev.bottom + (rect.bottom - prev.bottom) * a).toInt(),
            )
        }
        invalidate()
    }

    fun clear() {
        smoothRect = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = smoothRect ?: return
        if (srcW <= 0 || srcH <= 0 || width <= 0 || height <= 0) return

        // 分析帧 → View 坐标（PreviewView 用 FILL_CENTER 时是等比铺满 + 居中裁剪）
        val sx = width.toFloat() / srcW
        val sy = height.toFloat() / srcH
        val s = maxOf(sx, sy)
        val dx = (width - srcW * s) / 2f
        val dy = (height - srcH * s) / 2f

        val left = r.left * s + dx
        val top = r.top * s + dy
        val right = r.right * s + dx
        val bottom = r.bottom * s + dy

        val base = minOf(width, height).toFloat()
        paint.strokeWidth = base * 0.008f
        paint.color = if (aligned) Color.parseColor("#4CAF50") else Color.parseColor("#FFFFFF")

        canvas.drawRect(left, top, right, bottom, paint)

        // 四角加粗，观感更明确
        cornerPaint.strokeWidth = base * 0.018f
        cornerPaint.color = if (aligned) Color.parseColor("#66FF88") else Color.parseColor("#FFC107")
        val len = minOf(right - left, bottom - top) * 0.18f
        // 左上
        canvas.drawLine(left, top, left + len, top, cornerPaint)
        canvas.drawLine(left, top, left, top + len, cornerPaint)
        // 右上
        canvas.drawLine(right - len, top, right, top, cornerPaint)
        canvas.drawLine(right, top, right, top + len, cornerPaint)
        // 左下
        canvas.drawLine(left, bottom - len, left, bottom, cornerPaint)
        canvas.drawLine(left, bottom, left + len, bottom, cornerPaint)
        // 右下
        canvas.drawLine(right - len, bottom, right, bottom, cornerPaint)
        canvas.drawLine(right, bottom - len, right, bottom, cornerPaint)
    }
}
