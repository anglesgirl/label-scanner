package com.anglesgirl.labelscanner.util

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.widget.SeekBar
import androidx.camera.core.Camera

/**
 * 通用补光灯控制：开关(torch) + 亮度调节(曝光补偿)。
 *
 * - 开关：CameraX cameraControl.enableTorch —— 所有带闪光灯的设备可用
 * - 亮度：Android 无标准"LED 亮度"API，用曝光补偿(ExposureCompensation)映射 0~100，
 *   暗处开灯后调高让画面更亮（设备支持曝光补偿时生效）
 * - 亮度记忆：存 SharedPreferences，跨界面记住上次值
 * - 无闪光灯设备（含 USB 外接摄像头）自动隐藏按钮
 */
object TorchPrefs {
    private const val PREFS = "labelscanner_prefs"
    private const val KEY_BRIGHTNESS = "torch_brightness"
    private const val KEY_TORCH_ON = "torch_on"

    fun brightness(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_BRIGHTNESS, 100)

    fun setBrightness(context: Context, v: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_BRIGHTNESS, v.coerceIn(0, 100)).apply()
    }

    /** 上次补光灯开关状态：所有相机页共用，打开时恢复上次状态与亮度（用户 2026-09-19 要求） */
    fun torchOn(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_TORCH_ON, false)

    fun setTorchOn(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_TORCH_ON, on).apply()
    }
}

class TorchControl(
    private val context: Context,
    private val torchButton: View,
    private val brightnessBar: SeekBar?,
) {
    private var camera: Camera? = null
    private var torchOn = false

    fun attach(camera: Camera?) {
        this.camera = camera
        val canTorch = camera?.cameraInfo?.hasFlashUnit() == true
        torchButton.visibility = if (canTorch) View.VISIBLE else View.GONE
        brightnessBar?.visibility = View.GONE
        torchButton.setOnClickListener { toggle() }

        brightnessBar?.apply {
            max = 100
            progress = TorchPrefs.brightness(context)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) applyBrightness(progress)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    TorchPrefs.setBrightness(context, brightnessBar.progress)
                }
            })
        }

        // 恢复上一次的开关状态与亮度（用户 2026-09-19 要求：用上一次的开关和亮度）。
        // 相机就绪后才 attach，此时 enableTorch 才有效。
        if (canTorch && TorchPrefs.torchOn(context)) {
            runCatching { camera?.cameraControl?.enableTorch(true) }
            torchOn = true
            torchButton.isSelected = true
            torchButton.setBackgroundTintList(
                ColorStateList.valueOf(0xFF2E7D32.toInt())
            )
            brightnessBar?.visibility = View.VISIBLE
            applyBrightness(brightnessBar?.progress ?: TorchPrefs.brightness(context))
        }
    }

    /** USB 外接摄像头（内窥镜）没有闪光灯，禁用灯光。 */
    fun disableForUsbCamera() {
        torchButton.visibility = View.GONE
        brightnessBar?.visibility = View.GONE
    }

    private fun toggle() {
        val c = camera ?: return
        torchOn = !torchOn
        c.cameraControl.enableTorch(torchOn)
        TorchPrefs.setTorchOn(context, torchOn)
        torchButton.isSelected = torchOn
        torchButton.setBackgroundTintList(
            ColorStateList.valueOf(
                if (torchOn) 0xFF2E7D32.toInt() else 0xFF546E7A.toInt()
            )
        )
        brightnessBar?.visibility = if (torchOn) View.VISIBLE else View.GONE
        if (torchOn) applyBrightness(brightnessBar?.progress ?: TorchPrefs.brightness(context))
    }

    private fun applyBrightness(brightness: Int) {
        val c = camera ?: return
        val exposure = c.cameraInfo.exposureState
        val range = exposure.exposureCompensationRange
        if (range.lower == 0 && range.upper == 0) return // 设备不支持曝光补偿
        val idx = range.lower + ((range.upper - range.lower) * brightness.coerceIn(0, 100) / 100f).toInt()
        c.cameraControl.setExposureCompensationIndex(idx)
    }

    fun onDestroy() {
        runCatching { camera?.cameraControl?.enableTorch(false) }
        camera = null
    }
}
