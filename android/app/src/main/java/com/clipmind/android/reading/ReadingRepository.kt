package com.clipmind.android.reading

import androidx.room.withTransaction
import com.clipmind.android.data.*
import com.clipmind.android.domain.FilterResult
import com.clipmind.android.domain.LocalSafetyFilter
import com.clipmind.android.knowledge.KnowledgeFailure
import com.clipmind.android.network.CaptureApi
import com.clipmind.android.network.dto.ClientAnalysis
import com.clipmind.android.security.TextCipher
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

data class ReadingPlan(val card: LocalCard, val request: ReadingRequest)
data class PersonalNote(val cardId: Long, val revision: Long, val text: String, val questions: List<String>, val createdAt: Long)
data class ReadBook(val title: String)
data class BookAttribution(val cardId: Long, val revision: Long, val workKey: String, val location: String, val quote: String, val confirmedAt: Long)
data class DocumentView(val id: String, val kind: String, val text: String, val updatedAt: Long, val attribution: BookAttribution? = null)

class ReadingRepository(private val db: ClipMindDatabase, private val cards: LocalCardRepository, private val cipher: TextCipher, private val api: CaptureApi, private val filter: LocalSafetyFilter) {
    val dao = db.readingDao()
    private val gson = Gson()
    suspend fun prepare(id: Long, search: Boolean): ReadingPlan = withContext(Dispatchers.Default) {
        val card = cards.detail(id) ?: throw KnowledgeFailure("SOURCE_CHANGED")
        if (!card.contentAvailable || !ReadingContract.safeText(card.content) || filter.evaluate(card.content, card.sourceApp) != FilterResult.Allowed) throw KnowledgeFailure("UNSAFE_OR_OVER_6000")
        val tags = db.localCardDao().allTags().filter { it.level == 2 && it.status == "confirmed" }.map { it.name }
        if (tags.size > 200) throw KnowledgeFailure("TAG_POOL_OVER_200_MERGE_FIRST")
        val read = readBooks()
        if (read.size > 500) throw KnowledgeFailure("READ_BOOK_LIMIT_500")
        if ((tags + read).any { !com.clipmind.android.knowledge.KnowledgeContract.safeText(it) }) throw KnowledgeFailure("SENSITIVE_METADATA")
        ReadingPlan(card, ReadingRequest(card.content, tags, read, search))
    }
    suspend fun current(plan: ReadingPlan) = db.localCardDao().card(plan.card.id)?.card?.contentRevision == plan.card.contentRevision &&
        db.localCardDao().allTags().filter { it.level == 2 && it.status == "confirmed" }.map { it.name } == plan.request.knownTags && readBooks() == plan.request.readBooks
    suspend fun generate(plan: ReadingPlan, authorization: String?, allowed: () -> Boolean): ClientAnalysis = withContext(Dispatchers.Default) {
        if (!allowed() || !current(plan)) throw KnowledgeFailure("SOURCE_OR_CONSENT_CHANGED")
        val response = api.analyzeReading(authorization, plan.request)
        val result = response.body()
        if (!response.isSuccessful || result == null) throw KnowledgeFailure("READING_HTTP_${response.code()}")
        if (!ReadingContract.valid(result)) throw KnowledgeFailure("INVALID_READING_RESULT")
        val encrypted = cipher.encrypt(gson.toJson(result))
        db.withTransaction {
            if (!allowed() || !current(plan)) throw KnowledgeFailure("SOURCE_OR_CONSENT_CHANGED")
            dao.saveReading(ReadingEntity(plan.card.id, plan.card.contentRevision, encrypted, System.currentTimeMillis()))
            for (suggestion in result.secondaryTags) {
                val normalized = normalizeTag(suggestion.name)
                val existing = db.localCardDao().tagId(normalized)?.let { db.localCardDao().tag(it) }
                val id = existing?.id ?: db.localCardDao().insertTag(TagEntity(name = suggestion.name.trim(), normalizedName = normalized, createdAt = System.currentTimeMillis(), status = "pending"))
                if (id > 0) db.localCardDao().insertCardTag(CardTagRefEntity(plan.card.id, id))
            }
        }
        result
    }
    suspend fun manageTag(id: Long, action: String, value: String = "") = db.withTransaction {
        val tag = db.localCardDao().tag(id) ?: return@withTransaction
        require(tag.level == 2) { "一级分类不可编辑" }
        when (action) {
            "confirm" -> db.localCardDao().updateTag(tag.copy(status = "confirmed"))
            "delete" -> db.localCardDao().deleteTag(id)
            "rename", "merge" -> {
                val name = value.trim(); require(name.isNotBlank() && name.length <= 30 && name !in ReadingContract.PRIMARY)
                val target = db.localCardDao().tagId(normalizeTag(name))
                if (target == id) return@withTransaction
                if (action == "merge") {
                    require(target != null && db.localCardDao().tag(target)?.let { it.level == 2 && it.status == "confirmed" } == true) { "请选择已确认的二级目标标签" }
                    db.localCardDao().copyTagRefs(id, target); db.localCardDao().deleteTag(id)
                } else {
                    require(target == null) { "同名标签已存在，请使用合并" }
                    db.localCardDao().updateTag(tag.copy(name = name, normalizedName = normalizeTag(name)))
                }
            }
        }
    }
    suspend fun readBooks(): List<String> = dao.documents("read_book").mapNotNull { runCatching { gson.fromJson(cipher.decrypt(it.encryptedPayload), ReadBook::class.java).title }.getOrNull() }
    suspend fun addReadBook(title: String) {
        val name = title.trim(); require(name.isNotEmpty() && name.length <= 300)
        if (readBooks().any { it.equals(name, true) }) return
        saveDocument("read_book", gson.toJson(ReadBook(name)))
    }
    suspend fun saveNote(card: LocalCard, text: String, now: Long = System.currentTimeMillis()) {
        require(text.isNotBlank() && text.length <= 10000)
        db.withTransaction {
            if (db.localCardDao().card(card.id)?.card?.contentRevision != card.contentRevision) throw KnowledgeFailure("SOURCE_CHANGED")
            saveDocument("annotation", gson.toJson(PersonalNote(card.id, card.contentRevision, text, card.analysis?.questions.orEmpty(), now)))
        }
    }
    suspend fun confirmBookSource(card: LocalCard, workKey: String, quote: String, location: String) {
        require(quote.length in 20..800 && card.content.contains(quote) && location.isNotBlank() && location.length <= 200)
        require(card.analysis?.books?.any { it.verified && it.openLibraryKey == workKey } == true)
        db.withTransaction {
            if (db.localCardDao().card(card.id)?.card?.contentRevision != card.contentRevision) throw KnowledgeFailure("SOURCE_CHANGED")
            val proof = BookAttribution(card.id,card.contentRevision,workKey,location,quote,System.currentTimeMillis())
            saveDocument("book_source",gson.toJson(proof),"book-source:${card.id}:${workKey.substringAfterLast('/')}")
        }
    }
    suspend fun saveWeek(plan: WeeklyPlan, markdown: String, allowed: () -> Boolean) = db.withTransaction {
        if (!allowed() || plan.cards.any { db.localCardDao().card(it.id)?.card?.contentRevision != it.contentRevision }) throw KnowledgeFailure("SOURCE_OR_CONSENT_CHANGED")
        saveDocument("weekly",markdown,"weekly:${plan.start}")
    }
    suspend fun review(card: LocalCard, rating: Recall, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()) = db.withTransaction {
        if (db.localCardDao().card(card.id)?.card?.contentRevision != card.contentRevision) throw KnowledgeFailure("SOURCE_CHANGED")
        val previous = dao.review(card.id)
        if (previous?.lastReviewDay == ReviewPolicy.day(now, zone).toString()) return@withTransaction
        val current = previous?.takeIf { it.revision == card.contentRevision } ?: ReviewEntity(card.id, card.contentRevision, now)
        dao.saveReview(ReviewPolicy.next(current, rating, now, zone))
    }
    suspend fun saveDocument(kind: String, text: String, id: String = UUID.randomUUID().toString()): String {
        dao.saveDocument(ReaderDocument(id, kind, cipher.encrypt(text), System.currentTimeMillis())); return id
    }
    fun decode(row: ReaderDocument): DocumentView? = runCatching {
        val plain = cipher.decrypt(row.encryptedPayload)
        val attribution = if(row.kind == "book_source") gson.fromJson(plain,BookAttribution::class.java) else null
        val text = when(row.kind) {
            "read_book" -> gson.fromJson(plain, ReadBook::class.java).title
            "annotation" -> gson.fromJson(plain, PersonalNote::class.java).let { "卡片 ${it.cardId} · v${it.revision}\n${it.text}" }
            "recommendation" -> gson.fromJson(plain, RecommendationResponse::class.java).let { result -> "探索主题：${result.focusTopics.joinToString("、")}\n${result.basis}\n" + result.books.joinToString("\n") { "《${it.title}》 · ${it.author}\n${it.reason}\nhttps://openlibrary.org${it.openLibraryKey}" } }
            "book_source" -> attribution!!.let { "人工出处核对：卡片 ${it.cardId} v${it.revision}\n${it.workKey} · ${it.location}\n${it.quote}" }
            else -> plain
        }
        DocumentView(row.id, row.kind, text, row.updatedAt, attribution)
    }.getOrNull()
}

fun normalizeTag(name: String) = name.trim().lowercase(Locale.ROOT)
