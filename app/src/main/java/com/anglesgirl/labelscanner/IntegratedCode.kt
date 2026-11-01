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

    /** Expand selected codes once and exclude serials already saved in this list session. */
    fun serialNumbersToSave(values: List<String>, alreadySaved: Set<String>): List<String> =
        values.flatMap(::serialNumbers)
            .distinct()
            .filterNot(alreadySaved::contains)
}
