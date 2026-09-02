package com.clipmind.android.data

import com.clipmind.android.domain.CaptureHash
import com.clipmind.android.domain.FilterResult
import com.clipmind.android.domain.LocalSafetyFilter
import com.clipmind.android.security.TextCipher
import com.clipmind.android.security.TextCipherException
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
)

class CaptureRepository(
    private val db: ClipMindDatabase,
    private val filter: LocalSafetyFilter,
    private val cipher: TextCipher,
) {
    val pending: Flow<List<CaptureUiModel>> = db.captureOutboxDao().observePendingConfirmation()
        .map(::decryptForUi).flowOn(Dispatchers.Default)
    val recent: Flow<List<CaptureUiModel>> = db.captureOutboxDao().observeRecent()
        .map(::decryptForUi).flowOn(Dispatchers.Default)

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
            createEncryptedCaptureEntity(normalized, hash, sourceApp, null, mode, state, now, cipher)
        } catch (e: TextCipherException) {
            return CaptureDecision.EncryptionFailed("ENCRYPT_${e.error.name}")
        }
        val id = dao.insert(entity)
        return if (id == -1L) CaptureDecision.Duplicate else CaptureDecision.Stored(id, state)
    }

    suspend fun confirm(id: Long): Boolean = db.captureOutboxDao().confirm(id, System.currentTimeMillis()) == 1
    suspend fun discard(id: Long): Boolean = db.captureOutboxDao().discard(id, System.currentTimeMillis()) == 1

    private fun decryptForUi(entities: List<CaptureOutboxEntity>): List<CaptureUiModel> = entities.map { entity ->
        val text = runCatching { cipher.decrypt(entity.encryptedRawText) }.getOrNull()
        CaptureUiModel(
            id = entity.id,
            content = text ?: "内容无法解密",
            contentAvailable = text != null,
            state = entity.state,
            capturedAt = entity.capturedAt,
        )
    }
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
)

sealed interface CaptureDecision {
    data class Stored(val id: Long, val state: OutboxState) : CaptureDecision
    data class Filtered(val reason: String) : CaptureDecision
    data class EncryptionFailed(val code: String) : CaptureDecision
    data object Duplicate : CaptureDecision
}
