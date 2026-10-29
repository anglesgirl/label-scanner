package com.anglesgirl.labelscanner

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
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
import com.anglesgirl.labelscanner.data.TursoLedgerClient
import com.anglesgirl.labelscanner.util.LocationPrefs
import com.anglesgirl.labelscanner.util.TrayPrefs

/**
 * 库存移动：两种模式顶部切换（照登记页入库/出库切换的写法）。
 *
 * 托盘间移动（部分移量）：源托盘 + 物料编码 + 数量 → 目标托盘 + 目标库位，
 *   先查库存确定源库位与源结存，再单事务记一出一入两条流水。
 * 整托移库（整托换库位）：源托盘整托换到目标库位（托盘号不变），
 *   目标行存在则余额合并，不存在则改库位；每种物料记一出一入两条流水。
 *
 * 扫码/识码在这里一律只填字段，不走物料/托盘解析链；查库与移动只查本地库
 * （本地是工作副本，移动必须以本地结存为准）；流水用现有出库/入库类型，
 * 不新增类型。成功后后台同步一次。
 */
class TransferActivity : AppCompatActivity() {

    companion object {
        private const val MODE_PARTIAL = 0
        private const val MODE_WHOLE = 1
    }

    private lateinit var btnModePartial: Button
    private lateinit var btnModeWhole: Button
    private lateinit var layoutPartial: LinearLayout
    private lateinit var layoutWhole: LinearLayout
    private lateinit var tvStatus: TextView

    private lateinit var etSrcTray: EditText
    private lateinit var etMaterial: EditText
    private lateinit var etQuantity: EditText
    private lateinit var etDstTray: EditText
    private lateinit var etDstLocation: EditText
    private lateinit var tvSrcInfo: TextView

    private lateinit var etWholeSrcTray: EditText
    private lateinit var etWholeDstLocation: EditText
    private lateinit var tvWholeSummary: TextView

    private var mode = MODE_PARTIAL

    // 托盘间移动的查库存结果：物料/托盘改动后即作废，确认前必须重新查。
    private var resolvedMaterial = ""
    private var resolvedTray = ""
    private var resolvedLocation = ""
    private var resolvedBalance = 0

    // 整托移库的查托盘结果：托盘改动后即作废。
    private var wholeRows: List<TursoLedgerClient.StockRow> = emptyList()
    private var wholeTray = ""

