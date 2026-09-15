package com.anglesgirl.labelscanner

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.hardware.usb.UsbDevice
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.anglesgirl.labelscanner.camera.StaticRecognizer
import com.anglesgirl.labelscanner.data.Barcode69Lookup
import com.anglesgirl.labelscanner.data.RecordStore
import com.anglesgirl.labelscanner.model.LabelResult
import com.anglesgirl.labelscanner.util.TrayPrefs
import com.jiangdg.usbcamera.UVCCameraHelper
import com.serenegiant.usb.widget.UVCCameraTextureView
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 内窥镜扫码（USB UVC 摄像头）。
 *
 * 背景：用户买了 inskamOTG USB 内窥镜。反编译其官方 APK 发现「SCTP 超级兼容
 * 传输协议」实为营销词 —— 唯一 so 里全是标准 libuvc 符号、设备过滤器是 UVC
 * 视频类（class=0x0e），本质就是标准 UVC 摄像头。因此不打包厂商闭源库，直接
 * 用开源 AndroidUSBCamera（serenegiant UVC 内核）取帧。
 *
 * 用法：插上内窥镜 → 权限弹窗 → 实时预览 → 点「识别画面」抓当前帧 →
 * 复用现有 StaticRecognizer（zxing-cpp 条码 + ML Kit 双语 OCR）→
 * 结果弹窗：保存到记录（按 SN 展开，同 saveBox）/ 复制 SN / 重拍。
 *
 * 好处：怼着标签不手抖、光线可控，适合纸箱内壁等手机相机难对准的场景。
 */
class UsbCameraScanActivity : AppCompatActivity() {

    private lateinit var cameraView: UVCCameraTextureView
    private lateinit var tvStatus: TextView

    private val helper = UVCCameraHelper.getInstance()
    private var recognizing = false

    private val lookup69Lazy by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { Barcode69Lookup(this) }
    private fun lookup69(): Barcode69Lookup = lookup69Lazy

    private val connListener = object : UVCCameraHelper.OnMyDevConnectListener {
        override fun onAttachDev(device: UsbDevice?) {
            runOnUiThread { tvStatus.text = "发现摄像头，请求 USB 权限…" }
            // 库的 requestPermission(index) 按设备序号取列表；只有一个 UVC 设备时
            // index=1 会越界（IndexOutOfBoundsException），按实际数量选择
            val deviceCount = runCatching { helper.getUSBMonitor()?.deviceCount ?: 0 }.getOrDefault(0)
            helper.requestPermission(if (deviceCount > 1) 1 else 0)
        }

        override fun onDettachDev(device: UsbDevice?) {
            helper.closeCamera()
            runOnUiThread { tvStatus.text = "摄像头已拔出" }
        }

        override fun onConnectDev(device: UsbDevice?, isCameraOpened: Boolean) {
            if (!isCameraOpened) {
                helper.createUVCCamera()
                // TextureView 的 SurfaceTexture 是异步创建（onSurfaceTextureAvailable
                // 回调），此时可能还没就绪，直接 startPreview 会拿到 null 崩溃。
                // 轮询等待就绪后再开预览。
                startPreviewWhenReady(0)
            }
            runOnUiThread {
                tvStatus.text = "已连接：${device?.deviceName ?: "USB"}"
            }
        }

        override fun onDisConnectDev(device: UsbDevice?) {
            helper.closeCamera()
            runOnUiThread { tvStatus.text = "连接已断开" }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge：与其他页面保持一致，否则标题被状态栏盖住
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_usb_camera)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        cameraView = findViewById(R.id.usbCameraView)
        tvStatus = findViewById(R.id.tvUsbStatus)

        findViewById<Button>(R.id.btnUsbRecognize).setOnClickListener { captureAndRecognize() }
        findViewById<Button>(R.id.btnUsbBack).setOnClickListener { finish() }

        // 内窥镜分辨率不高，640x480 足够识别且稳定（MJPEG 兼容性最好）
        helper.setDefaultPreviewSize(640, 480)
        helper.setDefaultFrameFormat(UVCCameraHelper.FRAME_FORMAT_MJPEG)
        helper.initUSBMonitor(this, cameraView, connListener)
        helper.registerUSB()
    }

    override fun onResume() {
        super.onResume()
        runCatching { cameraView.onResume() }
    }

    override fun onPause() {
        runCatching { cameraView.onPause() }
        super.onPause()
    }

    override fun onDestroy() {
        runCatching { helper.unregisterUSB() }
        runCatching { helper.release() }
        super.onDestroy()
    }

    /** 等 TextureView 的 SurfaceTexture 就绪后开预览（最多等 5 秒） */
    private fun startPreviewWhenReady(attempt: Int) {
        if (isFinishing || isDestroyed) return
        if (cameraView.isAvailable) {
            helper.startPreview(cameraView)
            return
        }
        if (attempt >= 50) {
            runOnUiThread { tvStatus.text = "预览初始化超时，请重插摄像头" }
            return
        }
        cameraView.postDelayed({ startPreviewWhenReady(attempt + 1) }, 100)
    }

