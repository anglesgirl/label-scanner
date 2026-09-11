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
 * private val imageIn by lazy { ImageIn(this) { uri -> recognize(uri) } }
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
 * 所以本类要在 `onCreate` 之前或之中构造（用 `by lazy` 或直接赋值均可）。
 */
class ImageIn(
    private val activity: ComponentActivity,
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

    /** 自研文档模式拍照。 */
    fun takePhoto() {
        takePhoto.launch(Intent(activity, CaptureActivity::class.java))
    }

    /**
     * ML Kit 文档扫描。参数只在这里定义，所有页面共用，避免各处走偏。
     * - **FULL 模式**：自动找边 + 裁切 + 透视矫正 + 图像增强。
     *   对 PDF417 这类堆叠式二维码至关重要 —— 手机拍照必然有透视变形，
     *   而堆叠码对透视极其敏感，不矫正很难稳定解出。
     * - 页数限 1（一张标签）、允许从相册导入、JPEG 结果。
     */
    fun scanDocument() {
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
