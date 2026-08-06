package com.anglesgirl.labelscanner.model

import java.util.regex.Pattern

/**
 * 标签值 → 字段 的智能解析器。
 *
 * 不依赖标签排版（条码类型千差万别），纯按【值特征】判断：
 *  - 8 位纯数字 → 生产日期候选（yyyymmdd）
 *  - 13 位 69 开头 → 商品码（EAN13，留作反查物料编码）
 *  - 10 位纯数字 → 物料编码候选（模板规则：10 位补 01）
 *  - 12 位纯数字 → 物料编码候选（模板规则：12 位原样）
 *  - 字母+数字混合 → SN 候选
 */
object LabelParser {

    private val DATE8 = Pattern.compile("^\\d{8}$")
    private val EAN13 = Pattern.compile("^69\\d{11}$")
    private val MAT10 = Pattern.compile("^\\d{10}$")
    private val MAT12 = Pattern.compile("^\\d{12}$")
    private val SN_MIX = Pattern.compile("^[A-Za-z0-9]{6,30}$")

    /** 解析单个值，返回 (字段名, 规范化值)，无法判断返回 null */
    fun classify(value: String): Pair<String, String>? {
        val v = value.trim()
        if (v.isEmpty()) return null

        // 日期：8 位纯数字且像日期（MM 01-12, DD 01-31）
        if (DATE8.matcher(v).matches()) {
            val m = v.substring(4, 6).toInt()
            val d = v.substring(6, 8).toInt()
            if (m in 1..12 && d in 1..31) return "date" to v
        }

        // EAN13 商品码（69 开头）
        if (EAN13.matcher(v).matches()) return "ean" to v

        // 物料编码：10 位（补 01）或 12 位
        if (MAT10.matcher(v).matches()) return "material10" to v
        if (MAT12.matcher(v).matches()) return "material12" to v

        // SN：字母数字混合
        if (SN_MIX.matcher(v).matches() && v.any { it.isLetter() }) return "sn" to v

        return null
    }

    /**
     * 把一组条码值 + OCR 文本合并解析成 LabelResult。
     * 多值冲突时按优先级：日期 > 物料 > SN > 商品码
     */
    fun parse(barcodes: List<String>, ocrText: String): LabelResult {
        val result = LabelResult(barcodes = barcodes, ocrText = ocrText)

        // 1. 条码优先（准确）
        for (code in barcodes) {
            when (classify(code)?.first) {
                "date" -> if (result.productionDate.isEmpty()) result.productionDate = code
                "material10", "material12" -> if (result.materialCode.isEmpty()) {
                    result.materialCode = normalizeMaterial(code)
                }
                "sn" -> if (result.serialNumber.isEmpty()) result.serialNumber = code
                "ean" -> { /* 商品码：暂存不填字段，供反查 */ }
            }
        }

        // 2. OCR 兜底：从文本行里提取（条码缺失时）
        if (result.materialCode.isEmpty() || result.productionDate.isEmpty() || result.serialNumber.isEmpty()) {
            val lines = ocrText.lines().map { it.trim() }.filter { it.isNotEmpty() }
            for (line in lines) {
                when (classify(line)?.first) {
                    "date" -> if (result.productionDate.isEmpty()) result.productionDate = line
                    "material10", "material12" -> if (result.materialCode.isEmpty()) {
                        result.materialCode = normalizeMaterial(line)
                    }
                    "sn" -> if (result.serialNumber.isEmpty()) result.serialNumber = line
                }
            }
        }

        return result
    }

    /** 物料编码规范化：10 位补 01；12 位原样；69 开头保留（App 自有数据） */
    fun normalizeMaterial(code: String): String {
        val c = code.trim()
        return when {
            c.length == 10 -> c + "01"
            else -> c
        }
    }
}
