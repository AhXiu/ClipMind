package com.clipmind.android.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface CaptureOutboxDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCard(entity: LocalCardEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSyncMetadata(entity: SyncMetadataEntity)

    @Transaction
    suspend fun insert(entity: CaptureOutboxEntity): Long {
        val id = insertCard(entity.toLocalCard())
        if (id != -1L) insertSyncMetadata(entity.toSyncMetadata(id))
        return id
    }

    @Query("""SELECT c.id, c.clientCaptureId, c.encryptedContent AS encryptedRawText, c.hash, c.sourceApp,
        c.sourceUrl, c.mode, s.uploadState AS state, s.retryCount, s.nextRetryAt, c.capturedAt,
        s.updatedAt, s.lastErrorCode, s.serverCardId, s.serverCardStatus, s.serverLastError,
        s.aiProvider, s.aiModel, s.encryptedClientAnalysis FROM local_cards c
        JOIN sync_metadata s ON s.cardId = c.id
        WHERE s.uploadState = 'PENDING_CONFIRMATION' AND c.deletedAt IS NULL ORDER BY c.capturedAt DESC""")
    fun observePendingConfirmation(): Flow<List<CaptureOutboxEntity>>

    @Query("""SELECT c.id, c.clientCaptureId, c.encryptedContent AS encryptedRawText, c.hash, c.sourceApp,
        c.sourceUrl, c.mode, s.uploadState AS state, s.retryCount, s.nextRetryAt, c.capturedAt,
        s.updatedAt, s.lastErrorCode, s.serverCardId, s.serverCardStatus, s.serverLastError,
        s.aiProvider, s.aiModel, s.encryptedClientAnalysis FROM local_cards c
        JOIN sync_metadata s ON s.cardId = c.id
        WHERE c.deletedAt IS NULL ORDER BY c.capturedAt DESC LIMIT :limit""")
    fun observeRecent(limit: Int = 30): Flow<List<CaptureOutboxEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM local_cards WHERE hash = :hash AND deletedAt IS NULL AND capturedAt >= :since)")
    suspend fun hashExistsSince(hash: String, since: Long): Boolean

    @Query("UPDATE sync_metadata SET uploadState = 'READY', updatedAt = :now WHERE cardId = :id AND uploadState = 'PENDING_CONFIRMATION'")
    suspend fun confirm(id: Long, now: Long): Int

    @Query("UPDATE sync_metadata SET uploadState = 'DISCARDED', updatedAt = :now WHERE cardId = :id AND uploadState = 'PENDING_CONFIRMATION'")
    suspend fun markDiscarded(id: Long, now: Long): Int

    @Query("UPDATE local_cards SET deletedAt = :now, updatedAt = :now WHERE id = :id AND deletedAt IS NULL")
    suspend fun softDeleteCard(id: Long, now: Long): Int

    @Transaction
    suspend fun discard(id: Long, now: Long): Int {
        val changed = markDiscarded(id, now)
        if (changed == 1) softDeleteCard(id, now)
        return changed
    }

    @Query("UPDATE sync_metadata SET uploadState = 'RETRYABLE_ERROR', retryCount = retryCount + 1, nextRetryAt = :now, updatedAt = :now, lastErrorCode = 'INTERRUPTED_UPLOAD' WHERE uploadState = 'UPLOADING' AND updatedAt < :staleBefore")
    suspend fun recoverStaleUploads(staleBefore: Long, now: Long): Int

    @Query("""SELECT c.id, c.clientCaptureId, c.encryptedContent AS encryptedRawText, c.hash, c.sourceApp,
        c.sourceUrl, c.mode, s.uploadState AS state, s.retryCount, s.nextRetryAt, c.capturedAt,
        s.updatedAt, s.lastErrorCode, s.serverCardId, s.serverCardStatus, s.serverLastError,
        s.aiProvider, s.aiModel, s.encryptedClientAnalysis FROM local_cards c
        JOIN sync_metadata s ON s.cardId = c.id WHERE c.deletedAt IS NULL
        AND (s.uploadState = 'READY' OR s.uploadState = 'RETRYABLE_ERROR') AND s.nextRetryAt <= :now
        ORDER BY c.capturedAt LIMIT :limit""")
    suspend fun eligibleBatch(now: Long, limit: Int): List<CaptureOutboxEntity>

    @Query("""UPDATE sync_metadata SET uploadState = 'UPLOADING', updatedAt = :now
        WHERE cardId = :id AND uploadState IN ('READY', 'RETRYABLE_ERROR')
        AND EXISTS(SELECT 1 FROM local_cards WHERE id = :id AND clientCaptureId = :taskId AND deletedAt IS NULL)""")
    suspend fun claim(id: Long, taskId: String, now: Long): Int

    @Query("UPDATE sync_metadata SET uploadState = 'DECRYPTION_FAILED', updatedAt = :now, lastErrorCode = :errorCode WHERE cardId IN (:ids) AND uploadState IN ('READY', 'RETRYABLE_ERROR', 'UPLOADING')")
    suspend fun markDecryptionFailed(ids: List<Long>, now: Long, errorCode: String): Int

    @Query("UPDATE sync_metadata SET encryptedClientAnalysis = :encryptedAnalysis, updatedAt = :now, lastErrorCode = NULL WHERE cardId = :id AND encryptedClientAnalysis IS NULL AND uploadState = 'UPLOADING'")
    suspend fun cacheClientAnalysis(id: Long, encryptedAnalysis: String, now: Long): Int

    @Query("UPDATE sync_metadata SET uploadState = 'RETRYABLE_ERROR', retryCount = retryCount + 1, nextRetryAt = :nextRetryAt, updatedAt = :now, lastErrorCode = :errorCode WHERE cardId = :id AND uploadState = 'UPLOADING'")
    suspend fun markAnalysisError(id: Long, now: Long, nextRetryAt: Long, errorCode: String): Int

    @Query("UPDATE sync_metadata SET uploadState = 'UPLOADING', updatedAt = :now WHERE cardId IN (:ids) AND (uploadState = 'READY' OR uploadState = 'RETRYABLE_ERROR')")
    suspend fun markUploading(ids: List<Long>, now: Long): Int

    @Query("UPDATE sync_metadata SET uploadState = 'SUCCEEDED', serverCardId = :serverCardId, updatedAt = :now, lastErrorCode = NULL WHERE cardId = (SELECT id FROM local_cards WHERE clientCaptureId = :clientId) AND uploadState = 'UPLOADING'")
    suspend fun markSucceeded(clientId: String, serverCardId: String?, now: Long): Int

    @Query("SELECT c.clientCaptureId FROM local_cards c JOIN sync_metadata s ON s.cardId = c.id WHERE c.id = :id AND s.serverCardId = :cardId AND s.uploadState = 'SUCCEEDED' AND c.deletedAt IS NULL")
    suspend fun serverReadTask(id: Long, cardId: String): String?

    @Query("""UPDATE sync_metadata SET serverCardStatus = :status, serverLastError = :serverLastError,
        encryptedServerAnalysis = :analysis, updatedAt = :now WHERE cardId = :id AND serverCardId = :cardId
        AND uploadState = 'SUCCEEDED' AND EXISTS(SELECT 1 FROM local_cards WHERE id = :id AND clientCaptureId = :taskId AND deletedAt IS NULL)""")
    suspend fun updateServerCard(id: Long, cardId: String, taskId: String, status: String, serverLastError: String?, analysis: String?, now: Long): Int

    @Query("UPDATE sync_metadata SET serverLastError = :errorCode, updatedAt = :now WHERE cardId = :id AND serverCardId = :cardId AND uploadState = 'SUCCEEDED' AND EXISTS(SELECT 1 FROM local_cards WHERE id = :id AND clientCaptureId = :taskId AND deletedAt IS NULL)")
    suspend fun updateServerCardError(id: Long, cardId: String, taskId: String, errorCode: String, now: Long): Int

    @Query("UPDATE sync_metadata SET uploadState = 'REJECTED', updatedAt = :now, lastErrorCode = :errorCode WHERE cardId IN (SELECT id FROM local_cards WHERE clientCaptureId IN (:clientIds)) AND uploadState = 'UPLOADING'")
    suspend fun markRejected(clientIds: List<String>, now: Long, errorCode: String): Int

    @Query("UPDATE sync_metadata SET uploadState = 'RETRYABLE_ERROR', retryCount = retryCount + 1, nextRetryAt = :nextRetryAt, updatedAt = :now, lastErrorCode = :errorCode WHERE cardId IN (:ids) AND uploadState = 'UPLOADING'")
    suspend fun markRetryable(ids: List<Long>, now: Long, nextRetryAt: Long, errorCode: String): Int
}

private fun CaptureOutboxEntity.toLocalCard() = LocalCardEntity(
    id = id, clientCaptureId = clientCaptureId, encryptedContent = encryptedRawText, hash = hash,
    sourceApp = sourceApp, sourceUrl = sourceUrl, mode = mode, capturedAt = capturedAt, updatedAt = updatedAt,
)

private fun CaptureOutboxEntity.toSyncMetadata(cardId: Long) = SyncMetadataEntity(
    cardId = cardId, uploadState = state, retryCount = retryCount, nextRetryAt = nextRetryAt,
    updatedAt = updatedAt, lastErrorCode = lastErrorCode, serverCardId = serverCardId,
    serverCardStatus = serverCardStatus, serverLastError = serverLastError, aiProvider = aiProvider,
    aiModel = aiModel, encryptedClientAnalysis = encryptedClientAnalysis,
)
