package com.anglesgirl.labelscanner.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * 预览上「微信扫码式」的挑码浮层。
 *
 * 交互（按用户描述实现）：
 *  - 画面里出现多个码时，**每个码旁边画一个箭头指向它**（不画矩形框、不遮挡码本身）
 *  - 点击某个码即选中；**选中后在箭头旁标序号 ① ② ③…**
 *  - 序号即"第几个扫的"，也就是第几箱
 *  - 已选中的码，箭头与序号持续保留（可以回头看到选过哪些）
 *
 * 坐标换算：分析帧坐标 → View 坐标（PreviewView 为 FILL_CENTER：等比铺满 + 居中裁剪）。
 */
class BarcodePickOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    /** 一个候选码：值 + 它在分析帧里的位置。 */
    data class Pickable(val value: String, val box: Rect)

    private var items: List<Pickable> = emptyList()

    /** 已选顺序（下标 0 → 序号 1）。value 为码值。 */
    private var picked: List<String> = emptyList()

    private var srcW = 0
    private var srcH = 0

    var onPick: ((String) -> Unit)? = null

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
    }

    private val screenRects = HashMap<String, Rect>()

    fun setSourceSize(w: Int, h: Int) {
        srcW = w
        srcH = h
    }

    /** 当前帧识别到的候选（每次刷新）。 */
    fun setItems(list: List<Pickable>) {
        items = list
        rebuildScreenRects()
        invalidate()
    }

    /** 已选顺序（外部维护，序号以它为准）。 */
    fun setPicked(list: List<String>) {
        picked = list
        invalidate()
    }

    private fun rebuildScreenRects() {
        screenRects.clear()
        if (srcW <= 0 || srcH <= 0 || width <= 0 || height <= 0) return
        val s = maxOf(width.toFloat() / srcW, height.toFloat() / srcH)
        val dx = (width - srcW * s) / 2f
        val dy = (height - srcH * s) / 2f
        for (it in items) {
            screenRects[it.value] = Rect(
                (it.box.left * s + dx).toInt(),
                (it.box.top * s + dy).toInt(),
                (it.box.right * s + dx).toInt(),
                (it.box.bottom * s + dy).toInt(),
            )
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildScreenRects()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (items.isEmpty()) return

        val base = minOf(width, height).toFloat()
        labelPaint.textSize = base * 0.036f

        for (it in items) {
            val r = screenRects[it.value] ?: continue
            val order = picked.indexOf(it.value)
            val isPicked = order >= 0

            // 微信式：直接在码的位置画方框，框住码本身（不画箭头、不加偏移）
            val color = if (isPicked) Color.parseColor("#FFD54F") else Color.parseColor("#33E1FF")
            fillPaint.color = color
            strokePaint.color = color
            strokePaint.strokeWidth = base * (if (isPicked) 0.008f else 0.005f)

            // 稍外扩一点，让框不压住码的边缘模块
            val pad = base * 0.008f
            val fr = RectF(
                r.left - pad, r.top - pad,
                r.right + pad, r.bottom + pad,
            )
            // 半透明底 + 描边，既突出又不遮挡码
            fillPaint.alpha = 46
            canvas.drawRoundRect(fr, base * 0.012f, base * 0.012f, fillPaint)
            fillPaint.alpha = 255
            canvas.drawRoundRect(fr, base * 0.012f, base * 0.012f, strokePaint)

            // 四角加粗（扫描框的经典视觉）
            val corner = minOf(fr.width(), fr.height()) * 0.22f
            strokePaint.strokeWidth = base * 0.011f
            val c = Path()
            // 左上
            c.moveTo(fr.left, fr.top + corner); c.lineTo(fr.left, fr.top); c.lineTo(fr.left + corner, fr.top)
            // 右上
            c.moveTo(fr.right - corner, fr.top); c.lineTo(fr.right, fr.top); c.lineTo(fr.right, fr.top + corner)
            // 右下
            c.moveTo(fr.right, fr.bottom - corner); c.lineTo(fr.right, fr.bottom); c.lineTo(fr.right - corner, fr.bottom)
            // 左下
            c.moveTo(fr.left + corner, fr.bottom); c.lineTo(fr.left, fr.bottom); c.lineTo(fr.left, fr.bottom - corner)
            canvas.drawPath(c, strokePaint)

            // 序号徽标（选中后）：标在框左上角外侧，表示第几个扫的 = 第几箱
            if (isPicked) {
                val badge = (order + 1).toString()
                val rBadge = base * 0.036f
                val bx = (fr.left + rBadge * 0.9f).coerceAtLeast(rBadge + base * 0.005f)
                val by = (fr.top - rBadge * 0.4f).coerceAtLeast(rBadge + base * 0.005f)
                fillPaint.color = Color.parseColor("#FFD54F")
                canvas.drawCircle(bx, by, rBadge, fillPaint)
                labelPaint.color = Color.BLACK
                labelPaint.textAlign = Paint.Align.CENTER
                canvas.drawText(badge, bx, by + labelPaint.textSize * 0.36f, labelPaint)
                labelPaint.textAlign = Paint.Align.LEFT
            }

            // 码值标签：贴在框内侧下方（不遮挡码主体）
            val label = it.value.take(22)
            val tw = labelPaint.measureText(label)
            val th = labelPaint.textSize
            val lpad = th * 0.22f
            val bgL = fr.left
            val bgT = (fr.bottom + th * 0.15f)
            fillPaint.color = if (isPicked) Color.parseColor("#E6FFD54F") else Color.parseColor("#B3000000")
            canvas.drawRoundRect(
                RectF(bgL, bgT, bgL + tw + lpad * 2, bgT + th + lpad * 2),
                th * 0.25f, th * 0.25f, fillPaint,
            )
            labelPaint.color = if (isPicked) Color.BLACK else Color.WHITE
            canvas.drawText(label, bgL + lpad, bgT + th + lpad * 0.5f, labelPaint)
            labelPaint.color = Color.WHITE
        }
    }

    /** 命中测试：小框优先，避免大框吃掉小框。 */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return items.isNotEmpty()
        val x = event.x
        val y = event.y

        // 先判"点到了箭头/标签附近"（因为箭头不一定压在码上）
        val hit = screenRects.entries
            .filter { (_, r) ->
                // 画框在码上，直接点框内即选中；略微外扩以容忍手指误差
                val pad = maxOf(r.width(), r.height()) * 0.10f
                x >= r.left - pad && x <= r.right + pad && y >= r.top - pad && y <= r.bottom + pad
            }
            .minByOrNull { (_, r) -> r.width().toLong() * r.height().toLong() }

        if (hit != null) onPick?.invoke(hit.key)
        return true
    }
}
