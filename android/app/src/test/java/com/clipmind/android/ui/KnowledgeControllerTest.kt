package com.clipmind.android.ui

import com.clipmind.android.data.AiCaptureConfiguration
import com.clipmind.android.data.AiMode
import com.clipmind.android.knowledge.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KnowledgeControllerTest {
    private class Store : KnowledgeStore {
        var current = true
        var saves = 0
        val input = KnowledgeRequest(listOf(KnowledgeCard("1", 1, "First selected excerpt"), KnowledgeCard("2", 1, "Second selected excerpt")))
        override suspend fun prepare(ids: Set<Long>) = input.also { require(ids == setOf(1L, 2L)) }
        override suspend fun isCurrent(input: KnowledgeRequest) = current
        override suspend fun save(input: KnowledgeRequest, response: KnowledgeResponse): String {
            if (!current) throw KnowledgeFailure("SOURCE_CHANGED")
            saves++
            return "note"
        }
        override fun observeNotes() = flowOf(emptyList<KnowledgeNote>())
        override suspend fun deleteNote(id: String) = Unit
        override suspend fun discover(sourceId: Long) = 0
    }
    private class Generator : KnowledgeGenerator {
        var calls = 0
        var sent: KnowledgeRequest? = null
        val result = CompletableDeferred<KnowledgeResponse>()
        override suspend fun generate(input: KnowledgeRequest, config: AiCaptureConfiguration, key: String?, authorization: String?): KnowledgeResponse {
            calls++; sent = input
            return result.await()
        }
    }
    private fun response(store: Store) = KnowledgeResponse(KnowledgeResult("Topic", store.input.cards.map {
        KnowledgePoint("summary", it.text, listOf(Evidence(it.id, it.text)))
    }, emptyList()), "ark", "test", "knowledge-v1")

    @Test fun previewNeverCallsProviderAndChangedConfigRequiresNewConsent() = runTest {
        val store = Store(); val generator = Generator()
        var config = AiCaptureConfiguration(AiMode.SERVER_ARK, "test")
        val controller = KnowledgeController(backgroundScope, store, generator, { config }, { true }, { null }, { null }, {})
        backgroundScope.launch { controller.state.collect() }
        controller.prepare(setOf(1, 2)); runCurrent()
        assertNotNull(controller.state.value.preview)
        assertEquals(0, generator.calls)
        config = config.copy(mode = AiMode.BYOK_ARK)
        controller.generate(); runCurrent()
        assertEquals(0, generator.calls)
        assertFalse(controller.state.value.running)
    }

    @Test fun inFlightSourceChangeRejectsLateResultWithoutRetry() = runTest {
        val store = Store(); val generator = Generator()
        val controller = KnowledgeController(backgroundScope, store, generator, { AiCaptureConfiguration(AiMode.SERVER_ARK, "test") }, { true }, { null }, { null }, {})
        backgroundScope.launch { controller.state.collect() }
        controller.prepare(setOf(1, 2)); runCurrent()
        controller.generate(); runCurrent()
        assertEquals(store.input, generator.sent)
        store.current = false
        generator.result.complete(response(store)); runCurrent()
        assertEquals(0, store.saves)
        assertEquals(1, generator.calls)
        assertFalse(controller.state.value.running)
    }

    @Test fun cancellationNeverSavesAndKeepsProviderCallCountAtOne() = runTest {
        val store = Store(); val generator = Generator()
        val controller = KnowledgeController(backgroundScope, store, generator, { AiCaptureConfiguration(AiMode.BYOK_ARK, "test") }, { true }, { "test-key" }, { null }, {})
        backgroundScope.launch { controller.state.collect() }
        controller.prepare(setOf(1, 2)); runCurrent()
        controller.generate(); runCurrent()
        controller.cancel(); runCurrent()
        generator.result.complete(response(store)); runCurrent()
        assertEquals(0, store.saves)
        assertEquals(1, generator.calls)
        assertFalse(controller.state.value.running)
    }
}
