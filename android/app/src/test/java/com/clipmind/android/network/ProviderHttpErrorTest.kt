package com.clipmind.android.network

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class ProviderHttpErrorTest {
    @Test fun generic404DoesNotClaimTheModelWasRetired() {
        val result = classify(404, "<html>not found</html>")
        assertEquals(ByokErrorCode.BYOK_NOT_FOUND, result.code)
        assertEquals(404, result.httpStatus)
    }

    @Test fun modelErrorsCanArriveAs400Or404() {
        listOf(400, 404).forEach { status ->
            assertEquals(ByokErrorCode.BYOK_MODEL_UNAVAILABLE, classify(status, """{"error":{"code":"model_not_found"}}""").code)
        }
    }

    @Test fun permissionAndQuotaAreNotMisreportedAsInvalidKeyOrRateLimit() {
        assertEquals(ByokErrorCode.BYOK_PERMISSION, classify(403, "").code)
        assertEquals(ByokErrorCode.BYOK_PERMISSION, classify(404, """{"error":{"type":"permission_denied"}}""").code)
        assertEquals(ByokErrorCode.BYOK_QUOTA, classify(429, """{"error":{"type":"insufficient_user_quota"}}""").code)
        assertEquals(ByokErrorCode.BYOK_AUTH, classify(401, """{"error":{"code":"insufficient_quota"}}""").code)
        assertEquals(ByokErrorCode.BYOK_HTTP, classify(503, """{"error":{"code":"model_not_found"}}""").code)
    }

    @Test fun unknownCodesAndProviderMessagesNeverEscapeIntoDiagnostics() {
        val secret = "do-not-expose-this-credential"
        val result = classify(404, """{"error":{"code":"$secret","message":"$secret: insufficient_quota"}}""")
        assertEquals(ByokErrorCode.BYOK_NOT_FOUND, result.code)
        assertFalse(result.toString().contains(secret))
    }

    @Test fun malformedAndOversizedBodiesPreserveHttpDiagnosis() {
        listOf("null", "[]", "not-json", """{"error":{"code":{}}}""", "x".repeat(20000)).forEach { body ->
            assertEquals(ByokAnalysisResult.Failure(ByokErrorCode.BYOK_NOT_FOUND, 404), classify(404, body))
        }
    }

    @Test fun configurationFailuresDoNotBecomeBackgroundRetryLoops() {
        listOf("BYOK_AUTH", "BYOK_PERMISSION", "BYOK_NOT_FOUND", "BYOK_MODEL_UNAVAILABLE", "BYOK_QUOTA").forEach {
            assertTrue(requiresByokConfigurationChange(it))
        }
        listOf("BYOK_NETWORK", "BYOK_RATE_LIMIT", "BYOK_HTTP", "BYOK_JSON", "BYOK_ENCRYPTION").forEach {
            assertFalse(requiresByokConfigurationChange(it))
        }
    }

    private fun classify(status: Int, body: String): ByokAnalysisResult.Failure = Response.Builder()
        .request(Request.Builder().url("https://example.invalid/chat/completions").build())
        .protocol(Protocol.HTTP_1_1).code(status).message("test").body(body.toResponseBody()).build()
        .use(::providerHttpError)
}
