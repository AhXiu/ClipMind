package com.clipmind.android.reading

import androidx.room.withTransaction
import com.clipmind.android.data.ClipMindDatabase
import com.clipmind.android.domain.FilterResult
import com.clipmind.android.domain.LocalSafetyFilter
import com.clipmind.android.knowledge.KnowledgeCard
import com.clipmind.android.knowledge.KnowledgeFailure
import com.clipmind.android.network.CaptureApi
import com.clipmind.android.security.TextCipher
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

data class SemanticPlan(val sourceId: Long, val model: String, val cards: List<KnowledgeCard>, val pending: List<KnowledgeCard>)
data class SemanticMatch(val card: KnowledgeCard, val score: Double)
data class IndexedVector(val cardId: Long, val revision: Long, val model: String, val values: List<Double>)

fun rankSemantic(cards: List<KnowledgeCard>, sourceId: Long, model: String, vectors: List<IndexedVector>, excluded: Set<Long>): List<SemanticMatch> {
    val sourceCard = cards.firstOrNull { it.id.toLong() == sourceId } ?: throw KnowledgeFailure("SOURCE_CHANGED")
    val rows = vectors.filter { it.model == model }.associateBy { it.cardId }
    val source = rows[sourceId]?.takeIf { it.revision == sourceCard.revision }?.values ?: throw KnowledgeFailure("SOURCE_VECTOR_MISSING")
    val candidates = cards.filter { it.id.toLong() != sourceId && it.id.toLong() !in excluded }.mapNotNull { card ->
        val row = rows[card.id.toLong()]?.takeIf { it.revision == card.revision } ?: return@mapNotNull null
        if (source.size != row.values.size) throw KnowledgeFailure("INDEX_DIMENSION_CHANGED")
        SemanticMatch(card,VectorMath.cosine(source,row.values))
    }.filter { it.score > 0.0 }
        .sortedWith(compareByDescending<SemanticMatch> { it.score }.thenBy { it.card.id.toLong() }).take(20).toMutableList()
    // Preserve the closest two, then prefer complementary candidates within the
    // semantic shortlist. Neither cosine nor diversity determines viewpoint polarity.
    val selected = candidates.take(2).toMutableList()
    candidates.removeAll(selected.toSet())
    while (selected.size < 5 && candidates.isNotEmpty()) {
        fun utility(match: SemanticMatch): Double {
            val vector = rows.getValue(match.card.id.toLong()).values
            val redundancy = selected.maxOf { VectorMath.cosine(vector, rows.getValue(it.card.id.toLong()).values) }
            return 0.7 * match.score - 0.3 * redundancy
        }
        val next = candidates.sortedWith(compareByDescending<SemanticMatch> { utility(it) }
            .thenByDescending { it.score }.thenBy { it.card.id.toLong() }).first()
        selected += next
        candidates.remove(next)
    }
    return selected
}

object VectorMath {
    fun valid(v: List<Double>) = v.size in 8..4096 && v.all { it.isFinite() } && v.any { it != 0.0 }
    fun cosine(a: List<Double>, b: List<Double>): Double {
        require(valid(a) && valid(b) && a.size == b.size)
        val normA = sqrt(a.sumOf { it * it }); val normB = sqrt(b.sumOf { it * it })
        require(normA.isFinite() && normB.isFinite())
        return a.indices.sumOf { (a[it] / normA) * (b[it] / normB) }.coerceIn(-1.0, 1.0)
    }
}

