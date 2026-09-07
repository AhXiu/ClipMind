package com.clipmind.android.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalCardDao {
    @Query("SELECT * FROM sync_metadata")
    fun observeSyncMetadata(): Flow<List<SyncMetadataEntity>>

    @Query("SELECT * FROM sync_metadata WHERE cardId = :cardId")
    suspend fun syncMetadata(cardId: Long): SyncMetadataEntity?

    @Query("""UPDATE sync_metadata SET uploadState = 'READY', retryCount = 0, nextRetryAt = 0,
        encryptedClientAnalysis = NULL, lastErrorCode = NULL, updatedAt = :now
        WHERE cardId IN (:cardIds) AND uploadState != 'DISCARDED'""")
    suspend fun queueAi(cardIds: List<Long>, now: Long): Int

    @Transaction
    @Query("SELECT * FROM local_cards WHERE deletedAt IS NULL ORDER BY capturedAt DESC")
    fun observeCards(): Flow<List<LocalCardWithTags>>

    @Transaction
    @Query("SELECT * FROM local_cards WHERE deletedAt IS NULL ORDER BY capturedAt DESC")
    suspend fun exportCards(): List<LocalCardWithTags>

    @Query("SELECT * FROM card_relations WHERE status = 'CONFIRMED'")
    suspend fun confirmedRelations(): List<CardRelationEntity>

    @Transaction
    @Query("SELECT * FROM local_cards WHERE id = :id AND deletedAt IS NULL")
    suspend fun card(id: Long): LocalCardWithTags?

    @Query("SELECT * FROM local_cards WHERE deletedAt IS NULL ORDER BY capturedAt DESC")
    suspend fun activeCards(): List<LocalCardEntity>

    @Query("UPDATE local_cards SET encryptedContent = :encryptedContent, hash = :hash, updatedAt = :now WHERE id = :id AND deletedAt IS NULL")
    suspend fun edit(id: Long, encryptedContent: String, hash: String, now: Long): Int

    @Query("UPDATE local_cards SET deletedAt = :now, updatedAt = :now WHERE id = :id AND deletedAt IS NULL")
    suspend fun softDelete(id: Long, now: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTag(tag: TagEntity): Long

    @Query("SELECT id FROM tags WHERE normalizedName = :normalizedName LIMIT 1")
    suspend fun tagId(normalizedName: String): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCardTag(ref: CardTagRefEntity): Long

    @Query("DELETE FROM card_tag_refs WHERE cardId = :cardId AND tagId = :tagId")
    suspend fun removeCardTag(cardId: Long, tagId: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRelation(relation: CardRelationEntity): Long

    @Query("UPDATE card_relations SET status = :status, updatedAt = :now WHERE id = :relationId AND status = 'CANDIDATE'")
    suspend fun resolveRelation(relationId: Long, status: RelationStatus, now: Long): Int

    @Query("SELECT * FROM card_relations WHERE (sourceCardId = :cardId OR targetCardId = :cardId) AND status != 'REJECTED' ORDER BY updatedAt DESC")
    fun observeRelations(cardId: Long): Flow<List<CardRelationEntity>>

    @Query("SELECT * FROM card_relations WHERE (sourceCardId = :cardId OR targetCardId = :cardId) AND status != 'REJECTED' ORDER BY updatedAt DESC")
    suspend fun relations(cardId: Long): List<CardRelationEntity>

    @Query("""SELECT c.* FROM local_cards c JOIN sync_metadata s ON s.cardId = c.id
        WHERE c.deletedAt IS NULL AND s.aiProvider IS NOT NULL AND s.encryptedClientAnalysis IS NULL
        AND (s.uploadState = 'READY' OR s.uploadState = 'RETRYABLE_ERROR') ORDER BY c.capturedAt""")
    suspend fun pendingAiCards(): List<LocalCardEntity>
}
