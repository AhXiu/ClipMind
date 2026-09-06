package com.clipmind.android.service

import com.clipmind.android.data.CaptureDecision
import com.clipmind.android.data.OutboxState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface CaptureProcessingResult {
    data class Stored(val id: Long, val state: OutboxState) : CaptureProcessingResult
    data class Filtered(val reason: String) : CaptureProcessingResult
    data object Duplicate24H : CaptureProcessingResult
    data class EncryptionFailed(val code: String) : CaptureProcessingResult
    data class ProcessingFailed(val type: String) : CaptureProcessingResult
}

data class CaptureProcessingDiagnostic(
    val result: CaptureProcessingResult,
    val processedAt: Long,
    val characterCount: Int,
)

data class CaptureDecisionHandling(
    val result: CaptureProcessingResult,
    val commitRecentHash: Boolean,
)

class CaptureProcessingDiagnostics {
    private val mutableLatest = MutableStateFlow<CaptureProcessingDiagnostic?>(null)
    val latest: StateFlow<CaptureProcessingDiagnostic?> = mutableLatest.asStateFlow()

    fun record(result: CaptureProcessingResult, processedAt: Long, characterCount: Int) {
        mutableLatest.value = CaptureProcessingDiagnostic(result, processedAt, characterCount)
    }
}

internal fun handlingFor(decision: CaptureDecision): CaptureDecisionHandling = when (decision) {
    is CaptureDecision.Stored -> CaptureDecisionHandling(
        CaptureProcessingResult.Stored(decision.id, decision.state),
        commitRecentHash = true,
    )
    is CaptureDecision.Filtered -> CaptureDecisionHandling(
        CaptureProcessingResult.Filtered(decision.reason),
        commitRecentHash = true,
    )
    CaptureDecision.Duplicate -> CaptureDecisionHandling(
        CaptureProcessingResult.Duplicate24H,
        commitRecentHash = true,
    )
    is CaptureDecision.EncryptionFailed -> CaptureDecisionHandling(
        CaptureProcessingResult.EncryptionFailed(decision.code),
        commitRecentHash = false,
    )
}

internal fun handlingForFailure(error: Throwable): CaptureDecisionHandling = CaptureDecisionHandling(
    CaptureProcessingResult.ProcessingFailed(error::class.java.simpleName.ifBlank { "Unknown" }),
    commitRecentHash = false,
)

internal fun CaptureProcessingDiagnostic.toUiDescription(): String = when (val value = result) {
    is CaptureProcessingResult.Stored -> "STORED（state=${value.state.name}，记录 id=${value.id}）"
    is CaptureProcessingResult.Filtered -> "FILTERED（reason=${value.reason}）"
    CaptureProcessingResult.Duplicate24H -> "DUPLICATE_24H（24 小时内已存在）"
    is CaptureProcessingResult.EncryptionFailed -> "ENCRYPTION_FAILED（code=${value.code}）"
    is CaptureProcessingResult.ProcessingFailed -> "PROCESSING_FAILED（type=${value.type}）"
}
