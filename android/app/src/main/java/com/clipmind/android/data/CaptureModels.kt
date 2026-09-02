package com.clipmind.android.data

enum class CaptureMode { AUTO, CONFIRM }
enum class OutboxState { PENDING_CONFIRMATION, READY, UPLOADING, SUCCEEDED, DISCARDED, REJECTED, DECRYPTION_FAILED, RETRYABLE_ERROR }

data class NewCapture(
    val clientCaptureId: String,
    val rawText: String,
    val hash: String,
    val sourceApp: String?,
    val sourceUrl: String?,
    val mode: CaptureMode,
    val capturedAt: Long,
)
