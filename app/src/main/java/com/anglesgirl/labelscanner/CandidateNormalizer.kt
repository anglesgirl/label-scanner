package com.anglesgirl.labelscanner

import android.app.AlertDialog
import android.content.Context
import android.widget.EditText
import android.widget.Toast

/**
 * 手动点选候选值时的「整理 + 校验 + 修改提示」工具（2026-09-15 用户需求）。
 *
 * 背景：OCR / 条码识别出的候选，常常带着空格或字段标签（如 `sn123456789 10`、
 * `2026 49 15`、`S/N 201071000501`）。系统自动解析会按规则过滤不合规的值，
 * 但**用户手动点选加入时**，原样贴进去就是垃圾数据。这里统一：
 * 1. **自动整理**：去首尾/内嵌空白、剥离误粘的字段前缀（S/N 等，仅物料）
 * 2. **合规校验**：按目标字段规则判定（日期月份 1-12、物料 10/12 位纯数字…）
 * 3. **不合规提示**：弹修改框，预填整理后的值，让用户改对再填入 ——
 *    而不是静默贴入，也不是静默拒绝。
 */
object CandidateNormalizer {

    /**
     * 整理候选值。
     *
     * @param field 目标字段语义（material/date/box/tray/model/ean69/sn/""），
     *              决定是否剥离 S/N 类前缀 —— 只有物料才剥，防误伤
     *              SN 自身的 CA/PA/TP 前缀。
     */
    fun normalize(raw: String, field: String): String {
        var v = raw.trim().replace(Regex("\\s+"), "")
        if (field == "material") {
            // OCR 常把标签上的 "S/N:" / "SN" / "No." 与数字粘在一起；
            // 物料本身是纯数字，剥掉前缀不会误伤（CA/PA/TP 前缀是 SN 的事）
            v = v.replace(
                Regex("^(s/n|sn|no\\.?|n\\.?o\\.?)[:：\\-]?", RegexOption.IGNORE_CASE),
                "",
            )
        }
        return v
    }

    /**
     * 校验整理后的值是否合规；返回 null = 合规，否则返回给用户看的错误原因。
     * 规则与解析层一致（10/12 位物料、日期月 1-12 等），但**比解析层宽松**：
     * 解析层要"精确命中"，这里只拦"明显错误"，其余放行交给用户判断。
     */
    fun validate(value: String, field: String): String? = when (field) {
        "material" -> when {
            value.matches(Regex("^\\d{10}$")) || value.matches(Regex("^\\d{12}$")) -> null
            value.matches(Regex("^69\\d{11}$")) ->
                "这是 69 商品码（13 位 69 开头），不是物料编码，请确认"
            else -> "物料编码应为 10 位或 12 位纯数字"
        }
        "date" -> dateError(value)
        "box" -> when {
            value.length >= 6 && value.any { it.isLetter() } && value.any { it.isDigit() } -> null
            value.length in 4..40 && value.any { it.isDigit() } -> null   // 宽松兜底，不挡数据
            else -> "箱号应含数字（字母数字混合更常见）"
        }
        "tray" -> when {
            value.matches(Regex("^(TP|CA|PA)\\w+$", RegexOption.IGNORE_CASE)) -> null
            value.length in 4..40 && value.any { it.isDigit() } -> null
            else -> "托盘号通常以 TP/CA/PA 开头"
        }
        "model" -> when {
            value.length in 2..30 && value.any { it.isLetter() } -> null
            else -> "型号应为字母数字串"
        }
        "ean69" -> when {
            value.matches(Regex("^\\d{13}$")) -> null
            else -> "69 商品码应为 13 位数字"
        }
        "sn" -> when {
            value.length >= 4 && value.any { it.isDigit() } -> null
            else -> "序列号至少 4 位且含数字"
        }
        else -> null
    }

    /**
     * 整理 → 校验 →（不合规时）弹修改框。合规或用户修改确认后回调 onOk。
     * 这是"手动加入"的统一入口：两个入库页的候选点选都走这里。
     */
    fun applyWithCheck(context: Context, raw: String, field: String, onOk: (String) -> Unit) {
        val normalized = normalize(raw, field)
        val err = validate(normalized, field)
        if (err == null) {
            onOk(normalized)
            return
        }
        val input = EditText(context).apply {
            setText(normalized)
            setSelection(normalized.length)
        }
        AlertDialog.Builder(context)
            .setTitle("⚠️ 内容可能有问题")
            .setMessage("自动整理为：$normalized\n\n$err\n请修改后再确认：")
            .setView(input)
            .setPositiveButton("使用此值") { _, _ ->
                val fixed = input.text.toString().trim()
                if (fixed.isEmpty()) {
                    Toast.makeText(context, "内容为空，未填入", Toast.LENGTH_SHORT).show()
                } else {
                    onOk(fixed)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun dateError(v: String): String? {
        // 支持 2026-09-15 / 2026/9/15 / 2026.9.15 / 2026年9月15日 / 20260915
        val m = Regex("^(\\d{4})[-/.\\u5e74](\\d{1,2})[-/.\\u6708](\\d{1,2})\\u65e5?$").find(v)
        if (m != null) {
            return datePartsError(
                m.groupValues[1].toInt(),
                m.groupValues[2].toInt(),
                m.groupValues[3].toInt(),
            )
        }
        if (v.matches(Regex("^\\d{8}$"))) {
            return datePartsError(
                v.substring(0, 4).toInt(),
                v.substring(4, 6).toInt(),
                v.substring(6, 8).toInt(),
            )
        }
        return "日期格式应为 2026-09-15（年-月-日）"
    }

    private fun datePartsError(year: Int, month: Int, day: Int): String? {
        if (month !in 1..12) return "月份 $month 不对（应为 1-12）"
        val days = when (month) {
            2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }
        if (day !in 1..days) return "日期 $day 不对（$month 月最多 $days 天）"
        return null
    }
}
