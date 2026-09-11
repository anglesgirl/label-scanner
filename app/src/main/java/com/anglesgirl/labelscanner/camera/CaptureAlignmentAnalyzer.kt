package com.anglesgirl.labelscanner.camera

import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 取景稳定性检测：**判准依据改为"码认出来了没有"，不再看文档框在哪**。
 *
 * 为什么换掉旧实现（用户反馈"对不准、边框乱跳、出图慢半拍"）：
 *  - 旧版用 ML Kit 条码通道，没有结果时**退回每帧跑 OCR**（中文 OCR 很慢），
 *    整条链路被拖住，观感就是"反应慢、对不上"。
 *  - 旧版用"文档四边形的位置/面积"判稳定，而位置检测本身逐帧抖动，
 *    稍微一晃计数就衰减，**很难攒满** → 常常永远不触发拍照。
 *
 * 新思路（也与"条码全交 zxing、ML Kit 只做 OCR"的分工一致）：
 *  - 只用 zxing-cpp 解码，**解出码本身就是"标签在画面里"的硬证据**；
 *  - **连续若干帧解出同一组码**即认为稳定 → 触发拍照；
 *  - 不依赖任何位置判断，因此不会因框抖动而反复归零。
 *
 * 与实时扫码页的区别：这里**不关心码的具体内容**，只要能稳定解出即可
 * （真正取数据是拍照后在原图上跑的，那张图分辨率更高、更准）。
 */
class CaptureAlignmentAnalyzer(
    private val onState: (AlignmentState) -> Unit,
    private val onStable: () -> Unit,
) : ImageAnalysis.Analyzer {

    enum class AlignmentState { SEARCHING, MOVE_CLOSER, CENTERED, STABLE }

    private companion object {
        const val TAG = "CaptureAlign"
        /** 每 N 帧抽一次（zxing 比 ML Kit 慢，不能每帧）。 */
        const val SAMPLE_EVERY = 2
        /** 连续多少帧解出同一组码就算稳定。 */
        const val STABLE_NEEDED = 2
        /** 状态上报的最小间隔（迟滞，防闪烁）。 */
        const val EMIT_GAP_SAME = 250L
        const val EMIT_GAP_CHANGE = 150L
    }

    private val pool = Executors.newSingleThreadExecutor { r ->
        Thread(r, "capture-zxing").apply { isDaemon = true }
    }
    private val busy = AtomicBoolean(false)
    private var frameCounter = 0

    /** 上一次解出的码签名与连续命中次数。 */
    private var lastSignature = ""
    private var stableHits = 0

    override fun analyze(imageProxy: ImageProxy) {
        if (!busy.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }
        if (frameCounter++ % SAMPLE_EVERY != 0) {
            imageProxy.close()
            busy.set(false)
            return
        }
        // 必须在 close 之前取像素
        val bmp = runCatching { imageProxy.toBitmap() }.getOrNull()
        imageProxy.close()
        if (bmp == null) {
            busy.set(false)
            return
        }
        val w = bmp.width
        val h = bmp.height
        pool.execute {
            try {
                val codes = ZxingDecoder.decode(bmp)
                evaluate(codes)
            } catch (t: Throwable) {
                Log.w(TAG, "解码失败", t)
                evaluate(emptyList())
            } finally {
                busy.set(false)
            }
        }
    }

    private fun evaluate(codes: List<String>) {
        if (codes.isEmpty()) {
            stableHits = 0
            lastSignature = ""
            lastStateIsStable = false
            emitState(AlignmentState.SEARCHING)
            return
        }

        // 码签名：同一组码（顺序无关）连续出现即视为画面稳定。
        // 只比较"能不能稳定解出同一批码"，不比较它们的位置 —— 位置逐帧抖，
        // 用它判稳定正是旧版对不准的原因。
        val sig = codes.sorted().joinToString("|")
        if (sig == lastSignature) {
            stableHits++
        } else {
            lastSignature = sig
            stableHits = 1
        }

        if (stableHits >= STABLE_NEEDED) {
            stableHits = 0
            lastStateIsStable = true
            emitState(AlignmentState.STABLE)
            onStable()
        } else {
            lastStateIsStable = false
            emitState(AlignmentState.CENTERED)
        }
    }

    /** 上一次真正上报的状态与时间（迟滞用）。 */
    private var lastEmitted: AlignmentState? = null
    private var lastEmittedAt = 0L

    private fun emitState(st: AlignmentState) {
        val now = System.currentTimeMillis()
        val changed = st != lastEmitted
        val gap = now - lastEmittedAt
        if (changed && gap < EMIT_GAP_CHANGE) return
        if (!changed && gap < EMIT_GAP_SAME) return
        lastEmitted = st
        lastEmittedAt = now
        onState(st)
    }

    /** 最近一次状态是否 STABLE（供预览决定取景框颜色）。 */
    @Volatile
    var lastStateIsStable: Boolean = false
        private set

    fun close() {
        try { pool.shutdownNow() } catch (_: Throwable) {}
    }
}
