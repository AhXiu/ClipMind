package com.clipmind.android.data

import org.junit.Assert.*
import org.junit.Test

class AiModelCatalogTest {
    @Test fun ordersOnlyLiveModelsWithoutReintroducingRetiredOrOtherProviderModels() {
        val live = listOf("kimi-new", "kimi-current", "kimi-recent", "kimi-new")
        assertEquals(listOf("kimi-current", "kimi-recent", "kimi-new"),
            AiModelCatalog.ordered(live, "kimi-current", listOf("kimi-retired", "kimi-recent", "gpt-other-provider")))
        assertTrue(AiModelCatalog.ordered(emptyList(), "kimi-current", listOf("kimi-recent")).isEmpty())
    }

    @Test fun searchesTheLiveResponseIgnoringCaseAndSurroundingSpaces() {
        assertEquals(listOf("kimi-k3"), AiModelCatalog.search(listOf("kimi-k3", "kimi-k2.6"), " KIMI-K3 "))
    }

    @Test fun newProvidersHaveNoGuessedDefaultModel() {
        listOf("anthropic", "gemini", "deepseek", "qwen").forEach {
            assertEquals("", AiDefaults.defaultModel(it))
            assertNotNull(AiDefaults.endpoint(it))
            assertNotNull(AiDefaults.modelsEndpoint(it))
        }
        assertNull(AiDefaults.modelsEndpoint("ark"))
        assertNull(AiDefaults.modelsEndpoint("glm"))
        assertNull(AiDefaults.modelsEndpoint("unknown"))
    }
}
