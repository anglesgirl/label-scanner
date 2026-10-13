package com.anglesgirl.labelscanner.util

import android.content.Context
import android.content.res.ColorStateList
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.view.View
import android.widget.SeekBar
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera

/**
 * 通用补光灯控制：开关(torch) + 亮度调节。
 *
 * - 开关：CameraX cameraControl.enableTorch —— 所有带闪光灯的设备可用
 * - 亮度（真 LED 亮度）：Android 13+ (API 33) 支持 CaptureRequest.FLASH_STRENGTH_LEVEL，
 *   部分机型可调闪光灯强度。优先用这个。
 * - 亮度（回退）：不支持 FLASH_STRENGTH_LEVEL 的设备，用曝光补偿(ExposureCompensation)
 *   映射 0~100，暗处开灯后调高让画面更亮
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
    // 是否支持真 LED 亮度调节（Android 13+ FLASH_STRENGTH_LEVEL）
    private var flashStrengthMaxLevel: Int = 0

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    fun attach(camera: Camera?) {
        this.camera = camera
        val canTorch = camera?.cameraInfo?.hasFlashUnit() == true
        // 检测是否支持真 LED 亮度调节（Android 13+）
        flashStrengthMaxLevel = 0
        if (canTorch && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                val cam2Info = Camera2CameraInfo.from(camera!!.cameraInfo)
                val maxLevel = cam2Info.getCameraCharacteristic(
                    CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL
                ) ?: 0
                flashStrengthMaxLevel = maxLevel
            }
        }
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

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun applyBrightness(brightness: Int) {
        val c = camera ?: return
        val level = brightness.coerceIn(0, 100)
        // 优先：真 LED 亮度（Android 13+，设备支持时）
        if (flashStrengthMaxLevel > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                val cam2Control = Camera2CameraControl.from(c.cameraControl)
                // 0~100 映射到 1~maxLevel（0 表示关灯，由 torch 开关控制）
                val strengthLevel = (1 + (flashStrengthMaxLevel - 1) * level / 100f).toInt()
                    .coerceIn(1, flashStrengthMaxLevel)
                cam2Control.setCaptureRequestOption(
                    CaptureRequest.FLASH_STRENGTH_LEVEL,
                    strengthLevel
                )
                return
            }
        }
        // 回退：曝光补偿
        val exposure = c.cameraInfo.exposureState
        val range = exposure.exposureCompensationRange
        if (range.lower == 0 && range.upper == 0) return // 设备不支持曝光补偿
        val idx = range.lower + ((range.upper - range.lower) * level / 100f).toInt()
        c.cameraControl.setExposureCompensationIndex(idx)
    }

    fun onDestroy() {
        runCatching { camera?.cameraControl?.enableTorch(false) }
        camera = null
    }
}
