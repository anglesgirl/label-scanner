package com.anglesgirl.labelscanner.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 12 位物料编码的选取优先级（2026-11-25 实测回归）。
 *
 * P2206W 包装上除真料号 301061026101（12 位末尾 01）外，还有一串
 * 12 位码 936358033206（疑似零售 UPC）。条码先被扫到就抢进物料框，
 * 真料号后到时顶不掉，128 条里 3 条记错。标准料号（12 位末尾 01）
 * 出现时必须覆盖 12 位非 01 码；反过来非 01 码不能顶掉标准料号。
 */
class LabelParserMaterial12Test {

    private val realMaterial = "301061026101"
    private val impostor = "936358033206"

    @Test
    fun standard12DigitMaterialOverwritesNonStandard12DigitCode() {
        // 冒名码先到、真料号后到：真料号必须赢（这就是现场的 3 条错）。
        val parsed = LabelParser.parse(listOf(impostor, realMaterial), "")
        assertEquals(realMaterial, parsed.materialCode)
    }

    @Test
    fun standard12DigitMaterialIsKeptWhenImpostorComesLater() {
        val parsed = LabelParser.parse(listOf(realMaterial, impostor), "")
        assertEquals(realMaterial, parsed.materialCode)
    }

    @Test
    fun tenDigitMaterialOverwritesNonStandard12DigitCode() {
        // 10 位料号归一化后也是 12 位末尾 01，同样能顶掉非 01 码。
        val parsed = LabelParser.parse(listOf(impostor, "3010610261"), "")
        assertEquals(realMaterial, parsed.materialCode)
    }

    @Test
    fun loneNonStandard12DigitCodeStillFillsMaterial() {
        // 只有非 01 的 12 位码时仍填进去，不留空（不破坏单码标签）。
        val parsed = LabelParser.parse(listOf(impostor), "")
        assertEquals(impostor, parsed.materialCode)
    }

    @Test
    fun material11StillOverwritesOrderNumber() {
        // 原有行为不变：10 位订单号先被当物料，11 位末尾 01 料号后到时覆盖它。
        val parsed = LabelParser.parse(listOf("4900048225", "20822700301"), "")
        assertEquals("20822700301", parsed.materialCode)
    }

    @Test
    fun ocrImpostorLineDoesNotBeatStandardMaterial() {
        // OCR 通道同样：冒名码行先出现，真料号行后出现，真料号赢。
        val parsed = LabelParser.parse(emptyList(), "$impostor\n$realMaterial")
        assertEquals(realMaterial, parsed.materialCode)
    }
}
