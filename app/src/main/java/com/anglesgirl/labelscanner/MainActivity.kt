package com.anglesgirl.labelscanner

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.ViewCompat
import androidx.core.view.updatePadding
import com.anglesgirl.labelscanner.camera.SlipOcrRunner
import com.anglesgirl.labelscanner.data.SlipStore
import com.anglesgirl.labelscanner.data.SlipUi
import com.anglesgirl.labelscanner.util.CrashHandler
import java.io.File

/**
 * 主界面导航：扫描录入、托盘中心、台账管理与设置。
 * 台账与扫描录入保持独立入口，数据分别由 RecordStore / LedgerStore 管理。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var btnSlip: Button
    private var pendingSlipPhoto: File? = null

    /** 拍单据：系统相机全分辨率拍照（文档要能看清），拍完输单号即设为当前单据。 */
    private val takeSlipPhoto =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            val tmp = pendingSlipPhoto
            pendingSlipPhoto = null
            if (success && tmp != null && tmp.exists()) {
                confirmSlipPhoto(tmp)
            } else {
                tmp?.delete()
                Toast.makeText(this, "拍照已取消", Toast.LENGTH_SHORT).show()
            }
        }

    /** 单据从相册选本地图片：拷到临时文件后走同一套“输单号 → 归档”流程。 */
    private val pickSlipGallery =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@registerForActivityResult
            val tmp = SlipStore.newTempPhotoFile(this)
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (_: Exception) {
                Toast.makeText(this, "读取图片失败", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
            if (tmp.exists() && tmp.length() > 0) confirmSlipPhoto(tmp)
            else Toast.makeText(this, "读取图片失败", Toast.LENGTH_SHORT).show()
        }

    /** 单据附加本地文件（表格等单独文件，跟图片一样归进单据）：拷临时文件后输单号归档登记。 */
    private val pickSlipFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            val displayName = contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            } ?: "file_${System.currentTimeMillis()}"
            val tmp = SlipStore.newTempFile(this, displayName)
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (_: Exception) {
                Toast.makeText(this, "读取文件失败", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
            if (!tmp.exists() || tmp.length() <= 0) {
                Toast.makeText(this, "读取文件失败", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
            val initial = SlipStore.getCurrent(this).ifBlank { SlipStore.generateSlipNo() }
            SlipUi.showSlipNumberDialog(this, initial) { slipNo ->
                val path = SlipStore.storeFile(this, tmp, slipNo, displayName)
                SlipStore.addFile(this, slipNo, path, displayName)
                SlipStore.setCurrent(this, slipNo)
                Toast.makeText(this, "已附加到单据 $slipNo：$displayName", Toast.LENGTH_SHORT).show()
                refreshSlipButton()
            }
        }

    /** 拍照/相册共用：先 OCR 读数量/箱数/物料，再弹确认框（四项都能改）→ 归档 → 设为当前单据。 */
    private fun confirmSlipPhoto(tmp: java.io.File) {
        Toast.makeText(this, "正在识别单据…", Toast.LENGTH_SHORT).show()
        SlipOcrRunner.run(tmp) { ocr ->
            if (isFinishing || isDestroyed) return@run
            SlipUi.showSlipOcrDialog(this, SlipStore.generateSlipNo(), ocr) { slipNo, qty, boxes, mat ->
                val path = SlipStore.storePhoto(this, tmp, slipNo)
                SlipStore.saveSlip(this, slipNo, path, qty, boxes, mat)
                Toast.makeText(this, "已设为当前单据：$slipNo，之后采集自动跟单", Toast.LENGTH_SHORT).show()
                refreshSlipButton()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 全局崩溃捕获：写日志文件，下次启动弹窗展示。
        CrashHandler.install(applicationContext)
        // Edge-to-edge：与其他页面保持一致，避免首页内容被系统栏遮挡。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        // 上次崩溃日志：弹窗展示，可一键复制用于排查。
        CrashHandler.read(this)?.let { log ->
            AlertDialog.Builder(this)
                .setTitle("上次运行崩溃")
                .setMessage(log.take(4000))
                .setPositiveButton("复制日志") { _, _ ->
                    val cm = getSystemService(android.content.ClipboardManager::class.java)
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", log))
                    Toast.makeText(this, "崩溃日志已复制", Toast.LENGTH_SHORT).show()
                    CrashHandler.clear(this)
                }
                .setNegativeButton("清除") { _, _ -> CrashHandler.clear(this) }
                .setNeutralButton("关闭", null)
                .setCancelable(false)
                .show()
        }

        findViewById<Button>(R.id.btnModeSingle).setOnClickListener {
            startActivity(Intent(this, SingleInboundActivity::class.java))
        }
        findViewById<Button>(R.id.btnModeBox).setOnClickListener {
            startActivity(Intent(this, SingleBoxInboundActivity::class.java))
        }
        findViewById<Button>(R.id.btnModeListBatch).setOnClickListener {
            startActivity(Intent(this, ListBatchInboundActivity::class.java))
        }
        findViewById<Button>(R.id.btnSplitCode).setOnClickListener {
            startActivity(Intent(this, SplitCodeActivity::class.java))
        }
        findViewById<Button>(R.id.btnGotoCenter).setOnClickListener {
            startActivity(Intent(this, RecordListActivity::class.java))
        }
        findViewById<Button>(R.id.btnLedger).setOnClickListener {
            startActivity(Intent(this, LedgerActivity::class.java))
        }
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        btnSlip = findViewById(R.id.btnSlip)
        btnSlip.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("添加单据")
                .setItems(arrayOf("📷 拍照", "🖼️ 相册选本地图片", "📄 文件（表格等单独文件）")) { _, which ->
                    when (which) {
                        0 -> {
                            val tmp = SlipStore.newTempPhotoFile(this)
                            pendingSlipPhoto = tmp
                            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", tmp)
                            takeSlipPhoto.launch(uri)
                        }
                        1 -> pickSlipGallery.launch("image/*")
                        else -> pickSlipFile.launch(arrayOf("*/*"))
                    }
                }
                .show()
        }

        // 防误触提示：托盘中心是唯一的 WMS 批量导出入口。
        findViewById<Button>(R.id.btnGotoCenter).setOnLongClickListener {
            Toast.makeText(this, "录入完成后在托盘中心勾选托盘导出 WMS", Toast.LENGTH_SHORT).show()
            true
        }
    }

    override fun onResume() {
        super.onResume()
        refreshSlipButton()
    }

    /** 首页拍单据按钮文案：显示当前单据，提醒之后采集自动跟单。 */
    private fun refreshSlipButton() {
        if (!::btnSlip.isInitialized) return
        val current = SlipStore.getCurrent(this)
        btnSlip.text = if (current.isNotBlank()) {
            "📷 拍单据\n当前单据：$current · 之后采集自动跟单"
        } else {
            "📷 拍单据\n送货单/退货单拍照 · 编号跟单"
        }
    }
}
