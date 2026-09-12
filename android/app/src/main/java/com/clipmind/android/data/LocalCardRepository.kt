package com.clipmind.android.data

import androidx.room.withTransaction
import com.clipmind.android.domain.CaptureHash
import com.clipmind.android.export.ExportSnapshot
import com.clipmind.android.security.TextCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID
import com.clipmind.android.domain.LocalSafetyFilter
import com.clipmind.android.domain.FilterResult
import com.clipmind.android.network.dto.ClientAnalysis
import com.google.gson.Gson

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
    val analysis: ClientAnalysis? = null,
    val contentRevision: Long = 1,
    val bookSources: List<com.clipmind.android.reading.BookAttribution> = emptyList(),
)

class LocalCardRepository(
    private val db: ClipMindDatabase,
    private val cipher: TextCipher,
    private val safetyFilter: LocalSafetyFilter = LocalSafetyFilter(),
) {
    private val dao = db.localCardDao()

    // One Room transaction keeps new content from briefly pairing with an old AI cache.
    fun observeCards(): Flow<List<LocalCard>> = dao.observeCards()
        .map { cards -> cards.map { decrypt(it, it.sync) } }
        .flowOn(Dispatchers.Default)

    suspend fun detail(id: Long): LocalCard? = withContext(Dispatchers.Default) {
        dao.card(id)?.let { decrypt(it, it.sync) }
    }

    suspend fun edit(id: Long, content: String, now: Long = System.currentTimeMillis(), minimumLength: Int = 8): Boolean {
        val normalized = content
        require(safetyFilter.evaluate(normalized, null, minimumLength) == FilterResult.Allowed) { "内容为空、过短或包含敏感信息" }
        val encrypted = withContext(Dispatchers.Default) { cipher.encrypt(normalized) }
        return db.withTransaction {
            val card = dao.card(id) ?: return@withTransaction false
            val sync = dao.syncMetadata(id) ?: return@withTransaction false
            if (!canEditCard(sync.uploadState)) return@withTransaction false
            if (cipher.decrypt(card.card.encryptedContent) == content) return@withTransaction true
            if (dao.edit(id, encrypted, CaptureHash.sha256(normalized), now) != 1) return@withTransaction false
            dao.setTaskId(id, UUID.randomUUID().toString())
            dao.resetAnalysis(id, OutboxState.LOCAL_ONLY, sync.aiProvider, sync.aiModel, now)
            dao.invalidateRelations(id, now)
            true
        }
    }

    suspend fun softDelete(id: Long, now: Long = System.currentTimeMillis()): Boolean =
        dao.softDelete(id, now) == 1

    suspend fun softDelete(ids: Set<Long>, now: Long = System.currentTimeMillis()): Int =
        db.withTransaction { ids.count { dao.softDelete(it, now) == 1 } }

    suspend fun addTag(cardIds: Set<Long>, name: String, now: Long = System.currentTimeMillis()): Int =
        cardIds.count { addTag(it, name, now) }

    suspend fun queueAi(cardIds: Set<Long>, config: AiCaptureConfiguration, now: Long = System.currentTimeMillis()): Int =
        db.withTransaction {
            cardIds.count { id ->
                val sync = dao.syncMetadata(id)
                if (dao.card(id) == null || sync == null || !canQueueAnalysis(sync.uploadState)) false
                else {
                    dao.setTaskId(id, UUID.randomUUID().toString())
                    dao.resetAnalysis(id, OutboxState.READY, config.mode.takeIf { it.isByok }?.providerId, config.model, now)
                    true
                }
            }
        }

    suspend fun retryAi(ids: Set<Long>): Int = if (ids.isEmpty()) 0 else dao.retryAi(ids.toList())

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
            require(displayName.length <= 30) { "标签最多30字" }
            val inserted = dao.insertTag(TagEntity(name = displayName, normalizedName = normalized, createdAt = now, level = if (displayName in com.clipmind.android.reading.ReadingContract.PRIMARY) 1 else 2))
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
        db.withTransaction {
            val sources = db.readingDao().documents("book_source").map { Gson().fromJson(cipher.decrypt(it.encryptedPayload),com.clipmind.android.reading.BookAttribution::class.java) }
            val cards = dao.exportCards().map { value -> decrypt(value, value.sync).also { require(it.contentAvailable) { "卡片无法解密，导出已中止" } }.copy(bookSources=sources.filter { it.cardId == value.card.id && it.revision == value.card.contentRevision }) }
            val relations = dao.confirmedRelations()
            val evidence = relations.mapNotNull { r -> runCatching { r.encryptedEvidence?.let { r.id to Gson().fromJson(cipher.decrypt(it), com.clipmind.android.knowledge.RelationEvidence::class.java) } }.getOrNull() }.toMap()
            val documents = listOf("weekly", "annotation", "topic", "raw_capture").flatMap { kind ->
                db.readingDao().documents(kind).mapNotNull { row ->
                    val plain = cipher.decrypt(row.encryptedPayload)
                    if (kind == "raw_capture" && com.google.gson.JsonParser.parseString(plain).asJsonObject.get("card_id")?.asLong !in cards.map { it.id }) return@mapNotNull null
                    val name = row.id.replace(Regex("[^a-zA-Z0-9_-]"), "_")
                    com.clipmind.android.export.MarkdownFile("${if(kind == "raw_capture") "raw" else "reflections"}/$name.${if(kind == "weekly") "md" else "json"}",plain)
                }
            }
            ExportSnapshot(cards, relations, documents, evidence)
        }
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
            analysis = value.reading?.takeIf { it.revision == value.card.contentRevision }?.let { row -> runCatching { Gson().fromJson(cipher.decrypt(row.encryptedPayload), ClientAnalysis::class.java).takeIf(com.clipmind.android.reading.ReadingContract::valid) }.getOrNull() } ?: decryptAnalysis(sync, cipher), contentRevision = value.card.contentRevision,
        )
    }
}

internal fun canEditCard(state: OutboxState): Boolean = state in setOf(
    OutboxState.LOCAL_ONLY, OutboxState.PENDING_CONFIRMATION, OutboxState.SUCCEEDED, OutboxState.REJECTED,
)

internal fun canQueueAnalysis(state: OutboxState): Boolean = canEditCard(state)

internal fun decryptAnalysis(sync: SyncMetadataEntity?, cipher: TextCipher): ClientAnalysis? = runCatching {
    if (sync?.uploadState in setOf(OutboxState.LOCAL_ONLY, OutboxState.PENDING_CONFIRMATION, OutboxState.DECRYPTION_FAILED)) return null
    val encrypted = sync?.encryptedServerAnalysis ?: sync?.encryptedClientAnalysis ?: return null
    Gson().fromJson(cipher.decrypt(encrypted), ClientAnalysis::class.java)?.takeIf {
        it.primaryTag.isNotBlank() && it.interpretation.summary.isNotBlank() &&
            it.interpretation.insight.isNotBlank() && it.interpretation.action.isNotBlank()
    }
}.getOrNull()

internal fun searchDecryptedCards(cards: List<LocalCardEntity>, query: String, cipher: TextCipher): List<LocalCard> =
    cards.mapNotNull { card ->
        val text = runCatching { cipher.decrypt(card.encryptedContent) }.getOrNull() ?: return@mapNotNull null
        card.takeIf { text.contains(query, ignoreCase = true) }?.let {
            LocalCard(it.id, text, true, it.capturedAt, it.updatedAt, it.sourceApp, emptyList())
        }
    }
