package com.clipmind.android.knowledge

import androidx.room.withTransaction
import com.clipmind.android.data.*
import com.clipmind.android.domain.LocalSafetyFilter
import com.clipmind.android.domain.FilterResult
import com.clipmind.android.security.TextCipher
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.UUID

interface KnowledgeStore {
    suspend fun prepare(ids: Set<Long>): KnowledgeRequest
    suspend fun isCurrent(input: KnowledgeRequest): Boolean
    suspend fun save(input: KnowledgeRequest, response: KnowledgeResponse): String
    fun observeNotes(): Flow<List<KnowledgeNote>>
    suspend fun deleteNote(id: String)
    suspend fun discover(sourceId: Long): Int
}

class KnowledgeRepository(private val db: ClipMindDatabase, private val cipher: TextCipher, private val filter: LocalSafetyFilter) : KnowledgeStore {
    private val dao = db.localCardDao()
    private val gson = Gson()

    override suspend fun prepare(ids: Set<Long>): KnowledgeRequest = withContext(Dispatchers.Default) {
        if (ids.size !in 2..8) throw KnowledgeFailure("SELECT_2_TO_8_CARDS")
        val rows = db.withTransaction { ids.sorted().map { dao.card(it) ?: throw KnowledgeFailure("SOURCE_CHANGED") } }
        val input = KnowledgeRequest(rows.map { value ->
            val card = value.card
            val text = cipher.decrypt(card.encryptedContent)
            if (filter.evaluate(text, card.sourceApp) != FilterResult.Allowed || !KnowledgeContract.safeText(text)) throw KnowledgeFailure("SENSITIVE_CONTENT")
            KnowledgeCard(card.id.toString(), card.contentRevision, text)
        })
        if (!KnowledgeContract.validInput(input)) throw KnowledgeFailure("CONTENT_LIMIT_EXCEEDED")
        input
    }

    override suspend fun isCurrent(input: KnowledgeRequest): Boolean = db.withTransaction {
        input.cards.all { source -> dao.card(source.id.toLong())?.card?.contentRevision == source.revision }
    }

    override suspend fun save(input: KnowledgeRequest, response: KnowledgeResponse): String = withContext(Dispatchers.Default) {
        if (!KnowledgeContract.validResponse(input, response)) throw KnowledgeFailure("INVALID_CITATIONS")
        val id = UUID.randomUUID().toString()
        val payload = cipher.encrypt(gson.toJson(KnowledgeNotePayload(input, response)))
        db.withTransaction {
            if (!isCurrent(input)) throw KnowledgeFailure("SOURCE_CHANGED")
            dao.insertKnowledgeNote(KnowledgeNoteEntity(id, payload, System.currentTimeMillis()))
            val cards = input.cards.associateBy { it.id }
            response.result.relations.forEach { r ->
                saveCandidate(cards.getValue(r.sourceId), cards.getValue(r.targetId), r.type,
                    RelationEvidence(r.reason, r.sourceQuote, r.targetQuote), "llm")
            }
        }
        id
    }

    override fun observeNotes() = combine(dao.observeKnowledgeNotes(), dao.observeCards()) { notes, cards ->
        val versions = cards.associate { it.card.id.toString() to it.card.contentRevision }
        notes.map { row ->
            val payload = runCatching {
                gson.fromJson(cipher.decrypt(row.encryptedPayload), KnowledgeNotePayload::class.java).also {
                    require(KnowledgeContract.validResponse(it.input, it.response))
                }
            }.getOrNull()
            KnowledgeNote(row.id, row.createdAt, payload, payload == null || payload.input.cards.any { versions[it.id] != it.revision })
        }
    }.flowOn(Dispatchers.Default)

    override suspend fun deleteNote(id: String) = dao.deleteKnowledgeNote(id)

    override suspend fun discover(sourceId: Long): Int = withContext(Dispatchers.Default) {
        val source = dao.card(sourceId)?.card ?: throw KnowledgeFailure("SOURCE_CHANGED")
        val sourceCard = KnowledgeCard(source.id.toString(), source.contentRevision, cipher.decrypt(source.encryptedContent))
        val excluded = dao.allRelations(sourceId).filter { it.status in setOf(RelationStatus.REJECTED, RelationStatus.CONFIRMED) }
            .map { if (it.sourceCardId == sourceId) it.targetCardId.toString() else it.sourceCardId.toString() }.toSet()
        val candidates = dao.retrievalCards().mapNotNull { row -> runCatching {
            KnowledgeCard(row.card.id.toString(), row.card.contentRevision, cipher.decrypt(row.card.encryptedContent))
        }.getOrNull() }
        val found = LocalRelationFinder.find(sourceCard, candidates, excluded)
        db.withTransaction { found.count { saveCandidate(it.source, it.target, "same_topic", it.evidence, "local_overlap") } }
    }

    private suspend fun saveCandidate(a: KnowledgeCard, b: KnowledgeCard, type: String, evidence: RelationEvidence, origin: String): Boolean {
        var source = a; var target = b; var proof = evidence
        if (type in setOf("same_topic", "contradicts") && a.id.toLong() > b.id.toLong()) {
            source = b; target = a; proof = evidence.copy(sourceQuote = evidence.targetQuote, targetQuote = evidence.sourceQuote)
        }
        val sourceId = source.id.toLong(); val targetId = target.id.toLong()
        if (dao.card(sourceId)?.card?.contentRevision != source.revision || dao.card(targetId)?.card?.contentRevision != target.revision) return false
        val existing = dao.allRelations(sourceId).filter { (it.sourceCardId == sourceId && it.targetCardId == targetId) || (it.sourceCardId == targetId && it.targetCardId == sourceId) }
        if (existing.any { it.status == RelationStatus.REJECTED }) return false
        val same = existing.firstOrNull { it.sourceCardId == sourceId && it.targetCardId == targetId && it.relationType == type }
        if (same != null && same.status != RelationStatus.STALE) return false
        val now = System.currentTimeMillis()
        val row = CardRelationEntity(id = same?.id ?: 0, sourceCardId = sourceId, targetCardId = targetId, relationType = type,
            createdAt = same?.createdAt ?: now, updatedAt = now, encryptedEvidence = cipher.encrypt(gson.toJson(proof)),
            sourceRevision = source.revision, targetRevision = target.revision, origin = origin)
        if (same == null) return dao.insertRelation(row) != -1L
        dao.updateRelation(row)
        return true
    }

    fun decodeEvidence(relations: List<CardRelationEntity>): Map<Long, RelationEvidence> = relations.mapNotNull { row ->
        runCatching { row.id to gson.fromJson(cipher.decrypt(row.encryptedEvidence ?: return@mapNotNull null), RelationEvidence::class.java) }.getOrNull()
    }.toMap()
}
