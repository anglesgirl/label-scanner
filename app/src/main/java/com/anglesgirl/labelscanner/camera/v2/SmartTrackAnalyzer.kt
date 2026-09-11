package com.anglesgirl.labelscanner.camera.v2

/*
 * 【用途：模式 B —— 卡板清单 / 大批量取码，当前采集流程未使用】
 *
 * 采集入库（模式 A）走的是"一箱一张 + 静图矫正"（见 SingleShotAnalyzer / LabelRectifier），
 * 不做实时连续识别。本类保留给以后的大批量取码模式：那种场景只要条码值、不要 OCR、
 * 不逐箱核对，适合连续扫。**请勿当作死代码删除。**
 */

import android.graphics.PointF
import android.graphics.Rect
import androidx.camera.core.CameraControl
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.ImageProxy
import androidx.camera.core.ZoomState
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 智能采集分析器：**用条码 boundingBox 做追踪**，自动缩放 / 自动对焦 / 稳定后自动拍照。
 *
 * 为什么不用"检测文档四边形"（v1 的做法）：
 *   四边形检测是为平整纸张设计的；标签常反光、有褶、贴歪，检测结果帧间剧烈跳变，
 *   于是出现"框乱跳 / 总对不准 / 拍出来是上一瞬"。
 *   条码检测器天生稳定且给出精确 boundingBox，用它做追踪则缩放、对焦、拍照时机都有可靠依据。
 *
 * 依赖：仅 CameraX + bundled ML Kit（自带动量模型），**不需要 GMS**，
 *       以便在同事各种型号的手机上都能跑。
 */
