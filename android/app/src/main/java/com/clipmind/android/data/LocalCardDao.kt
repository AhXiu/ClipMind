package com.clipmind.android.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalCardDao {
    @Query("SELECT * FROM tags ORDER BY level, status, normalizedName") fun observeTags(): Flow<List<TagEntity>>
    @Query("SELECT * FROM tags ORDER BY normalizedName") suspend fun allTags(): List<TagEntity>
    @Query("SELECT * FROM tags WHERE id = :id") suspend fun tag(id: Long): TagEntity?
    @Update suspend fun updateTag(tag: TagEntity)
    @Query("DELETE FROM tags WHERE id = :id AND level = 2") suspend fun deleteTag(id: Long)
    @Query("INSERT OR IGNORE INTO card_tag_refs(cardId, tagId) SELECT cardId, :target FROM card_tag_refs WHERE tagId = :source") suspend fun copyTagRefs(source: Long, target: Long)
    @Transaction
    @Query("SELECT * FROM local_cards WHERE deletedAt IS NULL ORDER BY capturedAt DESC LIMIT 300")
    suspend fun retrievalCards(): List<LocalCardWithTags>

    @Query("SELECT * FROM card_relations WHERE sourceCardId = :id OR targetCardId = :id")
    suspend fun allRelations(id: Long): List<CardRelationEntity>

    @Update
    suspend fun updateRelation(relation: CardRelationEntity)

    @Insert
    suspend fun insertKnowledgeNote(note: KnowledgeNoteEntity)

    @Query("SELECT * FROM knowledge_notes ORDER BY createdAt DESC LIMIT 50")
    fun observeKnowledgeNotes(): Flow<List<KnowledgeNoteEntity>>

    @Query("DELETE FROM knowledge_notes WHERE id = :id")
    suspend fun deleteKnowledgeNote(id: String)
    @Query("SELECT * FROM sync_metadata")
    fun observeSyncMetadata(): Flow<List<SyncMetadataEntity>>

    @Query("SELECT * FROM sync_metadata WHERE cardId = :cardId")
    suspend fun syncMetadata(cardId: Long): SyncMetadataEntity?

    @Query("""UPDATE sync_metadata SET uploadState = :state, retryCount = 0, nextRetryAt = 0,
        encryptedClientAnalysis = NULL, encryptedServerAnalysis = NULL, lastErrorCode = NULL,
        serverCardStatus = NULL, serverLastError = NULL, aiProvider = :provider, aiModel = :model, updatedAt = :now
        WHERE cardId = :id""")
    suspend fun resetAnalysis(id: Long, state: OutboxState, provider: String?, model: String?, now: Long)

    @Query("UPDATE local_cards SET clientCaptureId = :taskId WHERE id = :id")
    suspend fun setTaskId(id: Long, taskId: String)

    @Query("UPDATE sync_metadata SET uploadState = 'READY', nextRetryAt = 0, lastErrorCode = NULL WHERE cardId IN (:ids) AND uploadState = 'RETRYABLE_ERROR'")
    suspend fun retryAi(ids: List<Long>): Int

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

    @Query("UPDATE local_cards SET encryptedContent = :encryptedContent, hash = :hash, contentRevision = contentRevision + 1, updatedAt = :now WHERE id = :id AND deletedAt IS NULL")
    suspend fun edit(id: Long, encryptedContent: String, hash: String, now: Long): Int

    @Query("UPDATE local_cards SET deletedAt = :now, updatedAt = :now WHERE id = :id AND deletedAt IS NULL")
    suspend fun softDelete(id: Long, now: Long): Int

    @Query("UPDATE card_relations SET status = 'STALE', updatedAt = :now WHERE (sourceCardId = :id OR targetCardId = :id) AND status != 'REJECTED'")
    suspend fun invalidateRelations(id: Long, now: Long)

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

    @Query("""UPDATE card_relations SET status = :status, updatedAt = :now WHERE id = :relationId AND status = 'CANDIDATE'
        AND EXISTS(SELECT 1 FROM local_cards c WHERE c.id = sourceCardId AND c.deletedAt IS NULL AND (sourceRevision = 0 OR c.contentRevision = sourceRevision))
        AND EXISTS(SELECT 1 FROM local_cards c WHERE c.id = targetCardId AND c.deletedAt IS NULL AND (targetRevision = 0 OR c.contentRevision = targetRevision))""")
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
