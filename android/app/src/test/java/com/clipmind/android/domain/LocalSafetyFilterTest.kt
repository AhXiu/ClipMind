package com.clipmind.android.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSafetyFilterTest {
    private val filter = LocalSafetyFilter(minimumLength = 8, blockedSources = setOf("secret.app"))

    @Test fun allowsNormalText() = assertEquals(FilterResult.Allowed, filter.evaluate("一段足够长的普通剪贴文本", "notes.app"))
    @Test fun rejectsShortText() = assertReason(FilterReason.TOO_SHORT, "short")
    @Test fun rejectsPureUrl() = assertReason(FilterReason.PURE_URL, "https://example.com/path")
    @Test fun rejectsPhone() = assertReason(FilterReason.PHONE_NUMBER, "138" + "00138000")
    @Test fun rejectsNationalId() = assertReason(FilterReason.NATIONAL_ID, "11010519491231002X")
    @Test fun rejectsOtp() = assertReason(FilterReason.VERIFICATION_CODE, "验证码: 123456")
    @Test fun rejectsToken() = assertReason(FilterReason.TOKEN_OR_SECRET, "Authorization: Bearer abcdefghijklmnopqrstuvwxyz")
    @Test fun rejectsPrivateKey() = assertReason(FilterReason.PRIVATE_KEY, "-----BEGIN PRIVATE KEY-----\nabc")
    @Test fun rejectsBlockedSource() = assertReason(FilterReason.BLOCKED_SOURCE, "otherwise safe text", "secret.app")

    private fun assertReason(reason: FilterReason, text: String, source: String? = null) {
        val result = filter.evaluate(text, source)
        assertTrue(result is FilterResult.Rejected)
        assertEquals(reason, (result as FilterResult.Rejected).reason)
    }
}