class SmartTrackAnalyzer(
    private val getZoomState: () -> ZoomState?,
    /** 惰性获取：CameraControl 只有 bindToLifecycle 之后才存在，
     *  构造时求值会拿到未初始化的引用（曾因此会在启动瞬间崩溃）。 */
    private val getCameraControl: () -> CameraControl?,
    /** 判定"已稳定"后回调（由页面触发 takePicture）。 */
    private val onStable: () -> Unit,
    /** 每帧的识别结果（条码 + OCR 文本行），由页面累加归组。 */
    private val onFrame: (barcodes: List<String>, ocrLines: List<String>, box: Rect?) -> Unit,
) : ImageAnalysis.Analyzer {

    companion object {
        /**
         * 变焦死区：框占比落在 [DEAD_LOW, DEAD_HIGH] 内**完全不动**。
         * 现场实测：同一张标签在不同帧里识别到 1~4 个码，框面积会在
         * 1% ↔ 90% 之间剧烈跳变，若一超出就调焦，画面会被来回拉扯。
         */
        private const val DEAD_LOW = 0.08f
        private const val DEAD_HIGH = 0.35f

        /** 单帧变焦幅度上限：一次最多变化 15%，避免画面一跳一跳。 */
        private const val ZOOM_STEP_MAX = 0.15f

        /** zoom 平滑系数（对下发值做 EMA）。 */
        private const val ZOOM_ALPHA = 0.35f

        /** 连续多少帧没检测到框 → 变焦缓慢回落到 1.0。 */
        private const val NO_BOX_FRAMES_TO_RESET = 8

        /** 两次自动对焦之间的最小间隔（对焦本身耗时，不能每帧调）。 */
        private const val FOCUS_INTERVAL_MS = 1200L

        /** 稳定判定：框中心移动与面积变化都要小于阈值，且持续这么多帧。 */
        private const val STABLE_MOVE_TH = 0.020f
        private const val STABLE_AREA_TH = 0.05f
        private const val STABLE_FRAMES_NEEDED = 5
    }

    private val scanner: BarcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
            .build()
    )

    private val recognizer: TextRecognizer = TextRecognition.getClient(
        ChineseTextRecognizerOptions.Builder().build()
    )

    @Volatile private var processing = false

    /** 平滑后的 zoom（EMA），避免每帧突变导致画面一顿一顿。 */
    private var smoothZoom = 1f
    private var lastFocusAt = 0L
    private var lastCenter: PointF? = null
    private var lastArea = 0f
    private var stableFrames = 0
    private var stableFired = false
    private var noBoxFrames = 0

    override fun analyze(imageProxy: ImageProxy) {
        if (processing) {
            imageProxy.close()
            return
        }
        processing = true

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            processing = false
            return
        }
        val rotation = imageProxy.imageInfo.rotationDegrees
        val w = imageProxy.width
        val h = imageProxy.height
        val inputImage = InputImage.fromMediaImage(mediaImage, rotation)

        scanner.process(inputImage)
            .addOnSuccessListener { barcodes ->
                val values = barcodes.mapNotNull { it.rawValue?.trim() }.filter { it.isNotEmpty() }
                val box = trackBox(barcodes)
                applyTracking(box, w, h)
                runOcr(inputImage, values, box, imageProxy)
            }
            .addOnFailureListener {
                runOcr(inputImage, emptyList(), null, imageProxy)
            }
    }

    /**
     * 取"面积最大的那个码框"作为追踪目标。
     *
     * 不用并集的原因：一帧识别到几个码是不稳定的（实测 1~4 个），并集会随之
     * 在 1% ↔ 90% 之间跳，变焦就被拉扯成一团。单个最大框随码数变化小得多。
     */
    private fun trackBox(barcodes: List<Barcode>): Rect? =
        barcodes.mapNotNull { it.boundingBox }
            .maxByOrNull { it.width().toLong() * it.height().toLong() }

    /**
     * 依据检测框做自动缩放 + 自动对焦 + 稳定判定。
     * 全部在分析线程执行，但 CameraControl 是线程安全的。
     */
    private fun applyTracking(box: Rect?, frameW: Int, frameH: Int) {
        if (box == null || frameW <= 0 || frameH <= 0) {
            stableFrames = 0
            stableFired = false
            noBoxFrames++
            // 丢失目标时不要在放大状态下干等：缓慢回到 1.0，否则会一路顶到最大倍率
            if (noBoxFrames >= NO_BOX_FRAMES_TO_RESET && smoothZoom > 1.02f) {
                val z = getZoomState()
                val floor = z?.minZoomRatio ?: 1f
                smoothZoom += (floor - smoothZoom) * ZOOM_ALPHA
                smoothZoom = smoothZoom.coerceAtLeast(floor)
                try { getCameraControl()?.setZoomRatio(smoothZoom) } catch (_: Throwable) {}
                noBoxFrames = 0
            }
            return
        }
        noBoxFrames = 0

        val boxArea = box.width().toFloat() * box.height().toFloat()
        val frameArea = frameW.toFloat() * frameH.toFloat()
        val areaRatio = boxArea / frameArea

        // ── 自动缩放：让标签落在目标面积区间内 ─────────────────────────
        val zoomState = getZoomState()
        if (zoomState != null && zoomState.maxZoomRatio > zoomState.minZoomRatio) {
            // 目标变焦必须基于**当前实际变焦值**推算（不能用平滑值累乘，会漂移）。
            val currentZoom = zoomState.zoomRatio
            var target = currentZoom
            if (areaRatio < DEAD_LOW || areaRatio > DEAD_HIGH) {
                val desiredArea = if (areaRatio < DEAD_LOW) DEAD_LOW else DEAD_HIGH
                // 面积比与线性变焦的平方成正比 → 倍数取平方根
                val ideal = currentZoom * kotlin.math.sqrt(desiredArea / max(areaRatio, 0.0001f))
                // 限速：单帧幅度不超过 ZOOM_STEP_MAX，画面才不会一跳一跳
                val maxStep = currentZoom * ZOOM_STEP_MAX
                val delta = (ideal - currentZoom).coerceIn(-maxStep, maxStep)
                target = currentZoom + delta
            }
            // 死区内 target == currentZoom（保持不动）
            target = target.coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
            smoothZoom += (target - smoothZoom) * ZOOM_ALPHA
            smoothZoom = smoothZoom.coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
            try {
                getCameraControl()?.setZoomRatio(smoothZoom)
            } catch (_: Throwable) {
                // 部分机型在特定状态下会抛，忽略即可（下次帧再试）
            }
        }

        // ── 自动对焦：对焦点放在标签中心（限频）────────────────────────
        // 注意：startFocusAndMetering 需要的是 MeteringPoint（由工厂按
        // 分析帧的像素坐标系构造），不是归一化的 PointF。
        val now = System.currentTimeMillis()
        if (now - lastFocusAt > FOCUS_INTERVAL_MS) {
            try {
                val factory = SurfaceOrientedMeteringPointFactory(
                    frameW.toFloat(), frameH.toFloat()
                )
                val point = factory.createPoint(box.centerX().toFloat(), box.centerY().toFloat())
                getCameraControl()?.startFocusAndMetering(
                    FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                        .setAutoCancelDuration(2, TimeUnit.SECONDS)
                        .build()
                )
                lastFocusAt = now
            } catch (_: Throwable) {
                // 个别机型/状态下不支持，忽略
            }
        }

        // ── 稳定判定：中心位移 + 面积变化都小，且持续 N 帧 ──────────────
        val cxNorm = box.centerX().toFloat() / frameW
        val cyNorm = box.centerY().toFloat() / frameH
        val prev = lastCenter
        if (prev == null) {
            stableFrames = 1
        } else {
            val move = max(abs(cxNorm - prev.x), abs(cyNorm - prev.y))
            val areaChange = abs(areaRatio - lastArea)
            stableFrames = if (move < STABLE_MOVE_TH && areaChange < STABLE_AREA_TH) stableFrames + 1 else 1
        }
        lastCenter = PointF(cxNorm, cyNorm)
        lastArea = areaRatio

        if (stableFrames >= STABLE_FRAMES_NEEDED && !stableFired) {
            stableFired = true
            onStable()
        }
    }

    private fun runOcr(
        inputImage: InputImage,
        barcodes: List<String>,
        box: Rect?,
        imageProxy: ImageProxy,
    ) {
        recognizer.process(inputImage)
            .addOnSuccessListener { text ->
                val lines = text.textBlocks.flatMap { it.lines }.map { it.text.trim() }.filter { it.isNotEmpty() }
                onFrame(barcodes, lines, box)
            }
            .addOnFailureListener {
                onFrame(barcodes, emptyList(), box)
            }
            .addOnCompleteListener {
                imageProxy.close()
                processing = false
            }
    }

    /** 拍完一张后调用：重新开始稳定计数，准备下一箱。 */
    fun restartStability() {
        stableFrames = 0
        stableFired = false
        lastCenter = null
    }

    fun close() {
        scanner.close()
        recognizer.close()
    }
}
