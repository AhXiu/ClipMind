package com.clipmind.android.data

import org.junit.Assert.*
import org.junit.Test

class AiModelCatalogTest {
    @Test fun compactPickerPrioritizesCurrentAndRecentWithoutRemovingFullCatalogue() {
        val compact = AiModelCatalog.common("openrouter", "private/current-model", listOf("private/recent-model", "private/current-model"))
        assertEquals(listOf("private/current-model", "private/recent-model"), compact.take(2))
        assertTrue(compact.size < 15)
        assertTrue(AiModelCatalog.models("openrouter").size > 100)
        assertTrue(AiModelCatalog.search(AiModelCatalog.models("openrouter"), "deepseek").isNotEmpty())
    }
    @Test fun routerCatalogCoversMainstreamFamiliesWithoutBatchOrInvalidIds() {
        val models = AiModelCatalog.models("openrouter")
        listOf("openai/", "anthropic/", "google/", "moonshotai/", "z-ai/", "deepseek/", "qwen/").forEach { family ->
            assertTrue(family, models.any { it.startsWith(family) })
        }
        assertEquals(models.size, models.distinct().size)
        assertTrue(models.all { AiDefaults.validModel(it) && !it.contains(":batch") })
    }

    @Test fun directProviderModelsAndCustomValuesDoNotMix() {
        assertTrue(AiModelCatalog.models("kimi").all { it.startsWith("kimi-") })
        assertTrue(AiModelCatalog.models("glm").all { it.startsWith("glm-") })
        assertTrue(AiModelCatalog.models("openai").all { it.startsWith("gpt-") })
        assertTrue(AiModelCatalog.options("kimi", "custom-model").contains("custom-model"))
        assertTrue(AiModelCatalog.models("unknown").isEmpty())
        assertTrue(AiModelCatalog.models("ark").isEmpty())
        assertEquals(listOf("kimi-k3"), AiModelCatalog.search(AiModelCatalog.models("kimi"), " KIMI-K3 "))
    }
}
