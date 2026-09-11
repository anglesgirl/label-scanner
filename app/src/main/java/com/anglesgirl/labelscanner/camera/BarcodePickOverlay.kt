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
        labelPaint.textSize = base * 0.040f
        strokePaint.strokeWidth = base * 0.006f

        for (it in items) {
            val r = screenRects[it.value] ?: continue
            val order = picked.indexOf(it.value)
            val isPicked = order >= 0

            val color = if (isPicked) Color.parseColor("#FFD54F") else Color.parseColor("#33E1FF")
            fillPaint.color = color
            strokePaint.color = color

            // 箭头指向码：从左上方斜指到码框中心偏上（不覆盖码本身）
            val cx = r.centerX().toFloat()
            val cy = r.centerY().toFloat()
            val len = base * 0.10f
            val startX = cx - len
            val startY = cy - len
            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(cx - base * 0.012f, cy - base * 0.012f)
            }
            canvas.drawPath(path, strokePaint)

            // 箭头尖
            val tip = Path().apply {
                val ax = cx - base * 0.012f
                val ay = cy - base * 0.012f
                val w = base * 0.026f
                moveTo(ax, ay)
                lineTo(ax - w, ay - w * 0.35f)
                lineTo(ax - w * 0.35f, ay - w)
                close()
            }
            canvas.drawPath(tip, fillPaint)

            // 序号徽标：选中后标 ①②③…（即第几个扫的 = 第几箱）
            if (isPicked) {
                val badge = (order + 1).toString()
                val rBadge = base * 0.034f
                val bx = startX - rBadge * 0.6f
                val by = startY - rBadge * 0.6f
                canvas.drawCircle(bx, by, rBadge, fillPaint)
                val bak = labelPaint.color
                labelPaint.color = Color.BLACK
                labelPaint.textAlign = Paint.Align.CENTER
                canvas.drawText(badge, bx, by + labelPaint.textSize * 0.35f, labelPaint)
                labelPaint.color = bak
                labelPaint.textAlign = Paint.Align.LEFT
            }

            // 码值标签（贴在箭头起点左侧，便于确认指的是哪个码）
            val label = it.value.take(20)
            val tw = labelPaint.measureText(label)
            val th = labelPaint.textSize
            val pad = th * 0.22f
            val boxL = (startX - tw - pad * 2).coerceAtLeast(0f)
            val boxT = (startY - th - pad).coerceAtLeast(0f)
            val labelBg = RectF(boxL, boxT, boxL + tw + pad * 2, boxT + th + pad * 2)
            fillPaint.color = if (isPicked) Color.parseColor("#D9FFD54F") else Color.parseColor("#B3000000")
            canvas.drawRoundRect(labelBg, th * 0.25f, th * 0.25f, fillPaint)
            labelPaint.color = if (isPicked) Color.BLACK else Color.WHITE
            canvas.drawText(label, boxL + pad, boxT + th + pad * 0.5f, labelPaint)
            labelPaint.color = Color.WHITE
            fillPaint.color = color
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
                r.contains(x.toInt(), y.toInt()) ||
                    // 箭头起点在码左上，命中范围略微放宽
                    (x >= r.left - r.width() * 0.35f && x <= r.right && y >= r.top - r.height() * 0.35f && y <= r.bottom)
            }
            .minByOrNull { (_, r) -> r.width().toLong() * r.height().toLong() }

        if (hit != null) onPick?.invoke(hit.key)
        return true
    }
}
