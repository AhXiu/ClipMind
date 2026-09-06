package com.clipmind.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.clipmind.android.BuildConfig
import com.clipmind.android.ClipMindApp
import com.clipmind.android.data.AiMode
import com.clipmind.android.data.CaptureMode
import com.clipmind.android.data.CaptureUiModel
import com.clipmind.android.data.ServerCardOperationResult
import com.clipmind.android.network.ByokAnalysisResult
import com.clipmind.android.network.HealthCheckResult
import com.clipmind.android.service.CaptureForegroundService
import com.clipmind.android.service.CaptureProcessingDiagnostic
import com.clipmind.android.shizuku.ClipboardDiagnosticUiState
import com.clipmind.android.shizuku.ShizukuState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface ConnectionUiState {
    data object Idle : ConnectionUiState
    data object Checking : ConnectionUiState
    data object Connected : ConnectionUiState
    data class Failed(val reason: String) : ConnectionUiState
}

sealed interface AiConnectionUiState {
    data object Idle : AiConnectionUiState
    data object Checking : AiConnectionUiState
    data object Success : AiConnectionUiState
    data class Failed(val errorCode: String) : AiConnectionUiState
}

sealed interface CardOperationUiState {
    data object Working : CardOperationUiState
    data class Success(val status: String) : CardOperationUiState
    data class Failed(val errorCode: String) : CardOperationUiState
}

internal fun shouldShowPublishAction(mode: CaptureMode, serverCardStatus: String?): Boolean =
    mode == CaptureMode.CONFIRM && serverCardStatus == "awaiting_confirm"

