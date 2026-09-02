package com.clipmind.android.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CaptureOutboxDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: CaptureOutboxEntity): Long

    @Query("SELECT * FROM capture_outbox WHERE state = 'PENDING_CONFIRMATION' ORDER BY capturedAt DESC")
    fun observePendingConfirmation(): Flow<List<CaptureOutboxEntity>>

    @Query("SELECT * FROM capture_outbox WHERE state != 'DISCARDED' ORDER BY capturedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 30): Flow<List<CaptureOutboxEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM capture_outbox WHERE hash = :hash AND state != 'DISCARDED' AND capturedAt >= :since)")
    suspend fun hashExistsSince(hash: String, since: Long): Boolean

    @Query("UPDATE capture_outbox SET state = 'READY', updatedAt = :now WHERE id = :id AND state = 'PENDING_CONFIRMATION'")
    suspend fun confirm(id: Long, now: Long): Int

    @Query("UPDATE capture_outbox SET state = 'DISCARDED', updatedAt = :now WHERE id = :id AND state = 'PENDING_CONFIRMATION'")
    suspend fun discard(id: Long, now: Long): Int

    @Query("UPDATE capture_outbox SET state = 'RETRYABLE_ERROR', retryCount = retryCount + 1, nextRetryAt = :now, updatedAt = :now, lastErrorCode = 'INTERRUPTED_UPLOAD' WHERE state = 'UPLOADING' AND updatedAt < :staleBefore")
    suspend fun recoverStaleUploads(staleBefore: Long, now: Long): Int

    @Query("SELECT * FROM capture_outbox WHERE (state = 'READY' OR state = 'RETRYABLE_ERROR') AND nextRetryAt <= :now ORDER BY capturedAt LIMIT :limit")
    suspend fun eligibleBatch(now: Long, limit: Int): List<CaptureOutboxEntity>

    @Query("UPDATE capture_outbox SET state = 'DECRYPTION_FAILED', updatedAt = :now, lastErrorCode = :errorCode WHERE id IN (:ids) AND (state = 'READY' OR state = 'RETRYABLE_ERROR')")
    suspend fun markDecryptionFailed(ids: List<Long>, now: Long, errorCode: String): Int

    @Query("UPDATE capture_outbox SET state = 'UPLOADING', updatedAt = :now WHERE id IN (:ids) AND (state = 'READY' OR state = 'RETRYABLE_ERROR')")
    suspend fun markUploading(ids: List<Long>, now: Long): Int

    @Query("UPDATE capture_outbox SET state = 'SUCCEEDED', updatedAt = :now, lastErrorCode = NULL WHERE clientCaptureId IN (:clientIds) AND state = 'UPLOADING'")
    suspend fun markSucceeded(clientIds: List<String>, now: Long): Int

    @Query("UPDATE capture_outbox SET state = 'REJECTED', updatedAt = :now, lastErrorCode = :errorCode WHERE clientCaptureId IN (:clientIds) AND state = 'UPLOADING'")
    suspend fun markRejected(clientIds: List<String>, now: Long, errorCode: String): Int

    @Query("UPDATE capture_outbox SET state = 'RETRYABLE_ERROR', retryCount = retryCount + 1, nextRetryAt = :nextRetryAt, updatedAt = :now, lastErrorCode = :errorCode WHERE id IN (:ids) AND state = 'UPLOADING'")
    suspend fun markRetryable(ids: List<Long>, now: Long, nextRetryAt: Long, errorCode: String): Int
}
