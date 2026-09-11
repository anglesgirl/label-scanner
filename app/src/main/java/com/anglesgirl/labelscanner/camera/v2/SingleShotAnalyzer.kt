package com.anglesgirl.labelscanner.camera.v2

import android.graphics.Rect
import androidx.camera.core.CameraControl
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.ZoomState
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlin.math.max

/**
 * 单张拍摄模式的取景分析器。
 *
 * 职责**只有两件**：
 *  1. 判断"标签是否已进入取景框且稳定" → 通知页面可以拍一张；
 *  2. 温和地把变焦调整到合适范围（死区 + 限速，避免画面被来回拉扯）。
 *
 * 与上一版的区别（用户反馈"费电"、"设计原理不是这样"）：
 *  - **不做连续识别、不做多帧累加**：识别只发生在拍下的那一张静图上；
 *  - 因此没有"码越扫越多、何时算完"的困惑，也没有跨帧混入的错值；
 *  - 取景阶段每 SKIP 帧才分析一次，核对阶段完全暂停（[pause]），显著省电。
 */
class SingleShotAnalyzer(
    private val getZoomState: () -> ZoomState?,
    private val getCameraControl: () -> CameraControl?,
    /** 判定"对准且稳定"时回调（页面据此自动拍一张）。 */
    private val onAligned: () -> Unit,
    /** 每帧的状态回调，供界面提示（是否对准、标签占比）。 */
    private val onProgress: (aligned: Boolean, areaRatio: Float) -> Unit,
) : ImageAnalysis.Analyzer {

    companion object {
        /** 每多少帧分析一次（取景阶段不需要很频繁，省电）。 */
        private const val SKIP_FRAMES = 3

        /** 标签框占比的目标死区：在此区间内完全不改变焦。 */
        private const val DEAD_LOW = 0.10f
        private const val DEAD_HIGH = 0.40f
        private const val ZOOM_STEP_MAX = 0.12f
        private const val ZOOM_ALPHA = 0.3f

        /** 稳定判定：中心位移小于此值且持续若干次即认为对准。 */
        private const val STABLE_MOVE_TH = 0.03f
        private const val STABLE_NEEDED = 3
    }

    private val scanner: BarcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
            .build()
    )

    @Volatile private var paused = false
    @Volatile private var processing = false

    private var frameCount = 0
    private var smoothZoom = 1f
    private var lastCx = -1f
    private var lastCy = -1f
    private var stableCount = 0
    private var firedForThisShot = false

    fun pause() { paused = true }

    fun resume() {
        paused = false
        restart()
    }

    /** 拍完一张后调用：重新开始稳定计数，准备下一张。 */
    fun restart() {
        stableCount = 0
        firedForThisShot = false
        lastCx = -1f
        lastCy = -1f
    }

    override fun analyze(imageProxy: ImageProxy) {
        if (paused || processing) {
            imageProxy.close()
            return
        }
        frameCount++
        if (frameCount % SKIP_FRAMES != 0) {
            imageProxy.close()
            return
        }
        processing = true

        val media = imageProxy.image
        if (media == null) {
            imageProxy.close()
            processing = false
            return
        }
        val w = imageProxy.width
        val h = imageProxy.height
        val input = InputImage.fromMediaImage(media, imageProxy.imageInfo.rotationDegrees)

        scanner.process(input)
            .addOnSuccessListener { barcodes ->
                val box = biggestBox(barcodes)
                evaluate(box, w, h)
            }
            .addOnFailureListener {
                onProgress(false, 0f)
            }
            .addOnCompleteListener {
                imageProxy.close()
                processing = false
            }
    }

    /** 取面积最大的单码框（比并集稳定：并集面积随识别到的码数跳变）。 */
    private fun biggestBox(barcodes: List<Barcode>): Rect? =
        barcodes.mapNotNull { it.boundingBox }
            .maxByOrNull { it.width().toLong() * it.height().toLong() }

    private fun evaluate(box: Rect?, w: Int, h: Int) {
        if (box == null || w <= 0 || h <= 0) {
            stableCount = 0
            onProgress(false, 0f)
            return
        }
        val areaRatio = (box.width().toFloat() * box.height()) / (w.toFloat() * h)
        adjustZoom(areaRatio)

        val cx = box.centerX().toFloat() / w
        val cy = box.centerY().toFloat() / h
        val moved = if (lastCx < 0) Float.MAX_VALUE
        else max(kotlin.math.abs(cx - lastCx), kotlin.math.abs(cy - lastCy))
        lastCx = cx
        lastCy = cy

        // 已在合适大小 + 中心基本不动 → 认为对准
        val wellSized = areaRatio in DEAD_LOW..DEAD_HIGH
        if (moved < STABLE_MOVE_TH && wellSized) stableCount++ else stableCount = 0

        val aligned = stableCount >= STABLE_NEEDED
        onProgress(aligned, areaRatio)

        if (aligned && !firedForThisShot) {
            firedForThisShot = true
            onAligned()
        }
    }

    /** 低于死区下限才放大、高于上限才缩小；单帧幅度受限；区间内完全不动。 */
    private fun adjustZoom(areaRatio: Float) {
        val zs = getZoomState() ?: return
        if (zs.maxZoomRatio <= zs.minZoomRatio) return
        val current = zs.zoomRatio
        var target = current
        if (areaRatio < DEAD_LOW || areaRatio > DEAD_HIGH) {
            val desired = if (areaRatio < DEAD_LOW) DEAD_LOW else DEAD_HIGH
            val ideal = current * kotlin.math.sqrt(desired / max(areaRatio, 0.0001f))
            val maxStep = current * ZOOM_STEP_MAX
            target = current + (ideal - current).coerceIn(-maxStep, maxStep)
        }
        target = target.coerceIn(zs.minZoomRatio, zs.maxZoomRatio)
        smoothZoom += (target - smoothZoom) * ZOOM_ALPHA
        smoothZoom = smoothZoom.coerceIn(zs.minZoomRatio, zs.maxZoomRatio)
        try {
            getCameraControl()?.setZoomRatio(smoothZoom)
        } catch (_: Throwable) {
        }
    }

    fun close() {
        scanner.close()
    }
}
