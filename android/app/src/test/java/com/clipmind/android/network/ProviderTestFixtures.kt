package com.clipmind.android.network

import com.google.gson.Gson
import com.google.gson.JsonParser

internal fun providerEnvelope(provider: String, content: String): String = Gson().toJson(when (provider) {
    "anthropic" -> mapOf("stop_reason" to "tool_use", "content" to listOf(mapOf(
        "type" to "tool_use", "name" to "return_json", "input" to JsonParser.parseString(content),
    )))
    "gemini" -> mapOf("candidates" to listOf(mapOf("finishReason" to "STOP", "content" to mapOf(
        "parts" to listOf(mapOf("text" to content)),
    ))))
    else -> mapOf("choices" to listOf(mapOf("finish_reason" to "stop", "message" to mapOf("content" to content))))
})

internal fun providerKeyHeader(provider: String): String = when (provider) {
    "anthropic" -> "x-api-key"
    "gemini" -> "x-goog-api-key"
    else -> "Authorization"
}

internal fun providerKeyValue(provider: String, key: String): String =
    if (provider in setOf("anthropic", "gemini")) key else "Bearer $key"
