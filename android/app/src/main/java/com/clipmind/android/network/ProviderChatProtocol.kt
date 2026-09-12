package com.clipmind.android.network

import com.clipmind.android.data.AiDefaults
import com.clipmind.android.security.ProviderApiKeyStore
import com.google.gson.Gson
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resumeWithException

/** Shared by single-card analysis and knowledge synthesis so provider constraints cannot diverge. */
object ProviderChatProtocol {
    fun request(provider: String, model: String, apiKey: String, messages: List<Map<String, String>>): Request {
        val endpoint = requireNotNull(AiDefaults.endpoint(provider))
        require(AiDefaults.validModel(model))
        require(ProviderApiKeyStore.validApiKey(apiKey))
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
            "openrouter" -> {
                body["max_tokens"] = 16384
                body["provider"] = mapOf("require_parameters" to true)
            }
        }
        return Request.Builder().url("$endpoint/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(Gson().toJson(body).toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
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
