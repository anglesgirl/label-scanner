package com.anglesgirl.labelscanner.camera

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.TextureView
import androidx.appcompat.app.AppCompatActivity
import com.serenegiant.usb.USBMonitor
import com.serenegiant.usb.UVCCamera
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

/**
 * A single UVC session. UI and TextureView access stay on main; all native camera work,
 * including monitor destruction, runs on one shared queue across Activities.
 * The receiver owns every delivered Bitmap.
 */
class UsbCameraController(
    private val activity: AppCompatActivity,
    private val view: UsbCameraPreviewView,
    private val onStatus: (String) -> Unit,
    private val onFrame: (Bitmap) -> Unit,
) {
    companion object {
        private const val FRAME_INTERVAL_MS = 200L
        private val cameraWorker = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "usb-camera-session").apply { isDaemon = true }
        }
        private var owner = WeakReference<UsbCameraController>(null)

        private fun assertMainThread() {
            check(Looper.myLooper() == Looper.getMainLooper()) {
                "UsbCameraController.start()/stop() must run on the main thread"
            }
        }

        private fun isVideoDevice(device: UsbDevice): Boolean {
            if (device.deviceClass == UsbConstants.USB_CLASS_VIDEO) return true
            for (i in 0 until device.interfaceCount) {
                if (device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_VIDEO) return true
            }
            return false
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val gate = UsbSessionGate()
    private val monitorLock = Any()
    @Volatile private var active = false
    @Volatile private var connected = false
    @Volatile private var surfaceGeneration = 0
    @Volatile private var surfaceAvailable = false
    @Volatile private var previewRunning = false
    // Only cameraWorker may read/write camera and call its native methods.
    private var camera: UVCCamera? = null
    @Volatile private var monitor: USBMonitor? = null
    @Volatile private var session = 0
    private var selectedDeviceName: String? = null
    private var requestedDeviceName: String? = null
    private var lastStatus: String? = null
    private var previewWidth = 640
    private var previewHeight = 480
    private var lastFrameTimestamp = 0L

    private val pollFrame = object : Runnable {
        override fun run() {
            if (!active) return
            if (connected && previewRunning && surfaceAvailable && view.isAvailable && !activity.isFinishing && !activity.isDestroyed) {
                val timestamp = runCatching { view.surfaceTexture?.timestamp ?: 0L }.getOrDefault(0L)
                val bitmap = if (timestamp > 0L && timestamp != lastFrameTimestamp)
                    runCatching { view.getBitmap(previewWidth, previewHeight) }.getOrNull() else null
                if (bitmap != null) {
                    lastFrameTimestamp = timestamp
                    try {
                        onFrame(bitmap)
                    } catch (t: Throwable) {
                        // Ownership transfers to the callback as soon as it is invoked.
                        failure("帧处理", t, session)
                    }
                }
            }
            if (active) main.postDelayed(this, FRAME_INTERVAL_MS)
        }
    }

    private val surfaceCallback = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            surfaceAvailable = true
            surfaceGeneration++
            tryStartPreview(session)
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            // TextureView resizing does not replace its producer surface. Do not restart UVC.
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            surfaceAvailable = false
            surfaceGeneration++
            // Keep the producer alive until native streaming has stopped. Returning true here
            // would let TextureView abandon a buffer queue that the UVC thread is still using.
            cameraWorker.execute {
                stopPreviewWorker()
                runCatching { surface.release() }
            }
            return false
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    }

    val isConnected: Boolean get() = connected

    fun start() {
        assertMainThread()
        if (active || activity.isFinishing || activity.isDestroyed) return
        owner.get()?.takeIf { it !== this }?.stop()
        owner = WeakReference(this)
        val token = synchronized(monitorLock) {
            session = gate.reset()
            active = true
            session
        }
        connected = false
        lastFrameTimestamp = 0L
        selectedDeviceName = null
        requestedDeviceName = null
        lastStatus = null
        view.surfaceTextureListener = surfaceCallback
        surfaceAvailable = view.isAvailable
        surfaceGeneration++
        report("等待 USB 摄像头连接")

        val listener = object : USBMonitor.OnDeviceConnectListener {
            override fun onAttach(device: UsbDevice) = dispatch(token) { requestVideoDevice(device, token) }

            override fun onDettach(device: UsbDevice) = dispatch(token) {
                if (device.deviceName == selectedDeviceName) handleDisconnect(token)
            }

            override fun onConnect(device: UsbDevice, ctrlBlock: USBMonitor.UsbControlBlock, createNew: Boolean) = dispatch(token) {
                if (device.deviceName != selectedDeviceName || !isVideoDevice(device)) return@dispatch
                // Duplicate permission/attach callbacks must never open two native sessions.
                val ticket = gate.claim(token, device.deviceName) ?: return@dispatch
                cameraWorker.execute { openCamera(device, ctrlBlock, ticket) }
            }

            override fun onDisconnect(device: UsbDevice, ctrlBlock: USBMonitor.UsbControlBlock) = dispatch(token) {
                if (device.deviceName == selectedDeviceName) handleDisconnect(token)
            }

            override fun onCancel(device: UsbDevice) = dispatch(token) {
                if (device.deviceName == selectedDeviceName) {
                    selectedDeviceName = null
                    requestedDeviceName = null
                    report("USB 摄像头权限被拒绝，可切换后重试")
                }
            }
        }

        main.removeCallbacks(pollFrame)
        main.postDelayed(pollFrame, FRAME_INTERVAL_MS)
        // A previous Activity may still be releasing its monitor on cameraWorker. Registering a
        // new receiver on the UI thread here allowed both USBMonitor instances to overlap. Keep
        // monitor creation/registration/initial enumeration behind the previous session's close.
        cameraWorker.execute {
            if (!active || token != session) return@execute
            var createdMonitor: USBMonitor? = null
            try {
                val usbMonitor = USBMonitor(activity.applicationContext, listener)
                createdMonitor = usbMonitor
                usbMonitor.register()
                val attached = usbMonitor.devices?.asSequence()?.firstOrNull(::isVideoDevice)
                val published = synchronized(monitorLock) {
                    if (!active || token != session) false
                    else {
                        monitor = usbMonitor
                        true
                    }
                }
                if (!published) {
                    runCatching { usbMonitor.destroy() }
                    return@execute
                }
                main.post {
                    if (!active || token != session || activity.isFinishing || activity.isDestroyed) return@post
                    attached?.let { requestVideoDevice(it, token) }
                }
            } catch (t: Throwable) {
                runCatching { createdMonitor?.destroy() }
                dispatch(token) {
                    failure("初始化", t, token)
                    stop()
                }
            }
        }
    }

    fun stop() {
        assertMainThread()
        if (!active && monitor == null) return
        val oldMonitor = synchronized(monitorLock) {
            active = false
            connected = false
            session = gate.reset()
            monitor.also { monitor = null }
        }
        main.removeCallbacks(pollFrame)
        // Leave the listener installed until TextureView destroys its surface; it defers release
        // behind native stop. A subsequent controller replaces the listener when it starts.
        cameraWorker.execute {
            releaseCameraWorker()
            runCatching { oldMonitor?.destroy() }.onFailure { Log.w("UsbCamera", "monitor destroy", it) }
        }
        selectedDeviceName = null
        requestedDeviceName = null
        if (owner.get() === this) owner.clear()
    }

    private fun requestVideoDevice(device: UsbDevice, token: Int) {
        if (!active || token != session || !isVideoDevice(device)) return
        val chosen = selectedDeviceName
        if (chosen != null && chosen != device.deviceName) return
        if (chosen == null) selectedDeviceName = device.deviceName
        if (requestedDeviceName == device.deviceName) return
        val currentMonitor = monitor ?: return
        requestedDeviceName = device.deviceName
        try {
            currentMonitor.requestPermission(device)
        } catch (t: Throwable) {
            selectedDeviceName = null
            requestedDeviceName = null
            failure("权限请求", t, token)
        }
    }

    private fun openCamera(device: UsbDevice, ctrlBlock: USBMonitor.UsbControlBlock, ticket: UsbSessionGate.Ticket) {
        if (!active || !gate.isCurrent(ticket)) return
        var opened: UVCCamera? = null
        try {
            // Constructor and static native-library loading MUST be inside the catch boundary.
            // UnsatisfiedLinkError/ExceptionInInitializerError otherwise kill the USB callback thread.
            val candidate = UVCCamera()
            opened = candidate
            candidate.open(ctrlBlock)
            val resolution = configurePreview(candidate)
            if (!active || !gate.isCurrent(ticket)) {
                runCatching { candidate.destroy() }
                return
            }
            releaseCameraWorker()
            camera = candidate
            main.post {
                if (!active || !gate.isCurrent(ticket) || device.deviceName != selectedDeviceName) return@post
                previewWidth = resolution.first
                previewHeight = resolution.second
                view.setAspectRatio(previewWidth, previewHeight)
                connected = true
                report("USB 摄像头已连接（${resolution.first}×${resolution.second}）")
                tryStartPreview(ticket.session)
            }
        } catch (t: Throwable) {
            runCatching { opened?.destroy() }
            if (gate.isCurrent(ticket)) failure("打开", t, ticket.session)
            // Keep the ticket claimed until detach or an explicit switch. Polling must not
            // repeatedly reload a failed driver or bombard the user with permission dialogs.
        }
    }

    private fun configurePreview(camera: UVCCamera): Pair<Int, Int> {
        val formats = intArrayOf(UVCCamera.FRAME_FORMAT_MJPEG, UVCCamera.FRAME_FORMAT_YUYV)
        val attempts = mutableListOf<String>()
        // supportedSizeList only reports the current frame format (YUYV before a
        // successful setPreviewSize), which hides MJPEG-only cameras. Format -1 means
        // all formats in this library version; try each reported size in both formats below.
        val descriptor = runCatching { camera.supportedSize }.getOrNull()
        val candidates = runCatching {
            UVCCamera.getSupportedSize(-1, descriptor.orEmpty()).orEmpty()
        }.onFailure { error ->
            attempts += "descriptor parse: ${error.javaClass.simpleName}: ${error.message}"
        }.getOrDefault(emptyList())
            .distinctBy { "${it.width}x${it.height}" }
            .sortedBy { size -> kotlin.math.abs(size.width - 640) + kotlin.math.abs(size.height - 480) }
        val orderedSizes = (listOf(UsbPreviewSize(640, 480)) + candidates.map { UsbPreviewSize(it.width, it.height) }).distinct()
        val selected = UsbPreviewModeSelector.firstWorking(orderedSizes, formats.toList()) { size, format ->
            val error = runCatching { camera.setPreviewSize(size.width, size.height, format) }.exceptionOrNull()
            if (error != null) {
                attempts += "${size.width}x${size.height}/${formatName(format)}: ${error.javaClass.simpleName}: ${error.message}"
            }
            error == null
        }
        if (selected != null) return selected.size.width to selected.size.height
        val device = runCatching { camera.device }.getOrNull()
        val deviceHint = device?.let { " VID=${it.vendorId.toString(16)} PID=${it.productId.toString(16)}" }.orEmpty()
        val descriptorHint = descriptor?.take(1000) ?: "<读取失败或为空>"
        val candidateHint = candidates.joinToString(", ") { "${it.width}x${it.height}" }.ifEmpty { "<无>" }
        throw IllegalStateException(
            "没有可用的 USB 视频分辨率/格式$deviceHint; descriptor=$descriptorHint; " +
                "候选=$candidateHint; 协商=${attempts.joinToString(" | ").take(2400)}"
        )
    }

    private fun formatName(format: Int): String = when (format) {
        UVCCamera.FRAME_FORMAT_MJPEG -> "MJPEG"
        UVCCamera.FRAME_FORMAT_YUYV -> "YUYV"
        else -> format.toString()
    }

    private fun tryStartPreview(token: Int) {
        if (!active || token != session || !connected || !surfaceAvailable) return
        val texture = view.surfaceTexture ?: return
        val generation = surfaceGeneration
        val ticket = gate.currentTicket() ?: return
        texture.setDefaultBufferSize(previewWidth, previewHeight)
        cameraWorker.execute {
            if (!active || !gate.isCurrent(ticket) || !surfaceAvailable || generation != surfaceGeneration || previewRunning) return@execute
            val currentCamera = camera ?: return@execute
            try {
                currentCamera.setPreviewTexture(texture)
                currentCamera.startPreview()
                previewRunning = true
            } catch (t: Throwable) {
                failure("预览启动", t, token)
            }
        }
    }

    private fun stopPreviewWorker() {
        previewRunning = false
        runCatching { camera?.stopPreview() }.onFailure { Log.w("UsbCamera", "stop preview", it) }
    }

    private fun releaseCameraWorker() {
        stopPreviewWorker()
        val oldCamera = camera
        camera = null
        runCatching { oldCamera?.destroy() }.onFailure { Log.w("UsbCamera", "destroy camera", it) }
    }

    private fun handleDisconnect(token: Int) {
        if (token != session) return
        gate.disconnect()
        connected = false
        lastFrameTimestamp = 0L
        cameraWorker.execute { releaseCameraWorker() }
        selectedDeviceName = null
        requestedDeviceName = null
        report("USB 摄像头已断开，请重新插入")
    }

    private fun dispatch(token: Int, action: () -> Unit) {
        main.post {
            if (active && token == session && !activity.isFinishing && !activity.isDestroyed) action()
        }
    }

    private fun failure(phase: String, error: Throwable, token: Int) {
        Log.e("UsbCamera", "USB camera $phase failed", error)
        // Use the existing local crash-log viewer for handled errors too; no network required.
        runCatching {
            val file = java.io.File(activity.filesDir, com.anglesgirl.labelscanner.util.CrashHandler.LOG_FILE)
            if (file.length() > 256 * 1024) file.writeText("")
            file.appendText("===== USB $phase ${java.util.Date()} =====\n${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}\n${Log.getStackTraceString(error)}\n")
        }
        val message = "USB 摄像头${phase}失败：${error.message?.take(180) ?: error.javaClass.simpleName}；可切换手机摄像头，详细原因已记录"
        if (Looper.myLooper() == Looper.getMainLooper() && active && token == session) {
            report(message)
        } else {
            dispatch(token) { report(message) }
        }
    }

    private fun report(message: String) {
        assertMainThread()
        if (message == lastStatus) return
        lastStatus = message
        runCatching { onStatus(message) }
    }
}
