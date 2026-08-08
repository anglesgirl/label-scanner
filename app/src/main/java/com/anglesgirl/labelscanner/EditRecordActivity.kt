package com.anglesgirl.labelscanner

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.anglesgirl.labelscanner.data.RecordStore
import com.anglesgirl.labelscanner.model.LabelResult

/**
 * 编辑页：修改一条已保存记录的全部字段。
 * 保存后写回 RecordStore，返回列表页。
 */
class EditRecordActivity : AppCompatActivity() {

    private var index = -1

    private lateinit var etSupplier: EditText
    private lateinit var etSn: EditText
    private lateinit var etMaterial: EditText
    private lateinit var etQty: EditText
    private lateinit var etDate: EditText
    private lateinit var etEan69: EditText
    private lateinit var etModel: EditText
    private lateinit var etColor: EditText
    private lateinit var etToner: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit_record)

        index = intent.getIntExtra("index", -1)
        if (index < 0) {
            finish()
            return
        }

        etSupplier = findViewById(R.id.etSupplier)
        etSn = findViewById(R.id.etSn)
        etMaterial = findViewById(R.id.etMaterial)
        etQty = findViewById(R.id.etQty)
        etDate = findViewById(R.id.etDate)
        etEan69 = findViewById(R.id.etEan69)
        etModel = findViewById(R.id.etModel)
        etColor = findViewById(R.id.etColor)
        etToner = findViewById(R.id.etToner)

        val records = RecordStore.load(this)
        if (index >= records.size) {
            Toast.makeText(this, "记录不存在", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val r = records[index]
        findViewById<TextView>(R.id.tvEditTitle).text = "编辑记录 #${index + 1}"

        etSupplier.setText(r.supplier)
        etSn.setText(r.serialNumber)
        etMaterial.setText(r.materialCode)
        etQty.setText(r.quantity.toString())
        etDate.setText(r.productionDate)
        etEan69.setText(r.ean69)
        etModel.setText(r.model)
        etColor.setText(r.color)
        etToner.setText(r.tonerModel)

        findViewById<Button>(R.id.btnEditSave).setOnClickListener { save() }
        findViewById<Button>(R.id.btnEditCancel).setOnClickListener { finish() }
    }

    private fun save() {
        val records = RecordStore.load(this)
        if (index >= records.size) {
            finish()
            return
        }
        val old = records[index]
        val updated = old.copy(
            supplier = etSupplier.text.toString().trim().ifBlank { "NA" },
            serialNumber = etSn.text.toString().trim(),
            materialCode = etMaterial.text.toString().trim(),
            quantity = etQty.text.toString().trim().toIntOrNull() ?: old.quantity,
            productionDate = etDate.text.toString().trim(),
            ean69 = etEan69.text.toString().trim(),
            model = etModel.text.toString().trim(),
            color = etColor.text.toString().trim(),
            tonerModel = etToner.text.toString().trim(),
        )
        if (updated.serialNumber.isEmpty()) {
            Toast.makeText(this, "序列号不能为空", Toast.LENGTH_SHORT).show()
            return
        }
        records[index] = updated
        RecordStore.save(this, records)
        Toast.makeText(this, "✅ 已保存", Toast.LENGTH_SHORT).show()
        finish()
    }
}
