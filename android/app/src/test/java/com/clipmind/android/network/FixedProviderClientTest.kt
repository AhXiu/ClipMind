package com.clipmind.android.network

import com.clipmind.android.data.AiDefaults
import com.clipmind.android.network.dto.ClientAnalysis
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FixedProviderClientTest {
    private val client = FixedProviderClient()

    @Test fun providerUrlsAreFixedAndUnknownProviderIsRejected() {
        assertEquals("${AiDefaults.ARK_BASE_URL}", FixedProviderClient.providerEndpoint("ark"))
        assertEquals("${AiDefaults.OPENROUTER_BASE_URL}", FixedProviderClient.providerEndpoint("openrouter"))
        assertEquals(null, FixedProviderClient.providerEndpoint("https://attacker.invalid"))
    }

    @Test fun validResponseMapsSharedClientAnalysisContract() {
        val content = """{"primary_tag":"技术","interpretation":{"summary":"s","insight":"i","action":"a"},"books":[{"title":"t","author":"u"}]}"""
        val envelope = Gson().toJson(mapOf("choices" to listOf(mapOf("message" to mapOf("content" to content)))))
        val result = client.parseResponse(envelope, "ark", "model-1")
        assertTrue(result is ByokAnalysisResult.Success)
        val analysis = (result as ByokAnalysisResult.Success).analysis
        assertEquals(ClientAnalysis("ark", "model-1", "技术", analysis.interpretation, analysis.books), analysis)
    }

    @Test fun malformedAndInvalidResponsesUseFixedCategories() {
        assertEquals(ByokErrorCode.BYOK_JSON, (client.parseResponse("not-json", "ark", "m") as ByokAnalysisResult.Failure).code)
        val invalidContent = """{"primary_tag":"bad","interpretation":{"summary":"s","insight":"i","action":"a"},"books":[]}"""
        val invalid = Gson().toJson(mapOf("choices" to listOf(mapOf("message" to mapOf("content" to invalidContent)))))
        assertEquals(ByokErrorCode.BYOK_VALIDATION, (client.parseResponse(invalid, "ark", "m") as ByokAnalysisResult.Failure).code)
    }

    @Test fun exactContractBoundariesAndEmptyAuthorAreAccepted() {
        val books = List(10) { mapOf("title" to "题".repeat(300), "author" to if (it == 0) "" else "作".repeat(200)) }
        val result = parseAnalysis(
            model = "模".repeat(200),
            summary = "摘".repeat(2000),
            insight = "洞".repeat(4000),
            action = "行".repeat(2000),
            books = books,
        )
        assertTrue(result is ByokAnalysisResult.Success)
        assertEquals("", (result as ByokAnalysisResult.Success).analysis.books[0].author)
    }

    @Test fun valuesPastEachContractBoundaryUseFixedValidationCategory() {
        val validBooks = List(10) { mapOf("title" to "t", "author" to "a") }
        val results = listOf(
            parseAnalysis(model = "m".repeat(201), books = validBooks),
            parseAnalysis(summary = "s".repeat(2001), books = validBooks),
            parseAnalysis(insight = "i".repeat(4001), books = validBooks),
            parseAnalysis(action = "a".repeat(2001), books = validBooks),
            parseAnalysis(books = List(11) { mapOf("title" to "t", "author" to "") }),
            parseAnalysis(books = listOf(mapOf("title" to "t".repeat(301), "author" to ""))),
            parseAnalysis(books = listOf(mapOf("title" to "t", "author" to "a".repeat(201)))),
        )
        results.forEach { result ->
            assertEquals(ByokErrorCode.BYOK_VALIDATION, (result as ByokAnalysisResult.Failure).code)
        }
    }

    private fun parseAnalysis(
        model: String = "model",
        summary: String = "summary",
        insight: String = "insight",
        action: String = "action",
        books: List<Map<String, String>> = emptyList(),
    ): ByokAnalysisResult {
        val content = Gson().toJson(
            mapOf(
                "primary_tag" to "技术",
                "interpretation" to mapOf("summary" to summary, "insight" to insight, "action" to action),
                "books" to books,
            ),
        )
        val envelope = Gson().toJson(mapOf("choices" to listOf(mapOf("message" to mapOf("content" to content)))))
        return client.parseResponse(envelope, "ark", model)
    }
}
