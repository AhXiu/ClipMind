package com.clipmind.android.ui

import com.clipmind.android.data.AiMode
import com.clipmind.android.network.ModelListResult
import com.clipmind.android.network.ProviderModelLoader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ModelCatalogControllerTest {
    @Test fun selectingAloneDoesNotSendCredentialsAndDuplicateRefreshIsIgnored() = runTest {
        var calls = 0
        val response = CompletableDeferred<ModelListResult>()
        val controller = ModelCatalogController(this, ProviderModelLoader { provider, key ->
            assertEquals("kimi", provider); assertEquals("kimi-key", key); calls++; response.await()
        }) { "$it-key" }
        controller.select(AiMode.BYOK_KIMI)
        runCurrent()
        assertEquals(0, calls)
        controller.refresh(AiMode.BYOK_KIMI)
        controller.refresh(AiMode.BYOK_KIMI)
        runCurrent()
        assertEquals(1, calls)
        response.complete(ModelListResult.Success(listOf("kimi-current")))
        runCurrent()
        assertEquals(listOf("kimi-current"), controller.state.value.models)
    }

    @Test fun lateResponseFromPreviousProviderCannotReplaceTheCurrentList() = runTest {
        val previous = CompletableDeferred<ModelListResult>()
        val controller = ModelCatalogController(this, ProviderModelLoader { provider, key ->
            assertEquals("$provider-key", key)
            if (provider == "kimi") withContext(NonCancellable) { previous.await() }
            else ModelListResult.Success(listOf("claude-current"))
        }) { "$it-key" }
        controller.select(AiMode.BYOK_KIMI); controller.refresh(AiMode.BYOK_KIMI); runCurrent()
        controller.select(AiMode.BYOK_ANTHROPIC); controller.refresh(AiMode.BYOK_ANTHROPIC); runCurrent()
        previous.complete(ModelListResult.Success(listOf("kimi-old"))); runCurrent()
        assertEquals("anthropic", controller.state.value.provider)
        assertEquals(listOf("claude-current"), controller.state.value.models)
    }

    @Test fun changingOrClearingAKeyInvalidatesItsListAndLateCallbacks() = runTest {
        val previous = CompletableDeferred<ModelListResult>()
        var key: String? = "old-account"
        val controller = ModelCatalogController(this, ProviderModelLoader { _, credential ->
            if (credential == "old-account") withContext(NonCancellable) { previous.await() }
            else ModelListResult.Success(listOf("gpt-new-account"))
        }) { key }
        val mode = AiMode.BYOK_OPENAI
        controller.select(mode); controller.refresh(mode); runCurrent()
        key = "new-account"
        controller.invalidate(mode); controller.refresh(mode); runCurrent()
        previous.complete(ModelListResult.Success(listOf("gpt-old-account"))); runCurrent()
        assertEquals(listOf("gpt-new-account"), controller.state.value.models)
        key = null
        controller.invalidate(mode)
        assertTrue(controller.state.value.models.isEmpty())
        controller.refresh(mode); runCurrent()
        assertEquals("BYOK_KEY_MISSING", controller.state.value.errorCode)
    }

    @Test fun failuresClearOldModelsAndServerOrUnsupportedModesNeverSendKeys() = runTest {
        var fail = false
        val controller = ModelCatalogController(this, ProviderModelLoader { _, _ ->
            if (fail) ModelListResult.Failure("BYOK_AUTH", 401) else ModelListResult.Success(listOf("kimi-test"))
        }) { provider -> check(provider == "kimi"); "fake-key" }
        controller.select(AiMode.BYOK_KIMI); controller.refresh(AiMode.BYOK_KIMI); runCurrent()
        fail = true
        controller.refresh(AiMode.BYOK_KIMI); runCurrent()
        assertTrue(controller.state.value.models.isEmpty())
        assertEquals(401, controller.state.value.httpStatus)
        listOf(AiMode.SERVER_ARK, AiMode.BYOK_ARK, AiMode.BYOK_GLM).forEach {
            controller.select(it); controller.refresh(it); runCurrent()
            assertTrue(controller.state.value.models.isEmpty())
        }
    }
}
