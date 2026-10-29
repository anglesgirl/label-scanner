package com.anglesgirl.labelscanner

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.anglesgirl.labelscanner.data.DbExecutor
import com.anglesgirl.labelscanner.data.LedgerStore
import com.anglesgirl.labelscanner.util.LocationPrefs
import com.anglesgirl.labelscanner.util.TrayPrefs

/**
 * 出入库登记：顶部切入库/出库；扫码（沿用 LiveScan 字段补扫）或手动输入查库存，
 * 查到库存自动带入库位、托盘号和当前结存，只填数量点确认。
 * 登记只写本地库，成功后后台把增量推到远程数据库；推失败留脏标记，台账页同步再推。
 */
class LedgerRegisterActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TYPE = "ledger_type"
        const val EXTRA_MATERIAL = "ledger_material"
        const val EXTRA_LOCATION = "ledger_location"
        const val EXTRA_TRAY = "ledger_tray"
    }

    private lateinit var tvTitle: TextView
    private lateinit var btnTypeIn: Button
    private lateinit var btnTypeOut: Button
    private lateinit var etMaterial: EditText
    private lateinit var etLocation: EditText
    private lateinit var etTray: EditText
    private lateinit var tvBalance: TextView
    private lateinit var etQuantity: EditText
    private lateinit var btnConfirm: Button
    private lateinit var tvStatus: TextView

    private var moveType: String = LedgerStore.TYPE_IN

    /**
     * 扫码启动器：沿用单条入库的字段补扫（定格 + 点选填入）。
     * 点选模式一次回多个码时，让用户再点一个，避免静默丢弃其余码。
     */
    private val scanLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            val codes = data?.getStringArrayListExtra(LiveScanActivity.EXTRA_RESULT_CODES)
                ?: data?.getStringExtra(LiveScanActivity.EXTRA_RESULT_CODE)?.let { arrayListOf(it) }
                ?: arrayListOf()
            when {
                codes.isEmpty() -> Unit
                codes.size == 1 -> onCodeScanned(codes[0])
                else -> {
                    AlertDialog.Builder(this)
                        .setTitle("扫到多个码，用哪一个查库存")
                        .setItems(codes.toTypedArray()) { _, which -> onCodeScanned(codes[which]) }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
        }
    }

    /**
     * 库位专用启动器：只把扫到的值原样填进库位框（不做格式校验），
     * 绝不走 onCodeScanned → resolveScanned 的物料/托盘解析链 —— 库位码
     * （如 103A3501-1、A区-01-01）既不是物料也不是托盘，解析链会查错。
     * 填值会触发原有的手改监听（旧结存显示作废），这是期望行为。
     */
    private val locationLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            val codes = data?.getStringArrayListExtra(LiveScanActivity.EXTRA_RESULT_CODES)
                ?: data?.getStringExtra(LiveScanActivity.EXTRA_RESULT_CODE)?.let { arrayListOf(it) }
                ?: arrayListOf()
            when {
                codes.isEmpty() -> Unit
                codes.size == 1 -> etLocation.setText(codes[0])
                else -> {
                    AlertDialog.Builder(this)
                        .setTitle("扫到多个码，用哪一个作库位")
                        .setItems(codes.toTypedArray()) { _, which -> etLocation.setText(codes[which]) }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_ledger_register)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        tvTitle = findViewById(R.id.tvLedgerRegTitle)
        btnTypeIn = findViewById(R.id.btnTypeIn)
        btnTypeOut = findViewById(R.id.btnTypeOut)
        etMaterial = findViewById(R.id.etRegMaterial)
        etLocation = findViewById(R.id.etRegLocation)
        etTray = findViewById(R.id.etRegTray)
        tvBalance = findViewById(R.id.tvRegBalance)
        etQuantity = findViewById(R.id.etRegQuantity)
        btnConfirm = findViewById(R.id.btnRegConfirm)
        tvStatus = findViewById(R.id.tvRegStatus)

        moveType = intent.getStringExtra(EXTRA_TYPE) ?: LedgerStore.TYPE_IN
        val preMaterial = intent.getStringExtra(EXTRA_MATERIAL).orEmpty()
        val preLocation = intent.getStringExtra(EXTRA_LOCATION).orEmpty()
        val preTray = intent.getStringExtra(EXTRA_TRAY).orEmpty()
        if (preMaterial.isNotEmpty()) etMaterial.setText(preMaterial)
        if (preLocation.isNotEmpty()) etLocation.setText(preLocation)
        if (preTray.isNotEmpty()) etTray.setText(preTray)
        // 快捷入口没带整行时，库位/托盘沿用上一次（与托盘号整批沿用同款做法）。
        if (preMaterial.isEmpty()) {
            if (preLocation.isEmpty()) etLocation.setText(LocationPrefs.get(this))
            if (preTray.isEmpty()) etTray.setText(TrayPrefs.get(this))
        }
        updateTypeUi()

        btnTypeIn.setOnClickListener {
            moveType = LedgerStore.TYPE_IN
            updateTypeUi()
        }
        btnTypeOut.setOnClickListener {
            moveType = LedgerStore.TYPE_OUT
            updateTypeUi()
        }

        findViewById<Button>(R.id.btnScanLedger).setOnClickListener {
            scanLauncher.launch(
                Intent(this, LiveScanActivity::class.java)
                    .putExtra(LiveScanActivity.EXTRA_TITLE, "台账扫码")
                    .putExtra(LiveScanActivity.EXTRA_PICK_MODE, true),
            )
        }
        findViewById<Button>(R.id.btnScanMaterial).setOnClickListener {
            scanLauncher.launch(
                Intent(this, LiveScanActivity::class.java)
                    .putExtra(LiveScanActivity.EXTRA_TITLE, "物料编码")
                    .putExtra(LiveScanActivity.EXTRA_WANT_FIELD, "material")
                    .putExtra(LiveScanActivity.EXTRA_PICK_MODE, true),
            )
        }
        findViewById<Button>(R.id.btnOcrMaterial).setOnClickListener {
            // 连续采样识码：无条码的来料标识卡用 OCR 连续取帧、多帧命中后点选填入，
            // 走 scanLauncher 现成链路（RESULT_OK + EXTRA_RESULT_CODE → 查库存）。
            scanLauncher.launch(Intent(this, OcrPickActivity::class.java))
        }
        findViewById<Button>(R.id.btnScanTray).setOnClickListener {
            scanLauncher.launch(
                Intent(this, LiveScanActivity::class.java)
                    .putExtra(LiveScanActivity.EXTRA_TITLE, "托盘号")
                    .putExtra(LiveScanActivity.EXTRA_WANT_FIELD, "tray")
                    .putExtra(LiveScanActivity.EXTRA_PICK_MODE, true),
            )
        }
        findViewById<Button>(R.id.btnScanLocation).setOnClickListener {
            // 库位无固定格式、fieldScore 也无 location 分支：用普通点选模式，不传 wantField，
            // 多个码时由 locationLauncher 的选择框让用户点一个。
            locationLauncher.launch(
                Intent(this, LiveScanActivity::class.java)
                    .putExtra(LiveScanActivity.EXTRA_TITLE, "库位")
                    .putExtra(LiveScanActivity.EXTRA_PICK_MODE, true),
            )
        }
        findViewById<Button>(R.id.btnRegLookup).setOnClickListener { lookupFromForm() }
        btnConfirm.setOnClickListener { confirm() }

        // 手改物料/库位/托盘后，旧的结存显示作废，避免按旧数登记。
        // 注意：applyResolution 里先填字段、最后才写结存，所以程序填值不会被这道监听抹掉。
        val resetBalance = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                tvBalance.text = "当前结存：—"
            }
        }
        etMaterial.addTextChangedListener(resetBalance)
        etLocation.addTextChangedListener(resetBalance)
        etTray.addTextChangedListener(resetBalance)

        // 快捷入口带入整行时，直接查一次把结存显示出来。
        if (preMaterial.isNotEmpty()) lookupFromForm()
    }

    private fun updateTypeUi() {
        val isIn = moveType == LedgerStore.TYPE_IN
        tvTitle.text = if (isIn) "入库登记" else "出库登记"
        btnConfirm.text = if (isIn) "确认入库" else "确认出库"
        btnTypeIn.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (isIn) R.color.ls_ok else R.color.ls_text_dim),
        )
        btnTypeOut.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (isIn) R.color.ls_text_dim else R.color.ls_warn),
        )
        btnConfirm.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (isIn) R.color.ls_ok else R.color.ls_warn),
        )
    }

    /** 手动输入查库存：走和扫码同一条查找链路（物料优先，物料空时按托盘查）。 */
    private fun lookupFromForm() {
        val material = etMaterial.text.toString().trim()
        val tray = etTray.text.toString().trim()
        val code = if (material.isNotEmpty()) material else tray
        if (code.isEmpty()) {
            Toast.makeText(this, "请先填写或扫描物料编码", Toast.LENGTH_SHORT).show()
            return
        }
        tvStatus.text = "正在查库存…"
        DbExecutor.run({ LedgerStore.resolveScanned(this@LedgerRegisterActivity, code) }) { result ->
            result.onSuccess { applyResolution(it) }
                .onFailure { tvStatus.text = "查库存失败：${it.message}" }
        }
    }

    private fun onCodeScanned(code: String) {
        tvStatus.text = "正在查库存…"
        DbExecutor.run({ LedgerStore.resolveScanned(this@LedgerRegisterActivity, code) }) { result ->
            result.onSuccess { applyResolution(it) }
                .onFailure { tvStatus.text = "查库存失败：${it.message}" }
        }
    }

    /** 把解析结果填回表单：字段先填、结存最后写（否则手改监听会把刚写的结存抹掉）。 */
    private fun applyResolution(resolved: LedgerStore.Resolved) {
        if (resolved.materialCode.isNotEmpty()) etMaterial.setText(resolved.materialCode)
        if (resolved.trayCode.isNotEmpty()) etTray.setText(resolved.trayCode)
        tvStatus.text = resolved.note
        when {
            resolved.stocks.size == 1 -> {
                val row = resolved.stocks[0]
                etLocation.setText(row.location)
                if (etTray.text.toString().trim().isEmpty()) etTray.setText(row.trayCode)
                tvBalance.text = "当前结存：${row.balance}"
            }
            resolved.stocks.size > 1 -> {
                val items = resolved.stocks.map {
                    "${it.materialCode} · 库位 ${it.location} · 托盘 ${it.trayCode} · 结存 ${it.balance}"
                }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("该编码有多条库存，选一条")
                    .setItems(items) { _, which ->
                        val row = resolved.stocks[which]
                        etMaterial.setText(row.materialCode)
                        etLocation.setText(row.location)
                        etTray.setText(row.trayCode)
                        tvBalance.text = "当前结存：${row.balance}"
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            else -> {
                tvBalance.text = "当前结存：无记录"
            }
        }
    }

    private fun confirm() {
        val material = etMaterial.text.toString().trim()
        val location = etLocation.text.toString().trim()
        val tray = etTray.text.toString().trim()
        val quantity = etQuantity.text.toString().trim().toIntOrNull()
        if (material.isEmpty()) {
            Toast.makeText(this, "请填写或扫描物料编码", Toast.LENGTH_SHORT).show()
            return
        }
        if (quantity == null || quantity <= 0) {
            Toast.makeText(this, "请填写大于 0 的数量", Toast.LENGTH_SHORT).show()
            return
        }
        if (location.isEmpty()) {
            Toast.makeText(this, "请填写库位", Toast.LENGTH_SHORT).show()
            return
        }
        tvStatus.text = "正在登记…"
        DbExecutor.run({
            LedgerStore.applyMovement(this@LedgerRegisterActivity, moveType, material, quantity, location, tray)
        }) { result ->
            result.onSuccess { movement ->
                LocationPrefs.set(this@LedgerRegisterActivity, location)
                TrayPrefs.set(this@LedgerRegisterActivity, tray)
                tvBalance.text = "当前结存：${movement.balanceAfter}"
                tvStatus.text = if (moveType == LedgerStore.TYPE_IN) {
                    "✅ 入库成功 +$quantity，当前结存 ${movement.balanceAfter}"
                } else {
                    "✅ 出库成功 −$quantity，当前结存 ${movement.balanceAfter}"
                }
                etQuantity.setText("")
                etQuantity.requestFocus()
                // 登记完后台把增量推到远程；失败留脏标记，台账页同步再推。
                DbExecutor.run({
                    val stock = LedgerStore.findLocalStocksByMaterial(this@LedgerRegisterActivity, material)
                        .firstOrNull { it.location == location && it.trayCode == tray }
                    if (stock != null) {
                        LedgerStore.pushDelta(this@LedgerRegisterActivity, stock, movement)
                    } else {
                        "本地库存行丢失"
                    }
                }) { pushResult ->
                    pushResult.onSuccess { err ->
                        if (err != null) {
                            tvStatus.text = "${tvStatus.text}（远程同步失败，稍后点同步重试）"
                        }
                    }.onFailure {
                        tvStatus.text = "${tvStatus.text}（远程同步失败，稍后点同步重试）"
                    }
                }
            }.onFailure {
                tvStatus.text = "登记失败：${it.message}"
                Toast.makeText(this@LedgerRegisterActivity, it.message, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
