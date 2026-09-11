package com.anglesgirl.labelscanner.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 标签自动矫正：**在静图上**检测标签四角并做透视变换，输出"正图"。
 *
 * 为什么这样做：
 *  - 旧版把"自动对准 + 自动拍照 + 矫正"交给 Google 的 Document Scanner
 *    （`play-services-mlkit-document-scanner`），但它依赖 GMS，而使用者的
 *    手机型号杂乱，必须不依赖 GMS 才能推广。
 *  - 实时检测四边形会因每帧结果不同而"边框乱跳"；**在拍好的静图上检测只做一次**，
 *    不存在抖动问题，也省电。
 *
 * 算法（针对"白色标签贴在牛皮纸箱上"这一实际场景，颜色对比强，故用轻量法）：
 *  1. 缩到工作尺寸（加速），灰度化
 *  2. Otsu 自适应阈值 → 取亮区为前景（标签比纸箱亮得多）
 *  3. 逐行扫前景的左右边界，估计四条边 → 用最小二乘拟出四条直线
 *  4. 两两求交 → 四个角点
 *  5. 合理性校验（凸性、面积、宽高比）→ 通过才做透视变换
 *
 * 全程无第三方依赖（不用 OpenCV / 不用 GMS）。
 */
object LabelRectifier {

    private const val WORK_MAX_EDGE = 480

    /**
     * 透视变换前先把源图限制到该长边。
     * 实测在 3072x4096 全尺寸上做逐像素映射要 3.5 秒；降到 2000 后约 0.6 秒，
     * 而识别所需细节（条码模块 + 3x 放大后的宽度）依然充足。
     */
    private const val MAX_SRC_EDGE = 2000
    /** 前景占比低于此值 → 认为没找到标签（避免把整幅图当标签）。 */
    private const val MIN_FG_RATIO = 0.06f
    private const val MAX_FG_RATIO = 0.95f

    data class Result(
        val bitmap: Bitmap,
        val corners: List<PointF>?,
        val ok: Boolean,
        val note: String,
    )

    /**
     * 尝试矫正。失败时返回原图并说明原因（调用方直接继续识别原图，不阻断流程）。
     */
    fun rectify(src: Bitmap): Result {
        return try {
            val corners = detectCorners(src)
            if (corners == null) {
                return Result(src, null, false, "未检测到标签边界")
            }

            // 源图过大则先等比降采样（透视变换是逐像素操作，成本随像素数线性增长）
            val longest = max(src.width, src.height)
            val downScale = if (longest > MAX_SRC_EDGE) MAX_SRC_EDGE.toFloat() / longest else 1f
            val work = if (downScale < 1f) {
                Bitmap.createScaledBitmap(
                    src,
                    max(1, (src.width * downScale).toInt()),
                    max(1, (src.height * downScale).toInt()),
                    true,
                )
            } else src
            val scaledCorners = if (downScale < 1f) {
                corners.map { PointF(it.x * downScale, it.y * downScale) }
            } else corners

            val (w, h) = ImageWarp.outputSize(scaledCorners)
            if (w < 40 || h < 40) {
                return Result(src, null, false, "输出尺寸过小(${w}x$h)")
            }
            val warped = ImageWarp.perspectiveTransform(work, scaledCorners, w, h)
            val dsText = String.format(java.util.Locale.US, "%.2f", downScale)
            Result(warped, corners, true, "已矫正 ${w}x$h (源降采样 $dsText)")
        } catch (t: Throwable) {
            Result(src, null, false, "矫正异常: ${t.message}")
        }
    }

    /**
     * 检测标签四角（原图坐标系）。检测不到返回 null。
     */
    fun detectCorners(src: Bitmap): List<PointF>? {
        val scale = WORK_MAX_EDGE.toFloat() / max(src.width, src.height)
        val sw = max(1, (src.width * scale).toInt())
        val sh = max(1, (src.height * scale).toInt())
        val small = Bitmap.createScaledBitmap(src, sw, sh, true)

        val w = small.width
        val h = small.height
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)

        // 灰度 + Otsu 阈值
        val gray = IntArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            val g = ((Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000)
            gray[i] = g
        }
        val th = otsu(gray)
        // 亮于阈值视为标签候选
        val fg = BooleanArray(w * h)
        var fgCount = 0
        for (i in gray.indices) {
            if (gray[i] > th) { fg[i] = true; fgCount++ }
        }
        val ratio = fgCount.toFloat() / (w * h).toFloat()
        if (ratio < MIN_FG_RATIO || ratio > MAX_FG_RATIO) return null

