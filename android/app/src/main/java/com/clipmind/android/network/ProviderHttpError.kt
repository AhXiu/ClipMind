package com.clipmind.android.network

import com.google.gson.JsonParser
import okhttp3.Response
import java.util.Locale

/** Only known error codes are retained. Provider messages may contain credentials or user text. */
internal fun providerHttpError(response: Response): ByokAnalysisResult.Failure {
    val codes = runCatching {
        val raw = response.peekBody(8193).string()
        if (raw.length > 8192) return@runCatching emptySet<String>()
        val error = JsonParser.parseString(raw).asJsonObject.getAsJsonObject("error")
        listOf("code", "type", "status").mapNotNull { field ->
            error?.get(field)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString?.takeIf { it.length <= 80 }?.lowercase(Locale.ROOT)
        }.toSet()
    }.getOrDefault(emptySet())
    val code = when {
        response.code == 401 -> ByokErrorCode.BYOK_AUTH
        response.code >= 500 -> ByokErrorCode.BYOK_HTTP
        response.code in setOf(400, 402, 403, 404, 429) && codes.any { it in setOf("insufficient_quota", "insufficient_user_quota", "insufficient_balance") } -> ByokErrorCode.BYOK_QUOTA
        response.code in setOf(400, 403, 404) && codes.any { it in setOf("permission_denied", "access_denied") } -> ByokErrorCode.BYOK_PERMISSION
        response.code in setOf(400, 404) && codes.any { it in setOf("model_not_found", "model_not_available", "model_not_exist") } -> ByokErrorCode.BYOK_MODEL_UNAVAILABLE
        response.code == 403 -> ByokErrorCode.BYOK_PERMISSION
        response.code == 402 -> ByokErrorCode.BYOK_QUOTA
        response.code == 404 -> ByokErrorCode.BYOK_NOT_FOUND
        response.code == 429 -> ByokErrorCode.BYOK_RATE_LIMIT
        response.code in setOf(400, 422) -> ByokErrorCode.BYOK_VALIDATION
        else -> ByokErrorCode.BYOK_HTTP
    }
    return ByokAnalysisResult.Failure(code, response.code)
}

internal fun requiresByokConfigurationChange(code: String): Boolean = code in setOf(
    "BYOK_KEY_MISSING", "BYOK_MODEL_MISSING", "BYOK_VALIDATION", "BYOK_AUTH",
    "BYOK_MODEL_UNAVAILABLE", "BYOK_QUOTA", "BYOK_PERMISSION", "BYOK_NOT_FOUND",
)