data class MainUiState(
    val shizukuState: ShizukuState = ShizukuState.UNAVAILABLE,
    val captureRequested: Boolean = false,
    val mode: CaptureMode = CaptureMode.CONFIRM,
    val tokenConfigured: Boolean = false,
    val pending: List<CaptureUiModel> = emptyList(),
    val recent: List<CaptureUiModel> = emptyList(),
    val apiBaseUrl: String = BuildConfig.API_BASE_URL,
    val connectionState: ConnectionUiState = ConnectionUiState.Idle,
    val clipboardDiagnostic: ClipboardDiagnosticUiState = ClipboardDiagnosticUiState.Idle,
    val captureProcessingDiagnostic: CaptureProcessingDiagnostic? = null,
    val cardOperations: Map<Long, CardOperationUiState> = emptyMap(),
    val aiMode: AiMode = AiMode.SERVER_ARK,
    val arkModel: String = com.clipmind.android.data.AiDefaults.ARK_MODEL,
    val openRouterModel: String = "",
    val apiKeyConfigured: Boolean = false,
    val aiConnectionState: AiConnectionUiState = AiConnectionUiState.Idle,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as ClipMindApp
    private val container = app.container
    private val connectionState = MutableStateFlow<ConnectionUiState>(ConnectionUiState.Idle)
    private val aiConnectionState = MutableStateFlow<AiConnectionUiState>(AiConnectionUiState.Idle)
    private val cardOperations = MutableStateFlow<Map<Long, CardOperationUiState>>(emptyMap())

    val uiState: StateFlow<MainUiState> = combine(
        container.shizuku.state,
        container.settings.captureRequested,
        container.settings.mode,
        container.repository.pending,
        container.repository.recent,
    ) { shizuku, capture, mode, pending, recent ->
        MainUiState(shizuku, capture, mode, pending = pending, recent = recent)
    }.combine(container.tokenStore.configured) { state, configured -> state.copy(tokenConfigured = configured) }
        .combine(connectionState) { state, connection -> state.copy(connectionState = connection) }
        .combine(container.shizuku.clipboardDiagnostic) { state, value -> state.copy(clipboardDiagnostic = value) }
        .combine(container.captureDiagnostics.latest) { state, value -> state.copy(captureProcessingDiagnostic = value) }
        .combine(cardOperations) { state, value -> state.copy(cardOperations = value) }
        .combine(container.settings.aiMode) { state, value -> state.copy(aiMode = value) }
        .combine(container.settings.arkModel) { state, value -> state.copy(arkModel = value) }
        .combine(container.settings.openRouterModel) { state, value -> state.copy(openRouterModel = value) }
        .combine(container.apiKeyStore.configured) { state, value -> state.copy(apiKeyConfigured = value) }
        .combine(aiConnectionState) { state, value -> state.copy(aiConnectionState = value) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    fun requestShizukuPermission() = container.shizuku.requestPermission()
    fun reconnect() = container.shizuku.reconnect()
    fun setMode(mode: CaptureMode) = container.settings.setMode(mode)
    fun setAiMode(mode: AiMode) { container.settings.setAiMode(mode); aiConnectionState.value = AiConnectionUiState.Idle }
    fun setArkModel(model: String) { container.settings.setArkModel(model); aiConnectionState.value = AiConnectionUiState.Idle }
    fun setOpenRouterModel(model: String) { container.settings.setOpenRouterModel(model); aiConnectionState.value = AiConnectionUiState.Idle }
    fun saveApiKey(key: String): Boolean = container.apiKeyStore.overwrite(key)
    fun clearApiKey() { container.apiKeyStore.clear(); aiConnectionState.value = AiConnectionUiState.Idle }
    fun saveToken(token: String): Boolean = container.tokenStore.saveToken(token)
    fun clearToken() = container.tokenStore.clearToken()
    fun startCapture() = CaptureForegroundService.start(app)
    fun stopCapture() = CaptureForegroundService.stop(app)
    fun confirm(id: Long) = viewModelScope.launch { container.repository.confirm(id) }
    fun discard(id: Long) = viewModelScope.launch { container.repository.discard(id) }

    fun testModelConnection() {
        if (aiConnectionState.value == AiConnectionUiState.Checking) return
        val config = container.settings.captureAiConfiguration()
        if (!config.mode.isByok) {
            aiConnectionState.value = AiConnectionUiState.Failed("SERVER_MODE_NO_DIRECT_TEST")
            return
        }
        val key = container.apiKeyStore.readForAuthorization()
        if (key == null) {
            aiConnectionState.value = AiConnectionUiState.Failed("BYOK_KEY_MISSING")
            return
        }
        aiConnectionState.value = AiConnectionUiState.Checking
        viewModelScope.launch {
            aiConnectionState.value = when (
                val result = container.clientAnalyzer.analyze(config.mode.providerId, config.model, key, "连接测试")
            ) {
                is ByokAnalysisResult.Success -> AiConnectionUiState.Success
                is ByokAnalysisResult.Failure -> AiConnectionUiState.Failed(result.code.name)
            }
        }
    }

    fun refreshServerCard(id: Long, cardId: String) = runCardOperation(id) {
        container.cardRepository.refreshServerCard(id, cardId)
    }

    fun confirmServerCard(id: Long, cardId: String) = runCardOperation(id) {
        container.cardRepository.confirmServerCard(id, cardId)
    }

    private fun runCardOperation(id: Long, operation: suspend () -> ServerCardOperationResult) {
        if (cardOperations.value[id] == CardOperationUiState.Working) return
        cardOperations.value = cardOperations.value + (id to CardOperationUiState.Working)
        viewModelScope.launch {
            cardOperations.value = cardOperations.value + (id to when (val result = operation()) {
                is ServerCardOperationResult.Success -> CardOperationUiState.Success(result.card.status)
                is ServerCardOperationResult.Failure -> CardOperationUiState.Failed(result.errorCode)
            })
        }
    }

    fun testClipboardRead() {
        if (container.shizuku.clipboardDiagnostic.value == ClipboardDiagnosticUiState.Checking) return
        container.shizuku.markClipboardCheckStarted()
        viewModelScope.launch(Dispatchers.IO) { container.shizuku.readClipboard() }
    }

    fun testConnection() {
        if (connectionState.value == ConnectionUiState.Checking) return
        connectionState.value = ConnectionUiState.Checking
        viewModelScope.launch { connectionState.value = container.healthChecker.check().toConnectionUiState() }
    }
}

internal fun HealthCheckResult.toConnectionUiState(): ConnectionUiState = when (this) {
    HealthCheckResult.Success -> ConnectionUiState.Connected
    is HealthCheckResult.Failure -> ConnectionUiState.Failed(reason)
}
