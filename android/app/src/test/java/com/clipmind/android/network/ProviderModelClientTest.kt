package com.clipmind.android.network

import com.clipmind.android.data.AiDefaults
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class ProviderModelClientTest {
    private val key = "fake-provider-key"

    private fun client(reply: (Request) -> Pair<Int, String>): ProviderModelClient = ProviderModelClient(
        OkHttpClient.Builder().addInterceptor { chain ->
            val (status, body) = reply(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
                .header("Location", "https://attacker.invalid/").body(body.toResponseBody()).build()
        }.build(),
    )

    @Test fun everySupportedProviderUsesItsOwnFixedEndpointAndHeaderOnlyKey() = runBlocking {
        val fixtures = mapOf(
            "openai" to """{"data":[{"id":"gpt-test"}]}""",
            "kimi" to """{"data":[{"id":"kimi-test"}]}""",
            "deepseek" to """{"data":[{"id":"deepseek-test"}]}""",
            "openrouter" to """{"data":[{"id":"anthropic/claude-test","architecture":{"output_modalities":["text"]},"supported_parameters":["response_format"]}]}""",
            "anthropic" to """{"data":[{"id":"claude-test"}],"has_more":false}""",
            "gemini" to """{"models":[{"name":"models/gemini-test","supportedGenerationMethods":["generateContent"]}]}""",
            "qwen" to """{"success":true,"output":{"total":1,"page_no":1,"models":[{"model":"qwen-test"}]}}""",
        )
        fixtures.forEach { (provider, body) ->
            var calls = 0
            val result = client { request ->
                calls++
                assertEquals("GET", request.method)
                assertEquals(AiDefaults.modelsEndpoint(provider), request.url.newBuilder().query(null).build().toString())
                assertEquals(providerKeyValue(provider, key), request.header(providerKeyHeader(provider)))
                listOf("Authorization", "x-api-key", "x-goog-api-key").filterNot { it == providerKeyHeader(provider) }
                    .forEach { assertNull(request.header(it)) }
                assertFalse(request.url.toString().contains(key))
                assertNull(request.body)
                200 to body
            }.listModels(provider, key)
            assertTrue(provider, result is ModelListResult.Success)
            assertEquals(1, (result as ModelListResult.Success).models.size)
            assertEquals(1, calls)
        }
    }

    @Test fun anthropicAndGeminiConsumeAllPagesWithoutFollowingCursorUrls() = runBlocking {
        listOf("anthropic", "gemini").forEach { provider ->
            var calls = 0
            val cursor = "https://attacker.invalid/page?x=1"
            val result = client { request ->
                calls++
                assertEquals(if (provider == "anthropic") "api.anthropic.com" else "generativelanguage.googleapis.com", request.url.host)
                assertEquals(if (calls == 1) null else cursor, request.url.queryParameter(if (provider == "anthropic") "after_id" else "pageToken"))
                200 to if (provider == "anthropic") {
                    Gson().toJson(mapOf("data" to listOf(mapOf("id" to "claude-page-$calls")), "has_more" to (calls == 1), "last_id" to cursor))
                } else {
                    Gson().toJson(mapOf("models" to listOf(mapOf("name" to "models/gemini-page-$calls", "supportedGenerationMethods" to listOf("generateContent"))),
                        "nextPageToken" to if (calls == 1) cursor else ""))
                }
            }.listModels(provider, key)
            assertEquals(2, calls)
            assertEquals(2, (result as ModelListResult.Success).models.size)
        }
    }

    @Test fun qwenUsesSingaporeAccountAndNumberedPagination() = runBlocking {
        var calls = 0
        val result = client { request ->
            calls++
            assertEquals("dashscope-intl.aliyuncs.com", request.url.host)
            assertEquals(calls.toString(), request.url.queryParameter("page_no"))
            assertEquals("qwen", request.url.queryParameter("providers"))
            assertEquals("structured-output", request.url.queryParameter("features"))
            200 to """{"output":{"total":2,"page_no":$calls,"models":[{"model":"qwen-page-$calls"}]}}"""
        }.listModels("qwen", key)
        assertEquals(ModelListResult.Success(listOf("qwen-page-1", "qwen-page-2")), result)
    }

    @Test fun filtersIncompatibleModelsAndNeverImportsLocalOrRecentModels() = runBlocking {
        val body = """{"data":[{"id":"gpt-test"},{"id":"gpt-test"},{"id":"gpt-audio-test"},{"id":"gpt-image-test"},{"id":"gpt-test-codex"},{"id":"o3-pro"},{"id":"text-embedding-test"},{"id":"gpt-$key"},{"id":"gpt invalid"}]}"""
        assertEquals(ModelListResult.Success(listOf("gpt-test")), client { 200 to body }.listModels("openai", key))
        assertEquals(ModelListResult.Success(emptyList<String>()), client { 200 to """{"data":[]}""" }.listModels("openai", key))
        val gemini = """{"models":[{"name":"models/gemini-chat","supportedGenerationMethods":["generateContent"]},{"name":"models/gemini-tts","supportedGenerationMethods":["generateContent"]},{"name":"models/gemini-live","supportedGenerationMethods":["bidiGenerateContent"]}]}"""
        assertEquals(ModelListResult.Success(listOf("gemini-chat")), client { 200 to gemini }.listModels("gemini", key))
    }

    @Test fun errorResponsesAreSanitizedAndAreNotRetriedOrRedirected() = runBlocking {
        listOf(401, 403, 404, 429, 500, 307).forEach { status ->
            var calls = 0
            val result = client { calls++; status to """{"error":{"message":"$key"}}""" }.listModels("anthropic", key)
            assertTrue(result is ModelListResult.Failure)
            assertEquals(status, (result as ModelListResult.Failure).httpStatus)
            assertFalse(result.toString().contains(key))
            assertEquals(1, calls)
        }
    }

    @Test fun partialPagesMalformedBodiesAndRepeatedCursorsNeverBecomeSuccess() = runBlocking {
        listOf("null", "[]", "not-json", "{}", """{"data":{}}""", """{"data":[{}]}""").forEach { body ->
            assertEquals(ModelListResult.Failure("MODEL_LIST_INVALID"), client { 200 to body }.listModels("openai", key))
        }
        var calls = 0
        val partial = client {
            calls++
            if (calls == 1) 200 to """{"data":[{"id":"claude-first"}],"has_more":true,"last_id":"cursor"}""" else 503 to "unavailable"
        }.listModels("anthropic", key)
        assertEquals(ModelListResult.Failure("BYOK_HTTP", 503), partial)
        calls = 0
        val repeated = client { calls++; 200 to """{"data":[{"id":"claude-first"}],"has_more":true,"last_id":"same-cursor"}""" }.listModels("anthropic", key)
        assertEquals(ModelListResult.Failure("MODEL_LIST_INVALID"), repeated)
        assertEquals(2, calls)
    }

    @Test fun pageSizeAndPageCountAreBounded() = runBlocking {
        assertEquals(ModelListResult.Failure("MODEL_LIST_LIMIT"), client { 200 to "x".repeat(2_097_153) }.listModels("openai", key))
        var calls = 0
        val result = client {
            calls++
            200 to """{"data":[{"id":"claude-$calls"}],"has_more":true,"last_id":"page-$calls"}"""
        }.listModels("anthropic", key)
        assertEquals(ModelListResult.Failure("MODEL_LIST_LIMIT"), result)
        assertEquals(20, calls)
    }

    @Test fun missingKeysUnsupportedProvidersAndSecretCursorsAreNeverSent() = runBlocking {
        val noTransport = client { error("Must not make a request") }
        listOf("ark", "glm", "unknown").forEach { assertEquals(ModelListResult.Failure("MODEL_LIST_UNSUPPORTED"), noTransport.listModels(it, key)) }
        assertEquals(ModelListResult.Failure("BYOK_KEY_MISSING"), noTransport.listModels("openai", ""))
        assertEquals(ModelListResult.Failure("BYOK_AUTH"), noTransport.listModels("openai", "key\r\nx:y"))
        var calls = 0
        val result = client { calls++; 200 to """{"data":[{"id":"claude-first"}],"has_more":true,"last_id":"$key"}""" }.listModels("anthropic", key)
        assertEquals(ModelListResult.Failure("MODEL_LIST_INVALID"), result)
        assertEquals(1, calls)
    }
}
