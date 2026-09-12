package com.clipmind.android.knowledge

import com.clipmind.android.data.AiCaptureConfiguration
import com.clipmind.android.network.CaptureApi
import com.clipmind.android.network.ProviderChatProtocol
import com.clipmind.android.network.awaitProviderResponse
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import com.clipmind.android.domain.LocalSafetyFilter
import com.clipmind.android.domain.FilterResult

class KnowledgeFailure(val code: String) : Exception(code)

interface KnowledgeGenerator {
    suspend fun generate(input: KnowledgeRequest, config: AiCaptureConfiguration, key: String?, authorization: String?): KnowledgeResponse
}

class KnowledgeClient(private val api: CaptureApi, httpClient: OkHttpClient = OkHttpClient()) : KnowledgeGenerator {
    private val gson = Gson()
    private val client = httpClient.newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).callTimeout(90, TimeUnit.SECONDS).build()

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
            val request = try { ProviderChatProtocol.request(config.mode.providerId, config.model, key,
                listOf(
                    mapOf("role" to "system", "content" to KnowledgeContract.SYSTEM_PROMPT),
                    mapOf("role" to "user", "content" to gson.toJson(input)),
                ),
            ) } catch (_: IllegalArgumentException) { throw KnowledgeFailure("BYOK_CONFIG_MISSING") }
            client.newCall(request).awaitProviderResponse().use { remote ->
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
        val finish = choices[0].asJsonObject.get("finish_reason")
        if (finish != null && !finish.isJsonNull && finish.asString != "stop") throw KnowledgeFailure("INVALID_RESULT")
        val content = choices[0].asJsonObject.getAsJsonObject("message").get("content").asString
        gson.fromJson(content, KnowledgeResult::class.java)
    } catch (_: Exception) { throw KnowledgeFailure("INVALID_RESULT") }
}
