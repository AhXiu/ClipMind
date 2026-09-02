package com.clipmind.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.clipmind.android.ClipMindApp
import com.clipmind.android.data.CaptureMode
import com.clipmind.android.data.CaptureUiModel
import com.clipmind.android.service.CaptureForegroundService
import com.clipmind.android.shizuku.ShizukuState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MainUiState(
    val shizukuState: ShizukuState = ShizukuState.UNAVAILABLE,
    val captureRequested: Boolean = false,
    val mode: CaptureMode = CaptureMode.CONFIRM,
    val tokenConfigured: Boolean = false,
    val pending: List<CaptureUiModel> = emptyList(),
    val recent: List<CaptureUiModel> = emptyList(),
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as ClipMindApp
    private val container = app.container

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
}
