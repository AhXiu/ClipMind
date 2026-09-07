package com.clipmind.android.data

import androidx.room.withTransaction
import com.clipmind.android.domain.CaptureHash
import com.clipmind.android.export.ExportSnapshot
import com.clipmind.android.security.TextCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.Locale

/** Local-first card API. Cloud ids and AI output remain optional metadata. */
data class LocalCard(
    val id: Long,
    val content: String,
    val contentAvailable: Boolean,
    val capturedAt: Long,
    val updatedAt: Long,
    val sourceApp: String?,
    val tags: List<TagEntity>,
    val sourceUrl: String? = null,
    val mode: CaptureMode = CaptureMode.AUTO,
    val sync: SyncMetadataEntity? = null,
)

class LocalCardRepository(
    private val db: ClipMindDatabase,
    private val cipher: TextCipher,
) {
    private val dao = db.localCardDao()

    fun observeCards(): Flow<List<LocalCard>> = combine(
        dao.observeCards(), dao.observeSyncMetadata(),
    ) { cards, metadata ->
        val syncByCard = metadata.associateBy { it.cardId }
        cards.map { decrypt(it, syncByCard[it.card.id]) }
    }.flowOn(Dispatchers.Default)

    suspend fun detail(id: Long): LocalCard? = withContext(Dispatchers.Default) {
        dao.card(id)?.let { decrypt(it, dao.syncMetadata(id)) }
    }

    suspend fun edit(id: Long, content: String, now: Long = System.currentTimeMillis()): Boolean {
        val normalized = CaptureHash.normalize(content)
        return dao.edit(id, cipher.encrypt(normalized), CaptureHash.sha256(normalized), now) == 1
    }

    suspend fun softDelete(id: Long, now: Long = System.currentTimeMillis()): Boolean =
        dao.softDelete(id, now) == 1

    suspend fun softDelete(ids: Set<Long>, now: Long = System.currentTimeMillis()): Int =
        db.withTransaction { ids.count { dao.softDelete(it, now) == 1 } }

    suspend fun addTag(cardIds: Set<Long>, name: String, now: Long = System.currentTimeMillis()): Int =
        cardIds.count { addTag(it, name, now) }

    suspend fun queueAi(cardIds: Set<Long>, now: Long = System.currentTimeMillis()): Int =
        if (cardIds.isEmpty()) 0 else dao.queueAi(cardIds.toList(), now)

    suspend fun syncMetadata(id: Long): SyncMetadataEntity? = dao.syncMetadata(id)

    /** Search intentionally decrypts in memory; plaintext is never indexed or persisted. */
    suspend fun search(query: String): List<LocalCard> = withContext(Dispatchers.Default) {
        val needle = query.trim()
        if (needle.isEmpty()) return@withContext emptyList()
        searchDecryptedCards(dao.activeCards(), needle, cipher)
    }

    suspend fun addTag(cardId: Long, name: String, now: Long = System.currentTimeMillis()): Boolean {
        val displayName = name.trim()
        if (displayName.isEmpty()) return false
        val normalized = displayName.lowercase(Locale.ROOT)
        return db.withTransaction {
            val inserted = dao.insertTag(TagEntity(name = displayName, normalizedName = normalized, createdAt = now))
            val tagId = inserted.takeIf { it != -1L } ?: dao.tagId(normalized) ?: return@withTransaction false
            dao.insertCardTag(CardTagRefEntity(cardId, tagId)) != -1L
        }
    }

    suspend fun removeTag(cardId: Long, tagId: Long): Boolean = dao.removeCardTag(cardId, tagId) == 1

    suspend fun addRelationCandidate(sourceCardId: Long, targetCardId: Long, type: String, now: Long = System.currentTimeMillis()): Long {
        require(sourceCardId != targetCardId) { "A card cannot relate to itself" }
        return dao.insertRelation(CardRelationEntity(
            sourceCardId = sourceCardId, targetCardId = targetCardId, relationType = type.trim(),
            createdAt = now, updatedAt = now,
        ))
    }

    suspend fun confirmRelation(id: Long, now: Long = System.currentTimeMillis()): Boolean =
        dao.resolveRelation(id, RelationStatus.CONFIRMED, now) == 1

    suspend fun ignoreRelation(id: Long, now: Long = System.currentTimeMillis()): Boolean =
        dao.resolveRelation(id, RelationStatus.REJECTED, now) == 1

    fun observeRelations(cardId: Long): Flow<List<CardRelationEntity>> = dao.observeRelations(cardId)
    suspend fun relations(cardId: Long): List<CardRelationEntity> = dao.relations(cardId)

    suspend fun exportSnapshot(): ExportSnapshot = withContext(Dispatchers.Default) {
        val cards = dao.exportCards().map { value ->
            val text = cipher.decrypt(value.card.encryptedContent)
            LocalCard(
                value.card.id, text, true, value.card.capturedAt, value.card.updatedAt,
                value.card.sourceApp, value.tags, value.card.sourceUrl, value.card.mode,
                dao.syncMetadata(value.card.id),
            )
        }
        ExportSnapshot(cards, dao.confirmedRelations())
    }

    suspend fun pendingAiTasks(): List<LocalCard> = withContext(Dispatchers.Default) {
        dao.pendingAiCards().map { card ->
            val text = runCatching { cipher.decrypt(card.encryptedContent) }.getOrNull()
            LocalCard(card.id, text ?: "内容无法解密", text != null, card.capturedAt, card.updatedAt, card.sourceApp, emptyList())
        }
    }

    private fun decrypt(value: LocalCardWithTags, sync: SyncMetadataEntity? = null): LocalCard {
        val text = runCatching { cipher.decrypt(value.card.encryptedContent) }.getOrNull()
        return LocalCard(
            id = value.card.id, content = text ?: "内容无法解密", contentAvailable = text != null,
            capturedAt = value.card.capturedAt, updatedAt = value.card.updatedAt,
            sourceApp = value.card.sourceApp, tags = value.tags, sourceUrl = value.card.sourceUrl,
            mode = value.card.mode, sync = sync,
        )
    }
}

internal fun searchDecryptedCards(cards: List<LocalCardEntity>, query: String, cipher: TextCipher): List<LocalCard> =
    cards.mapNotNull { card ->
        val text = runCatching { cipher.decrypt(card.encryptedContent) }.getOrNull() ?: return@mapNotNull null
        card.takeIf { text.contains(query, ignoreCase = true) }?.let {
            LocalCard(it.id, text, true, it.capturedAt, it.updatedAt, it.sourceApp, emptyList())
        }
    }