    /**
     * 只填字段的通用启动器：扫码（LiveScan 点选）与识码（OcrPick）都走这里，
     * 多个码时弹框让用户点一个。绝不走 resolveScanned 解析链。
     */
    private var fillTarget: EditText? = null
    private val fillLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val target = fillTarget ?: return@registerForActivityResult
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            val codes = data?.getStringArrayListExtra(LiveScanActivity.EXTRA_RESULT_CODES)
                ?: data?.getStringExtra(LiveScanActivity.EXTRA_RESULT_CODE)?.let { arrayListOf(it) }
                ?: arrayListOf()
            when {
                codes.isEmpty() -> Unit
                codes.size == 1 -> target.setText(codes[0])
                else -> {
                    AlertDialog.Builder(this)
                        .setTitle("扫到多个码，用哪一个")
                        .setItems(codes.toTypedArray()) { _, which -> target.setText(codes[which]) }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_transfer)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        btnModePartial = findViewById(R.id.btnModePartial)
        btnModeWhole = findViewById(R.id.btnModeWhole)
        layoutPartial = findViewById(R.id.layoutPartial)
        layoutWhole = findViewById(R.id.layoutWhole)
        tvStatus = findViewById(R.id.tvTransStatus)

        etSrcTray = findViewById(R.id.etTransSrcTray)
        etMaterial = findViewById(R.id.etTransMaterial)
        etQuantity = findViewById(R.id.etTransQuantity)
        etDstTray = findViewById(R.id.etTransDstTray)
        etDstLocation = findViewById(R.id.etTransDstLocation)
        tvSrcInfo = findViewById(R.id.tvTransSrcInfo)

        etWholeSrcTray = findViewById(R.id.etWholeSrcTray)
        etWholeDstLocation = findViewById(R.id.etWholeDstLocation)
        tvWholeSummary = findViewById(R.id.tvWholeSummary)

        btnModePartial.setOnClickListener { setMode(MODE_PARTIAL) }
        btnModeWhole.setOnClickListener { setMode(MODE_WHOLE) }
        setMode(MODE_PARTIAL)

        // 托盘间移动的扫码/识码入口（只填字段）。
        findViewById<Button>(R.id.btnScanSrcTray).setOnClickListener {
            scanInto(etSrcTray, "源托盘", "tray")
        }
        findViewById<Button>(R.id.btnScanTransMaterial).setOnClickListener {
            scanInto(etMaterial, "物料编码", "material")
        }
        findViewById<Button>(R.id.btnOcrTransMaterial).setOnClickListener {
            ocrInto(etMaterial)
        }
        findViewById<Button>(R.id.btnScanDstTray).setOnClickListener {
            scanInto(etDstTray, "目标托盘", "tray")
        }
        findViewById<Button>(R.id.btnScanDstLocation).setOnClickListener {
            scanInto(etDstLocation, "目标库位")
        }
        findViewById<Button>(R.id.btnTransLookup).setOnClickListener { lookupPartial() }
        findViewById<Button>(R.id.btnTransConfirmPartial).setOnClickListener { confirmPartial() }

        // 整托移库的扫码入口（只填字段）。
        findViewById<Button>(R.id.btnScanWholeSrcTray).setOnClickListener {
            scanInto(etWholeSrcTray, "源托盘", "tray")
        }
        findViewById<Button>(R.id.btnScanWholeDstLocation).setOnClickListener {
            scanInto(etWholeDstLocation, "目标库位")
        }
        findViewById<Button>(R.id.btnWholeLookup).setOnClickListener { lookupWhole() }
        findViewById<Button>(R.id.btnWholeConfirm).setOnClickListener { confirmWhole() }

        // 物料/源托盘改动后，旧的查库存结果作废，避免按旧结存移动。
        val clearPartial = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                clearPartialResolved()
            }
        }
        etMaterial.addTextChangedListener(clearPartial)
        etSrcTray.addTextChangedListener(clearPartial)
        // 注意：lookupPartial 只写 tvSrcInfo 不写这两个框，所以程序查库不会被这道监听抹掉。
        etWholeSrcTray.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                wholeRows = emptyList()
                wholeTray = ""
                tvWholeSummary.text = "先点查托盘"
            }
        })
    }

    private fun setMode(newMode: Int) {
        mode = newMode
        val active = ContextCompat.getColor(this, R.color.ls_primary)
        val idle = ContextCompat.getColor(this, R.color.ls_text_dim)
        btnModePartial.backgroundTintList = ColorStateList.valueOf(if (mode == MODE_PARTIAL) active else idle)
        btnModeWhole.backgroundTintList = ColorStateList.valueOf(if (mode == MODE_WHOLE) active else idle)
        layoutPartial.visibility = if (mode == MODE_PARTIAL) View.VISIBLE else View.GONE
        layoutWhole.visibility = if (mode == MODE_WHOLE) View.VISIBLE else View.GONE
    }

    /** 条码点选：库位无 fieldScore 分支时不传 wantField，走普通点选。 */
    private fun scanInto(target: EditText, title: String, wantField: String? = null) {
        fillTarget = target
        val intent = Intent(this, LiveScanActivity::class.java)
            .putExtra(LiveScanActivity.EXTRA_TITLE, title)
            .putExtra(LiveScanActivity.EXTRA_PICK_MODE, true)
        if (wantField != null) intent.putExtra(LiveScanActivity.EXTRA_WANT_FIELD, wantField)
        fillLauncher.launch(intent)
    }

    /** 连续采样识码（复用登记页入口）：无条码的来料卡用 OCR 多帧命中点选填入。 */
    private fun ocrInto(target: EditText) {
        fillTarget = target
        fillLauncher.launch(Intent(this, OcrPickActivity::class.java))
    }

    // ---------------- 托盘间移动 ----------------

    /** 按（物料编码，源托盘）查本地库存：0 条报无记录、1 条直接定源、多条弹框选库位。 */
    private fun lookupPartial() {
        val material = etMaterial.text.toString().trim()
        val srcTray = etSrcTray.text.toString().trim()
        if (material.isEmpty() || srcTray.isEmpty()) {
            Toast.makeText(this, "请先填写物料编码和源托盘", Toast.LENGTH_SHORT).show()
            return
        }
        tvStatus.text = "正在查库存…"
        DbExecutor.run({
            LedgerStore.findLocalStocksByMaterial(this@TransferActivity, material)
                .filter { it.trayCode == srcTray }
        }) { result ->
            result.onSuccess { rows ->
                when {
                    rows.isEmpty() -> {
                        clearPartialResolved()
                        tvStatus.text = "该托盘无此物料库存"
                    }
                    rows.size == 1 -> {
                        setPartialResolved(material, srcTray, rows[0])
                        tvStatus.text = "已查到源库存"
                    }
                    else -> {
                        val items = rows.map { "库位 ${it.location} · 结存 ${it.balance}" }.toTypedArray()
                        AlertDialog.Builder(this)
                            .setTitle("该物料在源托盘有多个库位，选一条")
                            .setItems(items) { _, which ->
                                setPartialResolved(material, srcTray, rows[which])
                                tvStatus.text = "已查到源库存"
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                }
            }.onFailure { tvStatus.text = "查库存失败：${it.message}" }
        }
    }

    private fun setPartialResolved(material: String, tray: String, row: TursoLedgerClient.StockRow) {
        resolvedMaterial = material
        resolvedTray = tray
        resolvedLocation = row.location
        resolvedBalance = row.balance
        tvSrcInfo.text = "源库位：${row.location} · 源结存：${row.balance}"
    }

    private fun clearPartialResolved() {
        resolvedMaterial = ""
        resolvedTray = ""
        resolvedLocation = ""
        resolvedBalance = 0
        tvSrcInfo.text = "源库位：— · 源结存：—"
    }

    private fun confirmPartial() {
        val material = etMaterial.text.toString().trim()
        val srcTray = etSrcTray.text.toString().trim()
        val dstTray = etDstTray.text.toString().trim()
        val dstLocation = etDstLocation.text.toString().trim()
        val quantity = etQuantity.text.toString().trim().toIntOrNull()
        if (material.isEmpty() || srcTray.isEmpty()) {
            Toast.makeText(this, "请填写物料编码和源托盘", Toast.LENGTH_SHORT).show()
            return
        }
        if (quantity == null || quantity <= 0) {
            Toast.makeText(this, "请填写大于 0 的数量", Toast.LENGTH_SHORT).show()
            return
        }
        if (dstTray.isEmpty() || dstLocation.isEmpty()) {
            Toast.makeText(this, "请填写目标托盘和目标库位", Toast.LENGTH_SHORT).show()
            return
        }
        if (resolvedMaterial != material || resolvedTray != srcTray || resolvedLocation.isEmpty()) {
            Toast.makeText(this, "请先点查库存确定源库位", Toast.LENGTH_SHORT).show()
            return
        }
        if (quantity > resolvedBalance) {
            Toast.makeText(this, "数量超过源结存 $resolvedBalance", Toast.LENGTH_SHORT).show()
            return
        }
        if (resolvedLocation == dstLocation && srcTray == dstTray) {
            Toast.makeText(this, "目标与源相同，无需移动", Toast.LENGTH_SHORT).show()
            return
        }
        tvStatus.text = "正在移动…"
        DbExecutor.run({
            LedgerStore.transferBetweenTrays(
                this@TransferActivity, material, quantity,
                resolvedLocation, srcTray, dstLocation, dstTray,
            )
        }) { result ->
            result.onSuccess {
                LocationPrefs.set(this@TransferActivity, dstLocation)
                TrayPrefs.set(this@TransferActivity, dstTray)
                etQuantity.setText("")
                clearPartialResolved()
                syncAfterTransfer("✅ 已移动 $quantity 件：$material 从 $srcTray 到 $dstTray（库位 $dstLocation）")
            }.onFailure {
                tvStatus.text = "移动失败：${it.message}"
                Toast.makeText(this@TransferActivity, it.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------------- 整托移库 ----------------

    /** 按托盘号查出全部库存行，汇总显示种数、总件数与每种物料结存。 */
    private fun lookupWhole() {
        val tray = etWholeSrcTray.text.toString().trim()
        if (tray.isEmpty()) {
            Toast.makeText(this, "请先填写源托盘", Toast.LENGTH_SHORT).show()
            return
        }
        tvStatus.text = "正在查托盘…"
        DbExecutor.run({ LedgerStore.findLocalStocksByTray(this@TransferActivity, tray) }) { result ->
            result.onSuccess { rows ->
                if (rows.isEmpty()) {
                    wholeRows = emptyList()
                    wholeTray = ""
                    tvWholeSummary.text = "该托盘暂无库存"
                    tvStatus.text = ""
                } else {
                    wholeRows = rows
                    wholeTray = tray
                    val total = rows.sumOf { it.balance }
                    val lines = rows.joinToString("\n") { "${it.materialCode} · 库位 ${it.location} · 结存 ${it.balance}" }
                    tvWholeSummary.text = "${rows.size} 种物料、共 $total 件\n$lines"
                    tvStatus.text = "已查到托盘库存"
                }
            }.onFailure { tvStatus.text = "查托盘失败：${it.message}" }
        }
    }

    private fun confirmWhole() {
        val tray = etWholeSrcTray.text.toString().trim()
        val dstLocation = etWholeDstLocation.text.toString().trim()
        if (tray.isEmpty()) {
            Toast.makeText(this, "请填写源托盘", Toast.LENGTH_SHORT).show()
            return
        }
        if (dstLocation.isEmpty()) {
            Toast.makeText(this, "请填写目标库位", Toast.LENGTH_SHORT).show()
            return
        }
        if (wholeTray != tray || wholeRows.isEmpty()) {
            Toast.makeText(this, "请先点查托盘", Toast.LENGTH_SHORT).show()
            return
        }
        if (wholeRows.all { it.location == dstLocation }) {
            Toast.makeText(this, "已经在该库位", Toast.LENGTH_SHORT).show()
            return
        }
        tvStatus.text = "正在移动…"
        DbExecutor.run({
            LedgerStore.moveWholeTray(this@TransferActivity, tray, dstLocation)
        }) { result ->
            result.onSuccess { moved ->
                LocationPrefs.set(this@TransferActivity, dstLocation)
                TrayPrefs.set(this@TransferActivity, tray)
                wholeRows = emptyList()
                wholeTray = ""
                tvWholeSummary.text = "已移动，请重新查托盘确认"
                syncAfterTransfer("✅ 整托已移动 ${moved.movedKinds} 种物料、共 ${moved.movedTotal} 件到库位 $dstLocation")
            }.onFailure {
                tvStatus.text = "移动失败：${it.message}"
                Toast.makeText(this@TransferActivity, it.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 移动成功后后台同步一次，同步结果并进同一条提示，避免连弹两条 Toast。 */
    private fun syncAfterTransfer(successText: String) {
        tvStatus.text = "$successText（正在同步…）"
        DbExecutor.run({ LedgerStore.syncAll(this@TransferActivity) }) { result ->
            var ok = false
            result.onSuccess { err -> ok = err == null }
                .onFailure { ok = false }
            val text = if (ok) "$successText（已同步远程数据库）" else "$successText（远程同步失败，稍后点同步重试）"
            tvStatus.text = text
            Toast.makeText(this@TransferActivity, text, Toast.LENGTH_SHORT).show()
        }
    }
}
