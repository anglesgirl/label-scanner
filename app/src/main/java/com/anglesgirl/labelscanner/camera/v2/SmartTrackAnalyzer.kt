package com.anglesgirl.labelscanner.camera.v2

import android.graphics.PointF
import android.graphics.Rect
import androidx.camera.core.CameraControl
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
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
    private val cameraControl: CameraControl,
    /** 判定"已稳定"后回调（由页面触发 takePicture）。 */
    private val onStable: () -> Unit,
    /** 每帧的识别结果（条码 + OCR 文本行），由页面累加归组。 */
    private val onFrame: (barcodes: List<String>, ocrLines: List<String>, box: Rect?) -> Unit,
) : ImageAnalysis.Analyzer {

    companion object {
        /** 目标：条码框占画面面积比例（太小要放大，太大要缩小）。 */
        private const val TARGET_AREA_LOW = 0.10f
        private const val TARGET_AREA_HIGH = 0.45f

        /** zoom 平滑系数：越小越稳、越大越跟手。 */
        private const val ZOOM_ALPHA = 0.25f

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
                val box = unionBox(barcodes)
                applyTracking(box, w, h)
                runOcr(inputImage, values, box, imageProxy)
            }
            .addOnFailureListener {
                runOcr(inputImage, emptyList(), null, imageProxy)
            }
    }

    /** 合并所有条码的检测框，得到一个"标签区域"。 */
    private fun unionBox(barcodes: List<Barcode>): Rect? {
        var r: Rect? = null
        for (b in barcodes) {
            val bb = b.boundingBox ?: continue
            r = if (r == null) Rect(bb) else Rect(r).also { it.union(bb) }
        }
        return r
    }

    /**
     * 依据检测框做自动缩放 + 自动对焦 + 稳定判定。
     * 全部在分析线程执行，但 CameraControl 是线程安全的。
     */
    private fun applyTracking(box: Rect?, frameW: Int, frameH: Int) {
        if (box == null || frameW <= 0 || frameH <= 0) {
            stableFrames = 0
            stableFired = false
            return
        }

        val boxArea = box.width().toFloat() * box.height().toFloat()
        val frameArea = frameW.toFloat() * frameH.toFloat()
        val areaRatio = boxArea / frameArea

        // ── 自动缩放：让标签落在目标面积区间内 ─────────────────────────
        val zoomState = getZoomState()
        if (zoomState != null && zoomState.maxZoomRatio > zoomState.minZoomRatio) {
            // 目标变焦必须基于**当前实际变焦值**推算，不能用上一次的平滑值累乘 ——
            // 后者会随时间漂移（平滑值与实际值总有残差，越乘越偏）。
            val currentZoom = zoomState.zoomRatio
            val desiredArea = when {
                areaRatio < TARGET_AREA_LOW -> TARGET_AREA_LOW
                areaRatio > TARGET_AREA_HIGH -> TARGET_AREA_HIGH
                else -> areaRatio          // 已在目标区间内 → 保持
            }
            // 面积比与线性变焦的平方成正比，所以倍数取平方根
            val factor = kotlin.math.sqrt(desiredArea / max(areaRatio, 0.0001f))
            val target = (currentZoom * factor)
                .coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
            // EMA 平滑施加在"要下发的值"上，避免画面一顿一顿
            smoothZoom += (target - smoothZoom) * ZOOM_ALPHA
            smoothZoom = smoothZoom.coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
            try {
                cameraControl.setZoomRatio(smoothZoom)
            } catch (_: Throwable) {
                // 部分机型在特定状态下会抛，忽略即可（下次帧再试）
            }
        }

        // ── 自动对焦：对焦点放在标签中心（限频）────────────────────────
        val now = System.currentTimeMillis()
        if (now - lastFocusAt > FOCUS_INTERVAL_MS) {
            val cx = (box.centerX().toFloat() / frameW)
            val cy = (box.centerY().toFloat() / frameH)
            val point = PointF(cx.coerceIn(0.05f, 0.95f), cy.coerceIn(0.05f, 0.95f))
            try {
                cameraControl.startFocusAndMetering(
                    FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                        .setAutoCancelDuration(2, TimeUnit.SECONDS)
                        .build()
                )
                lastFocusAt = now
            } catch (_: Throwable) {
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
