package com.anglesgirl.labelscanner.camera

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.PointF
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.anglesgirl.labelscanner.R
import java.io.File

/**
 * 拉正页：系统相机高清图 → 手动四角拉正（拍正可跳过）→ 增强 → 返回拉正图 URI。
 * 零依赖，透视变换用 ImageWarp（android.graphics）。
 */
class CropActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_INPUT_URI = "extra_input_uri"
        const val EXTRA_OUTPUT_URI = "extra_output_uri"
        private const val MODE_ORIGIN = 0
        private const val MODE_ENHANCE = 1
        private const val MODE_BW = 2
    }

    private lateinit var ivCrop: ImageView
    private lateinit var overlay: CropOverlayView
    private var inputUri: Uri? = null
    private var mode: Int = MODE_ENHANCE // 默认增强，最能救浅字

    private val srcBitmap by lazy {
        inputUri?.let {
            contentResolver.openInputStream(it)?.use { BitmapFactory.decodeStream(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crop)

        ivCrop = findViewById(R.id.ivCrop)
        overlay = findViewById(R.id.cropOverlay)
        inputUri = intent.getParcelableExtra(EXTRA_INPUT_URI)

        if (inputUri == null || srcBitmap == null) {
            Toast.makeText(this, "图片读取失败", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        ivCrop.setImageURI(inputUri)

        // 等布局完成，用 ImageView 显示区域初始化四角（贴合图边）
        ivCrop.post {
            val rect = android.graphics.RectF()
            ivCrop.getImageMatrix().mapRect(rect, android.graphics.RectF(
                0f, 0f, srcBitmap!!.width.toFloat(), srcBitmap!!.height.toFloat()))
            val loc = IntArray(2)
            ivCrop.getLocationOnScreen(loc)
            overlay.initCorners(
                rect.left, rect.top,
                rect.right, rect.bottom
            )
        }

        findViewById<Button>(R.id.btnModeOrigin).setOnClickListener { mode = MODE_ORIGIN; renderPreview() }
        findViewById<Button>(R.id.btnModeEnhance).setOnClickListener { mode = MODE_ENHANCE; renderPreview() }
        findViewById<Button>(R.id.btnModeBw).setOnClickListener { mode = MODE_BW; renderPreview() }
        findViewById<Button>(R.id.btnCropCancel).setOnClickListener { setResult(RESULT_CANCELED); finish() }
        findViewById<Button>(R.id.btnCropConfirm).setOnClickListener { confirm() }

        renderPreview()
    }

    private fun renderPreview() {
        val bmp = srcBitmap ?: return
        val out = when (mode) {
            MODE_ORIGIN -> bmp
            MODE_ENHANCE -> ImageWarp.enhance(bmp)
            MODE_BW -> ImageWarp.blackAndWhite(bmp)
            else -> bmp
        }
        ivCrop.setImageBitmap(out)
        ivCrop.tag = out
    }

    private fun confirm() {
        val bmp = srcBitmap ?: return
        // 用 ImageView 当前显示区域把 View 坐标角点映射到源图坐标
        val rect = android.graphics.RectF()
        ivCrop.getImageMatrix().mapRect(rect, android.graphics.RectF(
            0f, 0f, bmp.width.toFloat(), bmp.height.toFloat()))
        val scaleX = bmp.width.toFloat() / rect.width()
        val scaleY = bmp.height.toFloat() / rect.height()
        val (locX, locY) = IntArray(2).also { ivCrop.getLocationOnScreen(it) }

        val srcCorners = overlay.corners.map { c ->
            PointF(
                ((c.x - rect.left) * scaleX).coerceIn(0f, bmp.width.toFloat()),
                ((c.y - rect.top) * scaleY).coerceIn(0f, bmp.height.toFloat())
            )
        }

        // 若四角基本贴合全图（用户没拖），直接增强原图，省去变换
        val isFull = srcCorners.all { p ->
            p.x in -2f..2f || p.x in (bmp.width - 2f)..(bmp.width + 2f)
        } && srcCorners.all { p ->
            p.y in -2f..2f || p.y in (bmp.height - 2f)..(bmp.height + 2f)
        }

        val warped = if (isFull) {
            when (mode) {
                MODE_ENHANCE -> ImageWarp.enhance(bmp)
                MODE_BW -> ImageWarp.blackAndWhite(bmp)
                else -> bmp
            }
        } else {
            val (w, h) = ImageWarp.outputSize(srcCorners)
            var t = ImageWarp.perspectiveTransform(bmp, srcCorners, w, h)
            t = when (mode) {
                MODE_ENHANCE -> ImageWarp.enhance(t)
                MODE_BW -> ImageWarp.blackAndWhite(t)
                else -> t
            }
            t
        }

        // 存临时文件返回
        val outFile = File(cacheDir, "warped_${System.currentTimeMillis()}.jpg")
        outFile.outputStream().use { warped.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it) }
        val outUri = Uri.fromFile(outFile)
        setResult(RESULT_OK, Intent().putExtra(EXTRA_OUTPUT_URI, outUri.toString()))
        finish()
    }
}
