package com.clipmind.android.knowledge

import com.clipmind.android.data.AiCaptureConfiguration
import com.clipmind.android.network.CaptureApi
import com.clipmind.android.network.FixedProviderClient
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import com.clipmind.android.domain.LocalSafetyFilter
import com.clipmind.android.domain.FilterResult
import kotlin.coroutines.resumeWithException

class KnowledgeFailure(val code: String) : Exception(code)

interface KnowledgeGenerator {
    suspend fun generate(input: KnowledgeRequest, config: AiCaptureConfiguration, key: String?, authorization: String?): KnowledgeResponse
}

class KnowledgeClient(private val api: CaptureApi) : KnowledgeGenerator {
    private val gson = Gson()
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .callTimeout(25, TimeUnit.SECONDS).build()

    override suspend fun generate(input: KnowledgeRequest, config: AiCaptureConfiguration, key: String?, authorization: String?): KnowledgeResponse {
        if (!KnowledgeContract.validInput(input)) throw KnowledgeFailure("INVALID_SELECTION")
        if (input.cards.any { !KnowledgeContract.safeText(it.text) || LocalSafetyFilter().evaluate(it.text, null) != FilterResult.Allowed }) {
            throw KnowledgeFailure("SENSITIVE_CONTENT")
        }
        val response = if (!config.mode.isByok) {
            val remote = api.synthesize(authorization, input)
            if (!remote.isSuccessful) throw KnowledgeFailure("HTTP_${remote.code()}")
            remote.body() ?: throw KnowledgeFailure("EMPTY_RESULT")
        } else {
            if (key.isNullOrBlank() || config.model.isBlank()) throw KnowledgeFailure("BYOK_CONFIG_MISSING")
            val endpoint = FixedProviderClient.providerEndpoint(config.mode.providerId) ?: throw KnowledgeFailure("INVALID_PROVIDER")
            val body = gson.toJson(mapOf(
                "model" to config.model, "temperature" to 0, "response_format" to mapOf("type" to "json_object"),
                "messages" to listOf(
                    mapOf("role" to "system", "content" to KnowledgeContract.SYSTEM_PROMPT),
                    mapOf("role" to "user", "content" to gson.toJson(input)),
                ),
            ))
            val request = Request.Builder().url("$endpoint/chat/completions")
                .header("Authorization", "Bearer $key").post(body.toRequestBody("application/json".toMediaType())).build()
            client.newCall(request).await().use { remote ->
                if (!remote.isSuccessful) throw KnowledgeFailure("BYOK_HTTP_${remote.code}")
                val raw = withContext(Dispatchers.IO) {
                    val source = remote.body?.source() ?: throw KnowledgeFailure("EMPTY_RESULT")
                    source.request(1_048_577)
                    if (source.buffer.size > 1_048_576) throw KnowledgeFailure("RESULT_TOO_LARGE")
                    source.readUtf8()
                }
                val result = parseProviderResult(raw)
                KnowledgeResponse(result, config.mode.providerId, config.model, KnowledgeContract.PROMPT_VERSION)
            }
        }
        if (!KnowledgeContract.validResponse(input, response)) throw KnowledgeFailure("INVALID_CITATIONS")
        return response
    }

    internal fun parseProviderResult(raw: String): KnowledgeResult = try {
        val choices = JsonParser.parseString(raw).asJsonObject.getAsJsonArray("choices")
        if (choices.size() != 1) throw KnowledgeFailure("INVALID_RESULT")
        val content = choices[0].asJsonObject.getAsJsonObject("message").get("content").asString
        gson.fromJson(content, KnowledgeResult::class.java)
    } catch (_: Exception) { throw KnowledgeFailure("INVALID_RESULT") }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(KnowledgeFailure("NETWORK_FAILED"))
        }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { response.close() }
        }
    })
}
