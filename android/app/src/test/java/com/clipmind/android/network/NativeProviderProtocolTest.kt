package com.clipmind.android.network

import com.google.gson.JsonParser
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class NativeProviderProtocolTest {
    private val messages = listOf(mapOf("role" to "system", "content" to "Return JSON."), mapOf("role" to "user", "content" to "A test excerpt."))
    private val content = """{"primary_tag":"技术","interpretation":{"summary":"s","insight":"i","action":"a"},"books":[]}"""

    @Test fun claudeUsesMessagesAndAForcedJsonReturnChannelNotAnExecutableTool() {
        val request = ProviderChatProtocol.request("anthropic", "claude-test", "fake-key", messages)
        assertEquals("https://api.anthropic.com/v1/messages", request.url.toString())
        assertEquals("fake-key", request.header("x-api-key"))
        assertEquals("2023-06-01", request.header("anthropic-version"))
        assertNull(request.header("Authorization"))
        val body = JsonParser.parseString(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).asJsonObject
        assertEquals("Return JSON.", body.get("system").asString)
        assertEquals(1, body.getAsJsonArray("messages").size())
        assertEquals(4096, body.get("max_tokens").asInt)
        assertEquals("return_json", body.getAsJsonObject("tool_choice").get("name").asString)
        assertTrue(body.getAsJsonObject("tool_choice").get("disable_parallel_tool_use").asBoolean)
        assertEquals(1, body.getAsJsonArray("tools").size())
        assertFalse(body.has("response_format"))
        assertEquals(JsonParser.parseString(content), JsonParser.parseString(ProviderChatProtocol.jsonContent("anthropic", providerEnvelope("anthropic", content))))
    }

    @Test fun geminiUsesNativeJsonGenerationAndNeverPutsAKeyInTheUrl() {
        val request = ProviderChatProtocol.request("gemini", "gemini-test", "fake-key", messages)
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-test:generateContent", request.url.toString())
        assertNull(request.url.query)
        assertEquals("fake-key", request.header("x-goog-api-key"))
        assertNull(request.header("Authorization"))
        val body = JsonParser.parseString(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).asJsonObject
        assertEquals("application/json", body.getAsJsonObject("generationConfig").get("responseMimeType").asString)
        assertEquals(16384, body.getAsJsonObject("generationConfig").get("maxOutputTokens").asInt)
        assertEquals(1, body.getAsJsonArray("contents").size())
        assertEquals("Return JSON.", body.getAsJsonObject("systemInstruction").getAsJsonArray("parts")[0].asJsonObject.get("text").asString)
        assertEquals(content, ProviderChatProtocol.jsonContent("gemini", providerEnvelope("gemini", content)))
    }

    @Test fun incompleteOutputsRemainNonRetryableValidationFailures() {
        val client = FixedProviderClient()
        listOf("anthropic", "gemini", "kimi").forEach { provider ->
            val raw = providerEnvelope(provider, content)
                .replace("\"tool_use\"", "\"max_tokens\"")
                .replace("\"STOP\"", "\"MAX_TOKENS\"")
                .replace("\"stop\"", "\"length\"")
            val result = client.parseResponse(raw, provider, "test-model") as ByokAnalysisResult.Failure
            assertEquals(ByokErrorCode.BYOK_VALIDATION, result.code)
            assertTrue(requiresByokConfigurationChange(result.code.name))
        }
    }

    @Test fun unexpectedToolNamesAndMalformedNativePayloadsAreRejected() {
        val client = FixedProviderClient()
        val wrongTool = providerEnvelope("anthropic", content).replace("return_json", "execute_command")
        assertTrue(client.parseResponse(wrongTool, "anthropic", "test") is ByokAnalysisResult.Failure)
        listOf("anthropic", "gemini").forEach { provider ->
            listOf("null", "{}", "[]", "not-json").forEach { raw ->
                assertTrue(client.parseResponse(raw, provider, "test") is ByokAnalysisResult.Failure)
            }
        }
    }

    @Test fun encodedModelPathCannotChangeGeminiHostOrInjectQueryParameters() {
        val request = ProviderChatProtocol.request("gemini", "../other?key=injected#fragment", "fake-key", messages)
        assertEquals("generativelanguage.googleapis.com", request.url.host)
        assertNull(request.url.query)
        assertNull(request.url.fragment)
        assertEquals(3, request.url.pathSegments.size)
    }
}
