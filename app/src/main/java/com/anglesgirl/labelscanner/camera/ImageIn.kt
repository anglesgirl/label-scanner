package com.anglesgirl.labelscanner.camera

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult

/** Camera presentation/analysis varies by workflow, while capture remains shared. */
enum class CameraCaptureMode(
    val instruction: String,
    val usesLiveSampling: Boolean,
    val showLastSavedSerial: Boolean,
    val captureButtonText: String,
) {
    PHOTO_ONLY(
        "将目标标签或条码页放入框内，尽量拍全并保持清晰；拍照后识别页面内容。",
        usesLiveSampling = false,
        showLastSavedSerial = false,
        captureButtonText = "拍照识别",
    ),
    INBOUND_LABEL(
        "将入库标签放入框内，重点对准69码下方的序列号、日期和物料编码；读到条码即开始采样。",
        usesLiveSampling = true,
        showLastSavedSerial = true,
        captureButtonText = "手动拍照",
    ),
    MULTI_CODE(
        "对准多码标签或序列号清单，尽量拍全页面所有条码；拍照后一次识别全部条码和文字。",
        usesLiveSampling = false,
        showLastSavedSerial = false,
        captureButtonText = "拍照识别",
    ),
    LIST_TRAY(
        "对准托盘信息页，让托盘/SSCC 条码完整清晰入框；同一候选连续识别 3 帧后自动拍照，也可手动拍照兜底。",
        usesLiveSampling = true,
        showLastSavedSerial = false,
        captureButtonText = "手动拍照",
    ),
    LIST_HEADER(
        "让终端物料编号和生产日期同时清晰入镜；直接识别中英文文字，不要求先读到条码。",
        usesLiveSampling = true,
        showLastSavedSerial = false,
        captureButtonText = "手动拍照",
    ),
    LIST_CODES(
        "从本页最上方开始缓慢向下扫；每个码稳定读到 3 次后加入清单，扫完整页点完成本页，再扫下一张。",
        usesLiveSampling = true,
        showLastSavedSerial = false,
        captureButtonText = "完成本页",
    ),
}

/**
 * 统一的取图入口模块。
 *
 * 为什么要有它（用户明确要求）：
 * 之前「单条采集」「整箱采集」「集成码拆分」「设置里的测试识别」各自注册了一套
 * takePhoto / scanDoc / pickGallery —— 同一件事写了三到四份，参数容易走偏，
 * 改动要改多处。用户原话：
 *   "不管是在采集、拆分还是测试里，都统一调用一个拍照模块。这个模块里边安排
 *    几个方法…但是不要在单个采集、整箱采集、拆分里面用三个不同的拍照模块，
 *    互相的参数还不一致。"
 *
 * 用法（各页面只声明一次，并只负责"拿到图之后怎么处理"）：
 * ```
 * private val imageIn = ImageIn(this) { uri -> recognize(uri) }
 * // 按钮：
 * btnTakePhoto.setOnClickListener { imageIn.takePhoto() }
 * btnScanDoc.setOnClickListener  { imageIn.scanDocument() }
 * btnPickGallery.setOnClickListener { imageIn.pickGallery() }
 * ```
 *
 * 三种取图方式都保留（它们对应不同使用场景），但**实现与参数只有这一份**：
 *  - [takePhoto]    —— 自研文档模式拍照（实时对准 + 自动拍照 + 透视矫正）
 *  - [scanDocument] —— ML Kit 文档扫描（自动找边/裁切/矫正/增强，体验最好）
 *  - [pickGallery]  —— 相册导入（走系统文档模式拍完再投喂的兜底）
 *
 * 注意：[ComponentActivity] 要求在 Activity 初始化阶段就注册 launcher，
 * 所以本类要在 Activity 构造阶段或 onCreate 中构造，不能延迟到点击时初始化。
 */
class ImageIn(
    private val activity: ComponentActivity,
    private val defaultCaptureMode: CameraCaptureMode = CameraCaptureMode.PHOTO_ONLY,
    private val onImage: (Uri) -> Unit,
) {

    private val takePhoto: ActivityResultLauncher<Intent> =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            result.data?.getStringExtra(CaptureActivity.EXTRA_OUTPUT_URI)
                ?.takeIf { it.isNotBlank() }
                ?.let { onImage(Uri.parse(it)) }
        }

    private val scanDocument: ActivityResultLauncher<IntentSenderRequest> =
        activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            GmsDocumentScanningResult.fromActivityResultIntent(result.data)
                ?.pages?.firstOrNull()?.imageUri
                ?.let(onImage)
        }

    private val pickGallery: ActivityResultLauncher<String> =
        activity.registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let(onImage)
        }

    /** 唯一的自研相机入口；调用方只声明当前流程的拍摄目的。 */
    fun takePhoto(mode: CameraCaptureMode = defaultCaptureMode) {
        takePhoto.launch(
            Intent(activity, CaptureActivity::class.java)
                .putExtra(CaptureActivity.EXTRA_CAPTURE_MODE, mode.name),
        )
    }

    /**
     * Google 文档扫描的 SDK 自带取景页不开放相机变焦控制。
     * 第二个按钮只进入 Google 原版，不再重复提供第一个按钮的自研相机。
     * 两条路径最终仍由同一个 onImage 回调交给全量扫码/OCR。
     */
    fun scanDocument() {
        scanGoogleDocument()
    }

    /**
     * ML Kit 原版文档扫描。参数只在这里定义，所有页面共用，避免各处走偏。
     * - **FULL 模式**：自动找边 + 裁切 + 透视矫正 + 图像增强。
     *   对 PDF417 这类堆叠式二维码至关重要 —— 手机拍照必然有透视变形，
     *   而堆叠码对透视极其敏感，不矫正很难稳定解出。
     * - 页数限 1（一张标签）、允许从相册导入、JPEG 结果。
     */
    fun scanGoogleDocument() {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(1)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .build()
        GmsDocumentScanning.getClient(options)
            .getStartScanIntent(activity)
            .addOnSuccessListener { sender ->
                scanDocument.launch(IntentSenderRequest.Builder(sender).build())
            }
            .addOnFailureListener { e ->
                android.widget.Toast.makeText(
                    activity,
                    "文档扫描不可用（该机型可能缺少 Google 服务）: ${e.message}",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
    }

    /** 相册导入已有图片。 */
    fun pickGallery() {
        pickGallery.launch("image/*")
    }

    /** 该机型是否支持 ML Kit 文档扫描（部分国产机型无 GMS）。 */
    fun isDocumentScanAvailable(): Boolean = runCatching {
        GmsDocumentScanning.getClient(
            GmsDocumentScannerOptions.Builder()
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                .build()
        ) != null
    }.getOrDefault(false)
}
