package com.anglesgirl.labelscanner

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.anglesgirl.labelscanner.data.DbExecutor
import com.anglesgirl.labelscanner.data.LedgerStore
import com.anglesgirl.labelscanner.data.TursoLedgerClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 📒 台账管理：库存结存列表 + 出入流水，数据存远程数据库存档。
 * 本地 SQLite 是工作副本，打开时后台同步一次（先推脏数据再拉远程），
 * 同步失败只影响远程存档，本地照常看照常记。
 */
class LedgerActivity : AppCompatActivity() {

    private lateinit var etSearch: EditText
    private lateinit var tvStatus: TextView
    private lateinit var btnTabStock: Button
    private lateinit var btnTabMovements: Button
    private lateinit var listLedger: ListView
    private lateinit var tvEmpty: TextView

    private var allStocks: List<TursoLedgerClient.StockRow> = emptyList()
    private var allMovements: List<TursoLedgerClient.MovementRow> = emptyList()
    private var showMovements = false
    private var searchQuery = ""
    private var syncMessage: String? = null
    private var refreshGen = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_ledger)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        etSearch = findViewById(R.id.etLedgerSearch)
        tvStatus = findViewById(R.id.tvLedgerStatus)
        btnTabStock = findViewById(R.id.btnTabStock)
        btnTabMovements = findViewById(R.id.btnTabMovements)
        listLedger = findViewById(R.id.listLedger)
        tvEmpty = findViewById(R.id.tvLedgerEmpty)

        findViewById<Button>(R.id.btnLedgerSearch).setOnClickListener {
            searchQuery = etSearch.text.toString().trim()
            render()
        }
        btnTabStock.setOnClickListener {
            showMovements = false
            updateTabs()
            render()
        }
        btnTabMovements.setOnClickListener {
            showMovements = true
            updateTabs()
            render()
        }
        findViewById<Button>(R.id.btnRegisterIn).setOnClickListener {
            startRegister(LedgerStore.TYPE_IN, null)
        }
        findViewById<Button>(R.id.btnRegisterOut).setOnClickListener {
            startRegister(LedgerStore.TYPE_OUT, null)
        }
        findViewById<Button>(R.id.btnLedgerSync).setOnClickListener { syncNow() }

        refreshLocal()
        syncNow()
    }

    override fun onResume() {
        super.onResume()
        refreshLocal() // 从登记页返回后刷新
    }

    /** 本地加载移后台线程；gen 计数丢弃过期结果（如返回本页时旧加载仍在跑）。 */
    private fun refreshLocal() {
        val gen = ++refreshGen
        DbExecutor.run({
            LedgerStore.listStock(this@LedgerActivity) to LedgerStore.listMovements(this@LedgerActivity)
        }) { result ->
            if (gen != refreshGen) return@run
            result.onSuccess { (stocks, movements) ->
                allStocks = stocks
                allMovements = movements
                render()
            }.onFailure {
                Toast.makeText(this@LedgerActivity, "加载失败：${it.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 全量同步（先推脏数据再拉远程），失败只改状态行，不动本地数据。 */
    private fun syncNow() {
        syncMessage = "正在同步远程数据库…"
        render()
        DbExecutor.run({ LedgerStore.syncAll(this@LedgerActivity) }) { result ->
            result.onSuccess { err ->
                syncMessage = if (err == null) "已同步远程数据库" else "同步失败：$err（仅本地数据）"
                refreshLocal()
            }.onFailure {
                syncMessage = "同步失败：${it.message}（仅本地数据）"
                render()
            }
        }
    }

    private fun updateTabs() {
        val active = ContextCompat.getColor(this, R.color.ls_primary)
        val idle = ContextCompat.getColor(this, R.color.ls_text_dim)
        btnTabStock.backgroundTintList = ColorStateList.valueOf(if (showMovements) idle else active)
        btnTabMovements.backgroundTintList = ColorStateList.valueOf(if (showMovements) active else idle)
    }

    private fun matches(material: String, location: String, tray: String): Boolean {
        if (searchQuery.isEmpty()) return true
        return material.contains(searchQuery) || location.contains(searchQuery) || tray.contains(searchQuery)
    }

    private fun render() {
        val counts = "库存 ${allStocks.size} 条 · 流水 ${allMovements.size} 条"
        tvStatus.text = if (syncMessage != null) "$syncMessage（$counts）" else counts
        if (showMovements) {
            val filtered = allMovements.filter { matches(it.materialCode, it.location, it.trayCode) }
            tvEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
            tvEmpty.text = "暂无出入流水"
            listLedger.adapter = object : ArrayAdapter<TursoLedgerClient.MovementRow>(this, 0, filtered) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val view = convertView ?: LayoutInflater.from(this@LedgerActivity)
                        .inflate(R.layout.item_ledger_movement, parent, false)
                    val mv = getItem(position) ?: return view
                    val sign = if (mv.moveType == LedgerStore.TYPE_IN) "+" else "−"
                    view.findViewById<TextView>(R.id.tvMoveMain).text =
                        "${mv.moveType} $sign${mv.quantity} · ${mv.materialCode}"
                    view.findViewById<TextView>(R.id.tvMoveSub).text =
                        "库位 ${mv.location} · 托盘 ${mv.trayCode} · 结存后 ${mv.balanceAfter} · ${formatTime(mv.createdAt)}"
                    return view
                }
            }
        } else {
            val filtered = allStocks.filter { matches(it.materialCode, it.location, it.trayCode) }
            tvEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
            tvEmpty.text = "暂无台账记录，先登记一笔入库"
            listLedger.adapter = object : ArrayAdapter<TursoLedgerClient.StockRow>(this, 0, filtered) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val view = convertView ?: LayoutInflater.from(this@LedgerActivity)
                        .inflate(R.layout.item_ledger_stock, parent, false)
                    val row = getItem(position) ?: return view
                    view.findViewById<TextView>(R.id.tvStockMain).text =
                        "${row.materialCode}  结存 ${row.balance}"
                    view.findViewById<TextView>(R.id.tvStockSub).text =
                        "库位 ${row.location} · 托盘 ${row.trayCode}"
                    view.findViewById<Button>(R.id.btnStockIn).setOnClickListener {
                        startRegister(LedgerStore.TYPE_IN, row)
                    }
                    view.findViewById<Button>(R.id.btnStockOut).setOnClickListener {
                        startRegister(LedgerStore.TYPE_OUT, row)
                    }
                    return view
                }
            }
        }
    }

    private fun startRegister(type: String, row: TursoLedgerClient.StockRow?) {
        val intent = Intent(this, LedgerRegisterActivity::class.java)
            .putExtra(LedgerRegisterActivity.EXTRA_TYPE, type)
        if (row != null) {
            intent.putExtra(LedgerRegisterActivity.EXTRA_MATERIAL, row.materialCode)
            intent.putExtra(LedgerRegisterActivity.EXTRA_LOCATION, row.location)
            intent.putExtra(LedgerRegisterActivity.EXTRA_TRAY, row.trayCode)
        }
        startActivity(intent)
    }

    private fun formatTime(millis: Long): String {
        return try {
            SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
        } catch (_: Exception) {
            ""
        }
    }
}