class SemanticIndex(private val db: ClipMindDatabase, private val cipher: TextCipher, private val api: CaptureApi, private val filter: LocalSafetyFilter) {
    private val gson = Gson()
    suspend fun prepare(sourceId: Long, authorization: String?): SemanticPlan = withContext(Dispatchers.Default) {
        val response = api.readingCapabilities(authorization)
        val model = response.body()?.embeddingModel.orEmpty()
        if (!response.isSuccessful || model.isBlank()) throw KnowledgeFailure("EMBEDDING_NOT_CONFIGURED")
        val rows = db.localCardDao().activeCards()
        if (rows.size > 2000) throw KnowledgeFailure("INDEX_LIMIT_2000_CARDS")
        val cards = rows.map { row ->
            val text = cipher.decrypt(row.encryptedContent)
            if (!ReadingContract.safeText(text) || filter.evaluate(text, row.sourceApp) != FilterResult.Allowed) throw KnowledgeFailure("INDEX_SOURCE_UNSAFE_OR_OVER_6000")
            KnowledgeCard(row.id.toString(), row.contentRevision, text)
        }
        if (cards.none { it.id == sourceId.toString() }) throw KnowledgeFailure("SOURCE_CHANGED")
        val cached = db.readingDao().vectors().associateBy { it.cardId }
        val pending = cards.filter { card -> cached[card.id.toLong()]?.let { it.model != model || it.revision != card.revision || decode(it) == null } != false }
        SemanticPlan(sourceId, model, cards, pending)
    }

    suspend fun current(plan: SemanticPlan): Boolean = db.withTransaction {
        val current = db.localCardDao().activeCards().associate { it.id.toString() to it.contentRevision }
        current == plan.cards.associate { it.id to it.revision }
    }

    suspend fun buildAndFind(plan: SemanticPlan, authorization: String?, allowed: () -> Boolean): List<SemanticMatch> = withContext(Dispatchers.Default) {
        if (!allowed() || !current(plan)) throw KnowledgeFailure("SOURCE_OR_CONSENT_CHANGED")
        val capabilities = api.readingCapabilities(authorization).body()
        if (capabilities?.embeddingModel != plan.model) throw KnowledgeFailure("EMBEDDING_MODEL_CHANGED")
        // Batch by both count and Unicode length; no silent truncation of source text.
        val batches = mutableListOf<MutableList<KnowledgeCard>>()
        plan.pending.forEach { card ->
            val last = batches.lastOrNull()
            if (last == null || last.size == 16 || last.sumOf { it.text.codePointCount(0, it.text.length) } + card.text.codePointCount(0, card.text.length) > 16000) batches += mutableListOf(card)
            else last += card
        }
        for (batch in batches) {
            if (!allowed() || !current(plan)) throw KnowledgeFailure("SOURCE_OR_CONSENT_CHANGED")
            val response = api.embedReading(authorization, EmbeddingRequest(batch.map { it.text }))
            val result = response.body()
            if (!response.isSuccessful || result == null) throw KnowledgeFailure("EMBEDDING_HTTP_${response.code()}")
            if (result.model != plan.model || result.vectors.size != batch.size || result.vectors.any { !VectorMath.valid(it) } || result.vectors.map { it.size }.distinct().size != 1) throw KnowledgeFailure("INVALID_EMBEDDING")
            val encrypted = result.vectors.map { cipher.encrypt(gson.toJson(it)) }
            db.withTransaction {
                if (!allowed() || !current(plan)) throw KnowledgeFailure("SOURCE_OR_CONSENT_CHANGED")
                batch.forEachIndexed { i, card -> db.readingDao().saveVector(VectorEntity(card.id.toLong(), card.revision, plan.model, encrypted[i])) }
            }
        }
        if (!allowed() || !current(plan)) throw KnowledgeFailure("SOURCE_OR_CONSENT_CHANGED")
        val rows = db.readingDao().vectors().filter { it.model == plan.model }
        val excluded = db.localCardDao().allRelations(plan.sourceId).filter { it.status in setOf(com.clipmind.android.data.RelationStatus.REJECTED, com.clipmind.android.data.RelationStatus.CONFIRMED) }
            .map { if (it.sourceCardId == plan.sourceId) it.targetCardId else it.sourceCardId }.toSet()
        rankSemantic(plan.cards,plan.sourceId,plan.model,rows.map { IndexedVector(it.cardId,it.revision,it.model,decode(it) ?: throw KnowledgeFailure("INDEX_CORRUPT")) },excluded)
    }

    private fun decode(row: VectorEntity): List<Double>? = runCatching {
        gson.fromJson(cipher.decrypt(row.encryptedVector), DoubleArray::class.java).toList().takeIf(VectorMath::valid)
    }.getOrNull()
}