        // 逐行取前景左右边界 → 四条边的采样点
        val leftPts = ArrayList<PointF>()
        val rightPts = ArrayList<PointF>()
        for (y in 0 until h) {
            var l = -1
            var r = -1
            for (x in 0 until w) {
                if (fg[y * w + x]) { l = x; break }
            }
            for (x in w - 1 downTo 0) {
                if (fg[y * w + x]) { r = x; break }
            }
            // 要求该行前景足够宽，避免噪点/文字行干扰
            if (l >= 0 && r >= 0 && (r - l) > w * 0.25f) {
                leftPts.add(PointF(l.toFloat(), y.toFloat()))
                rightPts.add(PointF(r.toFloat(), y.toFloat()))
            }
        }
        if (leftPts.size < h / 3) return null

        // 左右两条边各拟合一条直线 x = a*y + b（用首尾 30% 的点分上下两端，再分别拟合）
        val topLeft = fitEnd(leftPts, top = true, w, h)
        val bottomLeft = fitEnd(leftPts, top = false, w, h)
        val topRight = fitEnd(rightPts, top = true, w, h)
        val bottomRight = fitEnd(rightPts, top = false, w, h)

        // 也拟合上下边（用左右边界点集的首行/末行不容易，这里直接用四个端点做四边形）
        val quad = listOf(topLeft, topRight, bottomRight, bottomLeft)

        // 合理性校验
        if (!isReasonableQuad(quad, w.toFloat(), h.toFloat())) return null
        val ordered = ImageWarp.sortCorners(quad)

        // 换算回原图坐标
        val inv = 1f / scale
        return ordered.map { PointF(it.x * inv, it.y * inv) }
    }

    /** 用一列边界点里"靠上"或"靠下"的那部分拟合直线，返回它与该侧边界的交点估计。 */
    private fun fitEnd(pts: List<PointF>, top: Boolean, w: Int, h: Int): PointF {
        val sorted = pts.sortedBy { it.y }
        val take = max(2, sorted.size / 4)
        val seg = if (top) sorted.take(take) else sorted.takeLast(take)
        // 最小二乘拟合 x = a*y + b
        var sy = 0f; var sx = 0f; var syy = 0f; var syx = 0f
        val n = seg.size
        for (p in seg) {
            sy += p.y; sx += p.x; syy += p.y * p.y; syx += p.y * p.x
        }
        val denom = n * syy - sy * sy
        val a = if (abs(denom) < 1e-3f) 0f else (n * syx - sy * sx) / denom
        val b = (sx - a * sy) / n
        // 取该段的中位 y 作为代表位置
        val yRep = if (top) sorted.take(take).last().y else sorted.takeLast(take).first().y
        val x = a * yRep + b
        return PointF(x.coerceIn(0f, w.toFloat()), yRep.coerceIn(0f, h.toFloat()))
    }

    /** 凸性 + 面积 + 宽的合理性检查，防止把噪点当成标签。 */
    private fun isReasonableQuad(q: List<PointF>, w: Float, h: Float): Boolean {
        if (q.size != 4) return false
        // 面积（鞋带公式）
        var area = 0f
        for (i in 0 until 4) {
            val p = q[i]
            val n = q[(i + 1) % 4]
            area += p.x * n.y - n.x * p.y
        }
        area = abs(area) / 2f
        val imgArea = w * h
        if (area < imgArea * 0.08f) return false          // 太小 → 不像标签
        if (area > imgArea * 0.98f) return false          // 几乎满屏 → 说明没分割出边界
        // 四条边长度不能太悬殊（标签是矩形，透视后边比有界）
        val sides = (0 until 4).map { i ->
            val a = q[i]; val b = q[(i + 1) % 4]
            kotlin.math.hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat()
        }
        val minS = sides.min(); val maxS = sides.max()
        if (minS < 8f) return false
        if (maxS / minS > 4.0f) return false
        return true
    }

    /** Otsu 阈值。 */
    private fun otsu(gray: IntArray): Int {
        val hist = IntArray(256)
        for (g in gray) hist[g.coerceIn(0, 255)]++
        val total = gray.size
        var sum = 0.0
        for (i in 0..255) sum += i.toDouble() * hist[i]
        var sumB = 0.0
        var wB = 0
        var maxVar = -1.0
        var best = 128
        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += t.toDouble() * hist[t]
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > maxVar) {
                maxVar = between
                best = t
            }
        }
        return best
    }

    /** 给调试用：把检测到的四角画在图上（确认检测准不准）。 */
    fun drawCorners(src: Bitmap, corners: List<PointF>): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        val paint = Paint().apply {
            color = Color.RED
            strokeWidth = max(2f, src.width / 300f)
            style = Paint.Style.STROKE
        }
        for (i in corners.indices) {
            val a = corners[i]
            val b = corners[(i + 1) % corners.size]
            c.drawLine(a.x, a.y, b.x, b.y, paint)
        }
        return out
    }
}
