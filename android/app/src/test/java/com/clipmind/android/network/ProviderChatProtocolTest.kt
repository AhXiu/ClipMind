package com.clipmind.android.network

import com.clipmind.android.data.AiDefaults
import com.google.gson.JsonParser
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class ProviderChatProtocolTest {
    private val messages = listOf(mapOf("role" to "user", "content" to "Return JSON."))
    private val content = """{"primary_tag":"技术","interpretation":{"summary":"s","insight":"i","action":"a"},"books":[]}"""
    private fun envelope(finish: String = "stop") = Gson().toJson(mapOf("choices" to listOf(mapOf(
        "message" to mapOf("content" to content, "reasoning_content" to "not the final result"), "finish_reason" to finish,
    ))))

    @Test fun protocolUsesOnlyOfficialEndpointsAndKeepsKeysOutOfJson() {
        AiDefaults.providerIds.forEach { provider ->
            val request = ProviderChatProtocol.request(provider, "test-model", "provider-test-key", messages)
            assertEquals(ProviderChatProtocol.generationEndpoint(provider, "test-model"), request.url.toString())
            assertEquals("https", request.url.scheme)
            assertEquals(providerKeyValue(provider, "provider-test-key"), request.header(providerKeyHeader(provider)))
            val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            assertFalse(body.contains("provider-test-key"))
            val json = JsonParser.parseString(body).asJsonObject
            if (provider !in setOf("anthropic", "gemini")) assertEquals("json_object", json.getAsJsonObject("response_format").get("type").asString)
            if (provider != "ark") assertFalse(json.has("temperature"))
        }
        listOf("unknown", "https://attacker.invalid").forEach { provider ->
            assertThrows(IllegalArgumentException::class.java) { ProviderChatProtocol.request(provider, "m", "key", messages) }
        }
        assertThrows(IllegalArgumentException::class.java) { ProviderChatProtocol.request("kimi", "m", "key\r\nx:y", messages) }
    }

    @Test fun kimiK3AndGptUseBoundedReasoningCompatibleParameters() {
        val kimi = ProviderChatProtocol.request("kimi", "kimi-k3", "test-key", messages)
        val json = JsonParser.parseString(Buffer().also { kimi.body!!.writeTo(it) }.readUtf8()).asJsonObject
        assertEquals("low", json.get("reasoning_effort").asString)
        assertEquals(16384, json.get("max_completion_tokens").asInt)
        assertFalse(json.has("max_tokens"))
        assertFalse(json.has("temperature"))
        val gpt = ProviderChatProtocol.request("openai", "gpt-5-mini", "test-key", messages)
        val gptJson = JsonParser.parseString(Buffer().also { gpt.body!!.writeTo(it) }.readUtf8()).asJsonObject
        assertEquals(16384, gptJson.get("max_completion_tokens").asInt)
        assertFalse(gptJson.has("temperature"))
    }

    @Test fun eachProviderAnalyzesUsingItsOwnIdentityWithoutNetwork() = runBlocking {
        AiDefaults.providerIds.forEach { provider ->
            var calls = 0
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                calls++
                assertEquals(providerKeyValue(provider, "$provider-test-key"), chain.request().header(providerKeyHeader(provider)))
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(providerEnvelope(provider, content).toResponseBody()).build()
            }.build()
            val result = FixedProviderClient(http).analyze(provider, "test-model", "$provider-test-key", "test excerpt")
            assertTrue(result is ByokAnalysisResult.Success)
            assertEquals(provider, (result as ByokAnalysisResult.Success).analysis.provider)
            assertEquals(1, calls)
        }
    }

    @Test fun errorCategoriesNeverExposeProviderErrorBodiesOrRetry() = runBlocking {
        mapOf(401 to ByokErrorCode.BYOK_AUTH, 403 to ByokErrorCode.BYOK_PERMISSION,
            402 to ByokErrorCode.BYOK_QUOTA, 404 to ByokErrorCode.BYOK_NOT_FOUND,
            429 to ByokErrorCode.BYOK_RATE_LIMIT, 400 to ByokErrorCode.BYOK_VALIDATION,
            500 to ByokErrorCode.BYOK_HTTP, 307 to ByokErrorCode.BYOK_HTTP).forEach { (status, code) ->
            var calls = 0
            val http = OkHttpClient.Builder().followRedirects(true).addInterceptor { chain ->
                calls++
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("error")
                    .header("Location", "https://attacker.invalid").body("secret-provider-error".toResponseBody()).build()
            }.build()
            val result = FixedProviderClient(http).analyze("kimi", "kimi-k3", "test-key", "excerpt")
            assertEquals(ByokAnalysisResult.Failure(code, status), result)
            assertEquals(1, calls)
            assertFalse(result.toString().contains("secret-provider-error"))
        }
    }

    @Test fun invalidConfigurationNeverMakesARequestAndTruncatedOutputIsRejected() = runBlocking {
        val http = OkHttpClient.Builder().addInterceptor { error("Must not reach transport") }.build()
        val client = FixedProviderClient(http)
        assertEquals(ByokAnalysisResult.Failure(ByokErrorCode.BYOK_VALIDATION), client.analyze("unknown", "m", "key", "text"))
        assertEquals(ByokAnalysisResult.Failure(ByokErrorCode.BYOK_KEY_MISSING), client.analyze("kimi", "m", "", "text"))
        assertEquals(ByokAnalysisResult.Failure(ByokErrorCode.BYOK_MODEL_MISSING), client.analyze("kimi", "", "key", "text"))
        assertTrue(client.parseResponse(envelope("length"), "kimi", "kimi-k3") is ByokAnalysisResult.Failure)
        val nullContent = """{"choices":[{"message":{"content":"null"}}]}"""
        assertTrue(client.parseResponse(nullContent, "kimi", "kimi-k3") is ByokAnalysisResult.Failure)
    }
}
