package com.clipmind.android.data

/** Joined upload projection kept to minimize network/worker churn. It is not a persisted v4 entity. */
data class CaptureOutboxEntity(
    val id: Long = 0,
    val clientCaptureId: String,
    val encryptedRawText: String,
    val hash: String,
    val sourceApp: String?,
    val sourceUrl: String?,
    val mode: CaptureMode,
    val state: OutboxState,
    val retryCount: Int = 0,
    val nextRetryAt: Long = 0,
    val capturedAt: Long,
    val updatedAt: Long,
    val lastErrorCode: String? = null,
    val serverCardId: String? = null,
    val serverCardStatus: String? = null,
    val serverLastError: String? = null,
    val aiProvider: String? = null,
    val aiModel: String? = null,
    val encryptedClientAnalysis: String? = null,
)
