package com.anglesgirl.labelscanner

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.anglesgirl.labelscanner.data.DbExecutor
import com.anglesgirl.labelscanner.data.LedgerStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 库存详情：列表只负责看，点行进这里，再对这一行做操作。
 *
 * 显示物料编码、库位、托盘号与当前结存（本地库为准，返回时重查）；
 * 入库/出库打开登记页并带入整行预填；移动打开转移页托盘间移动模式，
 * 带入源托盘 + 物料编码并自动查出源库位与结存。下方顺带列出该行
 * 最近 20 条流水，只读。
 */
class StockDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MATERIAL = "detail_material"
        const val EXTRA_LOCATION = "detail_location"
        const val EXTRA_TRAY = "detail_tray"
    }

    private lateinit var tvBalance: TextView
    private lateinit var tvMovements: TextView

    private var material = ""
    private var location = ""
    private var tray = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_stock_detail)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        material = intent.getStringExtra(EXTRA_MATERIAL).orEmpty()
        location = intent.getStringExtra(EXTRA_LOCATION).orEmpty()
        tray = intent.getStringExtra(EXTRA_TRAY).orEmpty()

        findViewById<TextView>(R.id.tvDetailMaterial).text = "物料编码：$material"
        findViewById<TextView>(R.id.tvDetailLocation).text = "库位：$location"
        findViewById<TextView>(R.id.tvDetailTray).text = "托盘号：$tray"
        tvBalance = findViewById(R.id.tvDetailBalance)
        tvMovements = findViewById(R.id.tvDetailMovements)

        findViewById<Button>(R.id.btnDetailIn).setOnClickListener { startRegister(LedgerStore.TYPE_IN) }
        findViewById<Button>(R.id.btnDetailOut).setOnClickListener { startRegister(LedgerStore.TYPE_OUT) }
        findViewById<Button>(R.id.btnDetailMove).setOnClickListener {
            startActivity(
                Intent(this, TransferActivity::class.java)
                    .putExtra(TransferActivity.EXTRA_SRC_TRAY, tray)
                    .putExtra(TransferActivity.EXTRA_MATERIAL, material),
            )
        }

        loadDetail()
    }

    override fun onResume() {
        super.onResume()
        loadDetail() // 从登记/转移页返回后刷新结存与流水
    }

    /** 本地库为准：查该行当前结存 + 该行最近 20 条流水。 */
    private fun loadDetail() {
        tvBalance.text = "当前结存：…"
        DbExecutor.run({
            val stock = LedgerStore.findLocalStocksByMaterial(this@StockDetailActivity, material)
                .firstOrNull { it.location == location && it.trayCode == tray }
            val moves = LedgerStore.listMovements(this@StockDetailActivity)
                .filter { it.materialCode == material && it.location == location && it.trayCode == tray }
                .take(20)
            stock to moves
        }) { result ->
            result.onSuccess { (stock, moves) ->
                tvBalance.text = if (stock != null) "当前结存：${stock.balance}" else "当前结存：无记录"
                tvMovements.text = if (moves.isEmpty()) {
                    "暂无流水"
                } else {
                    moves.joinToString("\n") {
                        val sign = if (it.moveType == LedgerStore.TYPE_IN) "+" else "−"
                        "${it.moveType} $sign${it.quantity} · 结存后 ${it.balanceAfter} · ${formatTime(it.createdAt)}"
                    }
                }
            }.onFailure {
                tvBalance.text = "当前结存：加载失败"
                tvMovements.text = "流水加载失败：${it.message}"
            }
        }
    }

    private fun startRegister(type: String) {
        startActivity(
            Intent(this, LedgerRegisterActivity::class.java)
                .putExtra(LedgerRegisterActivity.EXTRA_TYPE, type)
                .putExtra(LedgerRegisterActivity.EXTRA_MATERIAL, material)
                .putExtra(LedgerRegisterActivity.EXTRA_LOCATION, location)
                .putExtra(LedgerRegisterActivity.EXTRA_TRAY, tray),
        )
    }

    private fun formatTime(millis: Long): String {
        return try {
            SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
        } catch (_: Exception) {
            ""
        }
    }
}
