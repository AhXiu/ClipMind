package com.clipmind.android.network

import com.clipmind.android.data.AiDefaults
import com.clipmind.android.security.ProviderApiKeyStore
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resumeWithException

internal class IncompleteProviderOutput : RuntimeException("INCOMPLETE_OUTPUT")

/** Shared by single-card analysis and knowledge synthesis so provider constraints cannot diverge. */
object ProviderChatProtocol {
    fun generationEndpoint(provider: String, model: String): String {
        val endpoint = requireNotNull(AiDefaults.endpoint(provider))
        return when (provider) {
            "anthropic" -> "$endpoint/messages"
            "gemini" -> endpoint.toHttpUrl().newBuilder().addPathSegment("models")
                .addPathSegment("$model:generateContent").build().toString()
            else -> "$endpoint/chat/completions"
        }
    }

    internal fun authorize(builder: Request.Builder, provider: String, apiKey: String): Request.Builder {
        require(provider in AiDefaults.providerIds && ProviderApiKeyStore.validApiKey(apiKey))
        return when (provider) {
            "anthropic" -> builder.header("x-api-key", apiKey).header("anthropic-version", "2023-06-01")
            "gemini" -> builder.header("x-goog-api-key", apiKey)
            else -> builder.header("Authorization", "Bearer $apiKey")
        }
    }

    fun request(provider: String, model: String, apiKey: String, messages: List<Map<String, String>>): Request {
        require(AiDefaults.validModel(model))
        require(ProviderApiKeyStore.validApiKey(apiKey))
        val endpoint = generationEndpoint(provider, model)
        if (provider == "anthropic" || provider == "gemini") {
            val system = messages.filter { it["role"] == "system" }.joinToString("\n") { it["content"].orEmpty() }
            val turns = messages.filter { it["role"] != "system" }
            val body = if (provider == "anthropic") mapOf(
                "model" to model, "max_tokens" to 4096, "system" to system, "messages" to turns,
                // This is a JSON return channel only. No model-requested tools are executed.
                "tools" to listOf(mapOf("name" to "return_json", "description" to "Return the JSON object requested by the user.",
                    "input_schema" to mapOf("type" to "object", "properties" to emptyMap<String, Any>(), "additionalProperties" to true))),
                "tool_choice" to mapOf("type" to "tool", "name" to "return_json", "disable_parallel_tool_use" to true),
            ) else mapOf(
                "systemInstruction" to mapOf("parts" to listOf(mapOf("text" to system))),
                "contents" to turns.map { mapOf("role" to if (it["role"] == "assistant") "model" else "user",
                    "parts" to listOf(mapOf("text" to it["content"].orEmpty()))) },
                "generationConfig" to mapOf("responseMimeType" to "application/json", "maxOutputTokens" to 16384),
            )
            return authorize(Request.Builder().url(endpoint), provider, apiKey)
                .post(Gson().toJson(body).toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        }
        val body = mutableMapOf<String, Any>(
            "model" to model,
            "messages" to messages,
            "response_format" to mapOf("type" to "json_object"),
        )
        when (provider) {
            "ark" -> body["temperature"] = 0
            "kimi" -> {
                if (model == "kimi-k3") {
                    body["reasoning_effort"] = "low"
                    body["max_completion_tokens"] = 16384
                } else body["max_tokens"] = 16384
            }
            "openai" -> body["max_completion_tokens"] = 16384
            "glm" -> body["max_tokens"] = 16384
            "deepseek" -> {
                body["max_tokens"] = 4096
                body["thinking"] = mapOf("type" to "disabled")
            }
            "qwen" -> {
                body["max_tokens"] = 8192
                body["enable_thinking"] = false
            }
            "openrouter" -> {
                body["max_tokens"] = 16384
                body["provider"] = mapOf("require_parameters" to true)
            }
        }
        return authorize(Request.Builder().url(endpoint), provider, apiKey)
            .post(Gson().toJson(body).toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
    }

    fun jsonContent(provider: String, raw: String): String {
        val root = JsonParser.parseString(raw).asJsonObject
        return when (provider) {
            "anthropic" -> {
                if (root.get("stop_reason")?.asString != "tool_use") throw IncompleteProviderOutput()
                val tools = root.getAsJsonArray("content").filter { it.asJsonObject.get("type")?.asString == "tool_use" }
                check(tools.size == 1) { "INVALID_OUTPUT" }
                val tool = tools.single().asJsonObject
                check(tool.get("name")?.asString == "return_json") { "INVALID_OUTPUT" }
                tool.getAsJsonObject("input").toString()
            }
            "gemini" -> {
                val candidates = root.getAsJsonArray("candidates")
                check(candidates.size() == 1) { "INVALID_OUTPUT" }
                val candidate = candidates[0].asJsonObject
                if (candidate.get("finishReason")?.asString != "STOP") throw IncompleteProviderOutput()
                candidate.getAsJsonObject("content").getAsJsonArray("parts")
                    .filter { it.asJsonObject.get("thought")?.asBoolean != true }
                    .joinToString("") { it.asJsonObject.get("text")?.asString.orEmpty() }
                    .also { check(it.isNotBlank()) { "INVALID_OUTPUT" } }
            }
            else -> {
                val choices = root.getAsJsonArray("choices")
                check(choices.size() == 1) { "INVALID_OUTPUT" }
                val choice = choices[0].asJsonObject
                val finish = choice.get("finish_reason")
                if (finish != null && !finish.isJsonNull && finish.asString != "stop") throw IncompleteProviderOutput()
                choice.getAsJsonObject("message").get("content").asString
            }
        }
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal suspend fun Call.awaitProviderResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { response.close() }
        }
    })
}
