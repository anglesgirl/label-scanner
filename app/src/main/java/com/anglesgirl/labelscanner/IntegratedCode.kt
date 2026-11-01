package com.anglesgirl.labelscanner

/** Shared definition for an integrated-code payload: multiple serial numbers separated by punctuation. */
internal object IntegratedCode {
    private val separator = Regex("[,;，；]")

    fun isPayload(value: String): Boolean = separator.containsMatchIn(value)

    /** Expand a multi-serial payload into individual, trimmed serial numbers. */
    fun serialNumbers(value: String): List<String> = separator.split(value)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
}
