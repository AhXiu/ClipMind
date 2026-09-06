package com.clipmind.android.data

import com.clipmind.android.domain.CaptureHash
import com.clipmind.android.domain.FilterResult
import com.clipmind.android.domain.LocalSafetyFilter
import com.clipmind.android.security.TextCipher
import com.clipmind.android.security.TextCipherException
import com.clipmind.android.worker.ImmediateUploadScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.util.UUID

data class CaptureUiModel(
    val id: Long,
    val content: String,
    val contentAvailable: Boolean,
    val state: OutboxState,
    val capturedAt: Long,
    val lastErrorCode: String?,
    val retryCount: Int,
    val nextRetryAt: Long,
    val mode: CaptureMode,
    val serverCardId: String?,
    val serverCardStatus: String?,
    val serverLastError: String?,
)

class CaptureRepository(
    private val db: ClipMindDatabase,
    private val filter: LocalSafetyFilter,
    private val cipher: TextCipher,
    private val uploadScheduler: ImmediateUploadScheduler,
    private val settings: UserSettings,
) {
    val pending: Flow<List<CaptureUiModel>> = db.captureOutboxDao().observePendingConfirmation()
        .map { entities -> entities.map { it.toUiModel(cipher) } }.flowOn(Dispatchers.Default)
    val recent: Flow<List<CaptureUiModel>> = db.captureOutboxDao().observeRecent()
        .map { entities -> entities.map { it.toUiModel(cipher) } }.flowOn(Dispatchers.Default)

    suspend fun capture(rawText: String, sourceApp: String?, mode: CaptureMode, now: Long): CaptureDecision {
        when (val result = filter.evaluate(rawText, sourceApp)) {
            is FilterResult.Rejected -> return CaptureDecision.Filtered(result.reason.name)
            FilterResult.Allowed -> Unit
        }
        val normalized = CaptureHash.normalize(rawText)
        val hash = CaptureHash.sha256(normalized)
        val dao = db.captureOutboxDao()
        if (dao.hashExistsSince(hash, now - 24 * 60 * 60 * 1000L)) return CaptureDecision.Duplicate
        val state = if (mode == CaptureMode.AUTO) OutboxState.READY else OutboxState.PENDING_CONFIRMATION
        val entity = try {
            val ai = settings.captureAiConfiguration()
            createEncryptedCaptureEntity(normalized, hash, sourceApp, null, mode, state, now, cipher, ai)
        } catch (e: TextCipherException) {
            return CaptureDecision.EncryptionFailed("ENCRYPT_${e.error.name}")
        }
        val id = dao.insert(entity)
        val decision = if (id == -1L) CaptureDecision.Duplicate else CaptureDecision.Stored(id, state)
        if (shouldScheduleImmediateUpload(decision)) uploadScheduler.schedule()
        return decision
    }

    suspend fun confirm(id: Long): Boolean {
        val updated = db.captureOutboxDao().confirm(id, System.currentTimeMillis()) == 1
        if (shouldScheduleImmediateUploadAfterConfirm(updated)) uploadScheduler.schedule()
        return updated
    }

    suspend fun discard(id: Long): Boolean = db.captureOutboxDao().discard(id, System.currentTimeMillis()) == 1
}

internal fun shouldScheduleImmediateUpload(decision: CaptureDecision): Boolean =
    decision is CaptureDecision.Stored && decision.state == OutboxState.READY

internal fun shouldScheduleImmediateUploadAfterConfirm(databaseUpdated: Boolean): Boolean = databaseUpdated

internal fun CaptureOutboxEntity.toUiModel(cipher: TextCipher): CaptureUiModel {
    val text = runCatching { cipher.decrypt(encryptedRawText) }.getOrNull()
    return CaptureUiModel(
        id = id,
        content = text ?: "内容无法解密",
        contentAvailable = text != null,
        state = state,
        capturedAt = capturedAt,
        lastErrorCode = lastErrorCode,
        retryCount = retryCount,
        nextRetryAt = nextRetryAt,
        mode = mode,
        serverCardId = serverCardId,
        serverCardStatus = serverCardStatus,
        serverLastError = serverLastError,
    )
}

internal fun createEncryptedCaptureEntity(
    plainText: String,
    hash: String,
    sourceApp: String?,
    sourceUrl: String?,
    mode: CaptureMode,
    state: OutboxState,
    now: Long,
    cipher: TextCipher,
    aiConfiguration: AiCaptureConfiguration = AiCaptureConfiguration(AiMode.SERVER_ARK, AiDefaults.ARK_MODEL),
): CaptureOutboxEntity = CaptureOutboxEntity(
    clientCaptureId = UUID.randomUUID().toString(),
    encryptedRawText = cipher.encrypt(plainText),
    hash = hash,
    sourceApp = sourceApp,
    sourceUrl = sourceUrl,
    mode = mode,
    state = state,
    capturedAt = now,
    updatedAt = now,
    aiProvider = aiConfiguration.mode.takeIf { it.isByok }?.providerId,
    aiModel = aiConfiguration.model,
)

sealed interface CaptureDecision {
    data class Stored(val id: Long, val state: OutboxState) : CaptureDecision
    data class Filtered(val reason: String) : CaptureDecision
    data class EncryptionFailed(val code: String) : CaptureDecision
    data object Duplicate : CaptureDecision
}
