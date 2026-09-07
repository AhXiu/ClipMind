package com.clipmind.android.domain

enum class FilterReason {
    TOO_SHORT, PURE_URL, PHONE_NUMBER, NATIONAL_ID, VERIFICATION_CODE,
    TOKEN_OR_SECRET, PRIVATE_KEY, BLOCKED_SOURCE,
}

sealed interface FilterResult {
    data object Allowed : FilterResult
    data class Rejected(val reason: FilterReason) : FilterResult
}

class LocalSafetyFilter(
    private val minimumLength: Int = 8,
    blockedSources: Set<String> = emptySet(),
) {
    private val blocked = blockedSources.map { it.lowercase() }.toSet()
    private val pureUrl = Regex("(?i)^https?://\\S+$")
    private val phone = Regex("^(?:\\+?86[- ]?)?1[3-9]\\d{9}$")
    private val nationalId = Regex("^\\d{17}[0-9Xx]$")
    private val otp = Regex("(?i)^(?:验证码|code|otp)?[：: ]*\\d{4,8}$")
    private val token = Regex("(?i)(?:bearer\\s+[a-z0-9._~+/-]+=*|(?:access[_-]?token|api[_-]?key|secret)\\s*[:=]\\s*\\S+|(?:ghp|sk)-[a-z0-9_-]{16,}|eyJ[a-z0-9_-]+\\.eyJ[a-z0-9_-]+\\.[a-z0-9_-]+)")
    private val privateKey = Regex("-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----")

    fun evaluate(rawText: String, sourceApp: String?, minimumLengthOverride: Int? = null): FilterResult {
        val text = rawText.trim()
        val effectiveMinimum = minimumLengthOverride?.coerceAtLeast(1) ?: minimumLength
        return when {
            sourceApp?.lowercase() in blocked -> FilterResult.Rejected(FilterReason.BLOCKED_SOURCE)
            text.length < effectiveMinimum -> FilterResult.Rejected(FilterReason.TOO_SHORT)
            pureUrl.matches(text) -> FilterResult.Rejected(FilterReason.PURE_URL)
            phone.matches(text.replace(" ", "")) -> FilterResult.Rejected(FilterReason.PHONE_NUMBER)
            nationalId.matches(text) -> FilterResult.Rejected(FilterReason.NATIONAL_ID)
            otp.matches(text) -> FilterResult.Rejected(FilterReason.VERIFICATION_CODE)
            privateKey.containsMatchIn(text) -> FilterResult.Rejected(FilterReason.PRIVATE_KEY)
            token.containsMatchIn(text) -> FilterResult.Rejected(FilterReason.TOKEN_OR_SECRET)
            else -> FilterResult.Allowed
        }
    }
}
