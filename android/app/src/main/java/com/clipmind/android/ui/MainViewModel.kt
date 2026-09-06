package com.clipmind.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.clipmind.android.BuildConfig
import com.clipmind.android.ClipMindApp
import com.clipmind.android.data.CaptureMode
import com.clipmind.android.data.CaptureUiModel
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
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as ClipMindApp
    private val container = app.container
    private val connectionState = MutableStateFlow<ConnectionUiState>(ConnectionUiState.Idle)

    val uiState: StateFlow<MainUiState> = combine(
        container.shizuku.state,
        container.settings.captureRequested,
        container.settings.mode,
        container.repository.pending,
        container.repository.recent,
    ) { shizuku, capture, mode, pending, recent ->
        MainUiState(shizuku, capture, mode, pending = pending, recent = recent)
    }.combine(container.tokenStore.configured) { state, tokenConfigured ->
        state.copy(tokenConfigured = tokenConfigured)
    }.combine(connectionState) { state, connection ->
        state.copy(connectionState = connection)
    }.combine(container.shizuku.clipboardDiagnostic) { state, diagnostic ->
        state.copy(clipboardDiagnostic = diagnostic)
    }.combine(container.captureDiagnostics.latest) { state, diagnostic ->
        state.copy(captureProcessingDiagnostic = diagnostic)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    fun requestShizukuPermission() = container.shizuku.requestPermission()
    fun reconnect() = container.shizuku.reconnect()
    fun setMode(mode: CaptureMode) = container.settings.setMode(mode)
    fun saveToken(token: String): Boolean = container.tokenStore.saveToken(token)
    fun clearToken() = container.tokenStore.clearToken()
    fun startCapture() = CaptureForegroundService.start(app)
    fun stopCapture() = CaptureForegroundService.stop(app)
    fun confirm(id: Long) = viewModelScope.launch { container.repository.confirm(id) }
    fun discard(id: Long) = viewModelScope.launch { container.repository.discard(id) }

    fun testClipboardRead() {
        if (container.shizuku.clipboardDiagnostic.value == ClipboardDiagnosticUiState.Checking) return
        container.shizuku.markClipboardCheckStarted()
        viewModelScope.launch(Dispatchers.IO) { container.shizuku.readClipboard() }
    }

    fun testConnection() {
        if (connectionState.value == ConnectionUiState.Checking) return
        connectionState.value = ConnectionUiState.Checking
        viewModelScope.launch {
            connectionState.value = container.healthChecker.check().toConnectionUiState()
        }
    }
}

internal fun HealthCheckResult.toConnectionUiState(): ConnectionUiState = when (this) {
    HealthCheckResult.Success -> ConnectionUiState.Connected
    is HealthCheckResult.Failure -> ConnectionUiState.Failed(reason)
}
