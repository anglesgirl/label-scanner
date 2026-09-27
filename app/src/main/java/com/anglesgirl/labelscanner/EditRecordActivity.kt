package com.anglesgirl.labelscanner

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import com.anglesgirl.labelscanner.data.DbExecutor
import com.anglesgirl.labelscanner.data.RecordStore
import com.anglesgirl.labelscanner.model.LabelResult

/**
 * 编辑页：修改一条已保存记录的全部字段。
 * 保存后写回 RecordStore，返回列表页。
 */
class EditRecordActivity : AppCompatActivity() {

    private var index = -1
    /** onCreate 后台加载后赋值；save 时以最新库为准重载再按下标写回。 */
    private var records: List<LabelResult> = emptyList()

    private lateinit var etSupplier: EditText
    private lateinit var etSn: EditText
    private lateinit var etTrayCode: EditText
    private lateinit var etMaterial: EditText
    private lateinit var etQty: EditText
    private lateinit var etDate: EditText
    private lateinit var etEan69: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge：内容延伸到状态栏/导航栏底下，避免遮挡
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_edit_record)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { view, insets ->
            val sysBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(sysBars.left, sysBars.top, sysBars.right, sysBars.bottom)
            insets
        }

        index = intent.getIntExtra("index", -1)
        if (index < 0) {
            finish()
            return
        }

        etSupplier = findViewById(R.id.etSupplier)
        etSn = findViewById(R.id.etSn)
        etTrayCode = findViewById(R.id.etTrayCode)
        etMaterial = findViewById(R.id.etMaterial)
        etQty = findViewById(R.id.etQty)
        etDate = findViewById(R.id.etDate)
        etEan69 = findViewById(R.id.etEan69)

        // 全量加载移后台线程，避免数据量大时打开编辑页卡顿
        DbExecutor.run({ RecordStore.load(this@EditRecordActivity) }) { result ->
            result.onSuccess { loaded ->
                records = loaded
                if (index >= loaded.size) {
                    Toast.makeText(this, "记录不存在", Toast.LENGTH_SHORT).show()
                    finish()
                    return@onSuccess
                }
                val r = loaded[index]
                findViewById<TextView>(R.id.tvEditTitle).text = "编辑记录 #${index + 1}"

                etSupplier.setText(r.supplier)
                etSn.setText(r.serialNumber)
                etTrayCode.setText(r.trayCode)
                etMaterial.setText(r.materialCode)
                etQty.setText(r.quantity.toString())
                etDate.setText(r.productionDate)
                etEan69.setText(r.ean69)
            }.onFailure {
                Toast.makeText(this, "加载失败：${it.message}", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

        findViewById<Button>(R.id.btnEditSave).setOnClickListener { save() }
        findViewById<Button>(R.id.btnEditCancel).setOnClickListener { finish() }
    }

    private fun save() {
        if (index >= records.size) {
            finish()
            return
        }
        val old = records[index]
        val trayCode = etTrayCode.text.toString().trim()
        if (trayCode.isEmpty()) {
            Toast.makeText(this, "托盘号必填", Toast.LENGTH_SHORT).show()
            return
        }
        val updated = old.copy(
            supplier = etSupplier.text.toString().trim().ifBlank { "NA" },
            serialNumber = etSn.text.toString().trim(),
            trayCode = trayCode,
            materialCode = etMaterial.text.toString().trim(),
            quantity = etQty.text.toString().trim().toIntOrNull() ?: old.quantity,
            productionDate = etDate.text.toString().trim(),
            ean69 = etEan69.text.toString().trim(),
        )
        if (updated.serialNumber.isEmpty()) {
            Toast.makeText(this, "序列号不能为空", Toast.LENGTH_SHORT).show()
            return
        }
        findViewById<Button>(R.id.btnEditSave).isEnabled = false // 防重复点击
        // 保存：以库中最新全量按下标写回，整体在后台完成
        DbExecutor.run({
            val current = RecordStore.load(this@EditRecordActivity).toMutableList()
            if (index >= current.size) 0 else {
                current[index] = updated
                RecordStore.save(this@EditRecordActivity, current)
                1
            }
        }) { result ->
            result.onSuccess { saved ->
                if (saved == 0) {
                    Toast.makeText(this, "记录不存在", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "✅ 已保存", Toast.LENGTH_SHORT).show()
                }
                finish()
            }.onFailure {
                Toast.makeText(this, "保存失败：${it.message}", Toast.LENGTH_SHORT).show()
                findViewById<Button>(R.id.btnEditSave).isEnabled = true
            }
        }
    }
}
