package com.clipmind.android.shizuku

sealed interface ClipboardDiagnosticUiState {
    data object Idle : ClipboardDiagnosticUiState
    data object Checking : ClipboardDiagnosticUiState
    data class Success(val characterCount: Int) : ClipboardDiagnosticUiState
    data class Failed(val code: String, val detail: String?) : ClipboardDiagnosticUiState
}

internal fun ClipboardReadResult.toDiagnosticUiState(): ClipboardDiagnosticUiState = when (this) {
    is ClipboardReadResult.Success -> ClipboardDiagnosticUiState.Success(text.length)
    is ClipboardReadResult.Error -> ClipboardDiagnosticUiState.Failed(code, detail)
}