    /** 抓当前帧 → 走现有识别管线。
     * 全程后台执行：captureStillImage 内部是「同步等渲染线程」的实现，
     * 预览未就绪时会在主线程无限阻塞（点按钮卡死）。这里优先用
     * TextureView.getBitmap()（秒回、不依赖渲染线程内部状态），
     * 兜底 captureStillImage 也放在独立线程并加 3 秒超时。
     */
    private fun captureAndRecognize() {
        if (!helper.isCameraOpened()) {
            Toast.makeText(this, "摄像头未连接，请先插入内窥镜", Toast.LENGTH_SHORT).show()
            return
        }
        if (recognizing) {
            Toast.makeText(this, "识别中，请稍候…", Toast.LENGTH_SHORT).show()
            return
        }
        recognizing = true
        tvStatus.text = "识别中…"
        Thread {
            val bmp = runCatching { cameraView.bitmap }.getOrNull()
                ?: runCatching {
                    val f = Executors.newSingleThreadExecutor().submit<Bitmap?> {
                        runCatching { cameraView.captureStillImage(640, 480) }.getOrNull()
                    }
                    try {
                        f.get(3, TimeUnit.SECONDS)
                    } catch (e: Exception) {
                        f.cancel(true)
                        null
                    }
                }.getOrNull()
            if (bmp == null) {
                runOnUiThread {
                    recognizing = false
                    tvStatus.text = "取帧失败：预览未就绪，请重插摄像头"
                    Toast.makeText(this, "取帧失败，请稍候再试", Toast.LENGTH_SHORT).show()
                }
                return@Thread
            }
            runOnUiThread { tvStatus.text = "识别中（${bmp.width}x${bmp.height}）…" }
            StaticRecognizer.recognize(
                bmp,
                lookup69 = { ean -> lookup69().lookup(ean) },
                onResult = { result ->
                    runOnUiThread {
                        recognizing = false
                        showResultDialog(result)
                    }
                },
                onError = { msg ->
                    runOnUiThread {
                        recognizing = false
                        tvStatus.text = "识别失败：$msg"
                        Toast.makeText(this, "识别失败：$msg", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }.start()
    }

    /** 结果弹窗：字段一览 + 保存/复制 SN */
    private fun showResultDialog(r: LabelResult) {
        val sns = buildList {
            addAll(r.barcodes.filter { it.isNotBlank() })
            if (r.serialNumber.isNotBlank()) add(r.serialNumber)
        }.distinct()

        val sb = StringBuilder()
        sb.append("物料：").append(r.materialCode.ifBlank { "—" }).append('\n')
        sb.append("箱号：").append(r.boxCode.ifBlank { "—" }).append('\n')
        sb.append("日期：").append(r.productionDate.ifBlank { "—" }).append('\n')
        sb.append("型号：").append(r.model.ifBlank { "—" }).append('\n')
        sb.append("69码：").append(r.ean69.ifBlank { "—" })
        if (r.materialFromEan69) sb.append("（69 反查）")
        sb.append('\n')
        sb.append("序列号：")
        if (sns.isEmpty()) sb.append("—")
        else sb.append(sns.joinToString("\n        "))
        sb.append("\n\n托盘：").append(TrayPrefs.get(this).ifBlank { "未设置" })

        val dialog = AlertDialog.Builder(this)
            .setTitle("识别结果")
            .setMessage(sb.toString())
            .setNegativeButton("复制 SN", null)
            .setNeutralButton("重拍", null)
            .setPositiveButton("✅ 保存到记录", null)
            .setCancelable(true)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (saveResult(r, sns)) dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                if (sns.isEmpty()) {
                    Toast.makeText(this, "没有识别到序列号，无法复制", Toast.LENGTH_SHORT).show()
                } else {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("sn", sns.joinToString("\n")))
                    Toast.makeText(this, "已复制 ${sns.size} 个序列号", Toast.LENGTH_SHORT).show()
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { dialog.dismiss() }
        }
        dialog.show()
    }

    /** 保存：每个 SN 展开一条记录（同 saveBox：物料/箱号/日期/型号共享），69 码反查入库 */
    private fun saveResult(r: LabelResult, sns: List<String>): Boolean {
        val tray = TrayPrefs.get(this)
        if (tray.isEmpty()) {
            Toast.makeText(this, "托盘号未设置：请先在单台/单箱入库页扫描托盘码", Toast.LENGTH_LONG).show()
            return false
        }
        if (sns.isEmpty()) {
            Toast.makeText(this, "没有识别到序列号，无法保存", Toast.LENGTH_SHORT).show()
            return false
        }
        if (r.materialCode.isBlank()) {
            Toast.makeText(this, "没有识别到物料编码，无法保存（可改用手动输入）", Toast.LENGTH_SHORT).show()
            return false
        }
        val records = sns.map { sn ->
            LabelResult(
                barcodes = listOf(sn),
                serialNumber = sn,
                materialCode = r.materialCode,
                quantity = sns.size,
                productionDate = r.productionDate,
                model = r.model,
                boxCode = r.boxCode,
                trayCode = tray,
                ean69 = r.ean69,
                materialFromEan69 = r.materialFromEan69,
            )
        }
        RecordStore.append(this, records)
        if (r.ean69.isNotBlank()) lookup69().learn(r.ean69, r.materialCode)
        tvStatus.text = String.format(Locale.ROOT, "✅ 已保存 %d 条（物料 %s / 箱号 %s）", records.size, r.materialCode, r.boxCode.ifBlank { "—" })
        Toast.makeText(this, "已保存 ${records.size} 条记录", Toast.LENGTH_SHORT).show()
        return true
    }
}
