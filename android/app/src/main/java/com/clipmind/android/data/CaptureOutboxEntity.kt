package com.clipmind.android.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "capture_outbox",
    indices = [Index(value = ["clientCaptureId"], unique = true), Index("hash"), Index("state", "nextRetryAt")],
)
data class CaptureOutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
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
)
