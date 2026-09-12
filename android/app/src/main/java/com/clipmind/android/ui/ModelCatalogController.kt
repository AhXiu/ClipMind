package com.clipmind.android.ui

import com.clipmind.android.data.AiDefaults
import com.clipmind.android.data.AiMode
import com.clipmind.android.network.ModelListResult
import com.clipmind.android.network.ProviderModelLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class ModelCatalogStatus { IDLE, LOADING, LOADED, FAILED, UNSUPPORTED }

data class ModelCatalogUiState(
    val provider: String? = null,
    val status: ModelCatalogStatus = ModelCatalogStatus.IDLE,
    val models: List<String> = emptyList(),
    val errorCode: String? = null,
    val httpStatus: Int? = null,
)

class ModelCatalogController(
    private val scope: CoroutineScope,
    private val loader: ProviderModelLoader,
    private val readKey: (String) -> String?,
) {
    private val mutableState = MutableStateFlow(ModelCatalogUiState())
    val state = mutableState.asStateFlow()
    private var selected: AiMode? = null
    private var generation = 0L
    private var request: Job? = null

    fun select(mode: AiMode) { if (selected != mode) invalidate(mode) }

    fun invalidate(mode: AiMode) {
        generation++
        request?.cancel()
        selected = mode
        mutableState.value = ModelCatalogUiState(
            provider = if (mode.isByok) mode.providerId else null,
            status = if (mode.isByok && AiDefaults.modelsEndpoint(mode.providerId) == null) ModelCatalogStatus.UNSUPPORTED else ModelCatalogStatus.IDLE,
        )
    }

    /** Call only after the user confirms a provider switch, Key save, or metadata refresh. */
    fun refresh(mode: AiMode) {
        if (selected != mode || !mode.isByok || mutableState.value.status == ModelCatalogStatus.LOADING ||
            AiDefaults.modelsEndpoint(mode.providerId) == null) return
        val key = readKey(mode.providerId)
        if (key == null) {
            mutableState.value = ModelCatalogUiState(mode.providerId, ModelCatalogStatus.FAILED, errorCode = "BYOK_KEY_MISSING")
            return
        }
        val expected = ++generation
        mutableState.value = ModelCatalogUiState(mode.providerId, ModelCatalogStatus.LOADING)
        request = scope.launch {
            val result = try { loader.listModels(mode.providerId, key) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { ModelListResult.Failure("MODEL_LIST_INVALID") }
            // Cancellation alone cannot prevent a late callback from a replaced account or provider.
            if (generation != expected || selected != mode) return@launch
            mutableState.value = when (result) {
                is ModelListResult.Success -> ModelCatalogUiState(mode.providerId, ModelCatalogStatus.LOADED, result.models)
                is ModelListResult.Failure -> ModelCatalogUiState(mode.providerId, ModelCatalogStatus.FAILED,
                    errorCode = result.code, httpStatus = result.httpStatus)
            }
        }
    }
}
