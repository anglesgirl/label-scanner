package com.anglesgirl.labelscanner.util

import android.graphics.Color
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan

/**
 * OCR 易混淆字符的红色标注。
 *
 * 用户要求："识别出现容易混淆的自然要红色标注提醒，可以人工修正，
 * 人才能识别出来这种差异。"
 *
 * OCR 分不清形状相同的字母与数字，最典型的是 O ↔ 0 和 I ↔ l ↔ 1。
 * 这些位一旦认错，人眼扫过去几乎看不出 —— 比如 `CS1RVO09B4` 里
 * **字母 O 和数字 0 紧挨着**，不标出来根本没法核对。
 *
 * 所以：把这些位标红加粗，让注意力集中到需要人工确认的字符上。
 * 只做"提示"，不做"自动纠正" —— 没有权威来源（如旁边的条码）可比对时，
 * 猜一个替代字符比不猜更糟（用户明确：猜错比不填更糟）。
 */
object AmbiguousChar {

    /**
     * 形状最易混的字符。
     *
     * 目前只收最常混的两组：字母 O / 小写 o / 数字 0，大写 I / 小写 l / 数字 1。
     * S↔5、B↔8、Z↔2、G↔6 也容易混，但它们在 SN 里出现频率很高，
     * 全标出来会让几乎每个 SN 都满屏红、反而失去提示作用 —— 先不纳入。
     */
    private val RISKY = setOf('O', 'o', '0', 'I', 'l', '1')

    /** 提示用的红色。 */
    val COLOR: Int = Color.parseColor("#FF3B30")

    /** 文本里是否含易混淆字符。 */
    fun has(text: String?): Boolean = !text.isNullOrEmpty() && text.any { it in RISKY }

    /**
     * 把易混淆字符标红（并加粗），其余保持原样。
     * 不含易混淆字符时原样返回，不做多余的 Spannable 分配。
     */
    fun highlight(
        text: String,
        color: Int = COLOR,
        bold: Boolean = true,
    ): CharSequence {
        if (!has(text)) return text
        val sp = SpannableString(text)
        text.forEachIndexed { i, c ->
            if (c in RISKY) {
                sp.setSpan(
                    ForegroundColorSpan(color),
                    i, i + 1,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                if (bold) {
                    sp.setSpan(
                        StyleSpan(Typeface.BOLD),
                        i, i + 1,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            }
        }
        return sp
    }

    /** 该文本涉及哪一类易混字符，用于给用户一句说明。 */
    fun hint(text: String?): String? {
        if (text.isNullOrEmpty() || !has(text)) return null
        val hasO0 = text.any { it == 'O' || it == 'o' || it == '0' }
        val hasI1 = text.any { it == 'I' || it == 'l' || it == '1' }
        return when {
            hasO0 && hasI1 -> "红色位易混（O/0、I/l/1），请核对"
            hasO0 -> "红色位易混（O/0），请核对"
            else -> "红色位易混（I/l/1），请核对"
        }
    }
}
