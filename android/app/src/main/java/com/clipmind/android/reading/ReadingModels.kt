package com.clipmind.android.reading

import androidx.room.*
import com.clipmind.android.knowledge.KnowledgeContract
import com.clipmind.android.network.dto.ClientAnalysis
import com.google.gson.annotations.SerializedName
import java.net.URI
import java.util.Locale

data class ReadingRequest(val text: String, @SerializedName("known_tags") val knownTags: List<String>, @SerializedName("read_books") val readBooks: List<String>, @SerializedName("search_articles") val searchArticles: Boolean)
data class EmbeddingRequest(val texts: List<String>)
data class EmbeddingResponse(val model: String, val vectors: List<List<Double>>)
data class ReadingCapabilities(@SerializedName("embedding_model") val embeddingModel: String, val reading: Boolean, val search: Boolean)
data class RecommendationRequest(val counts: Map<String,Int>, @SerializedName("read_books") val readBooks: List<String>, @SerializedName("previous_books") val previousBooks: List<String>)
data class RecommendationResponse(@SerializedName("focus_topics") val focusTopics: List<String>, val books: List<com.clipmind.android.network.dto.AnalysisBook>, val basis: String)

@Entity(tableName = "card_reading")
data class ReadingEntity(@PrimaryKey val cardId: Long, val revision: Long, val encryptedPayload: String, val updatedAt: Long)
@Entity(tableName = "card_vectors")
data class VectorEntity(@PrimaryKey val cardId: Long, val revision: Long, val model: String, val encryptedVector: String)
@Entity(tableName = "reader_documents")
data class ReaderDocument(@PrimaryKey val id: String, val kind: String, val encryptedPayload: String, val updatedAt: Long)
@Entity(tableName = "review_schedule")
data class ReviewEntity(@PrimaryKey val cardId: Long, val revision: Long, val dueAt: Long, val intervalDays: Int = 0, val repetitions: Int = 0, val ease: Double = 2.5, val lastReviewDay: String = "")

object ReadingContract {
    val PRIMARY = setOf("人文", "商业", "技术", "认知", "职场", "社会", "随笔")
    fun valid(a: ClientAnalysis, sourceText: String? = null): Boolean = runCatching {
        require(a.schemaVersion == 2 && a.primaryTag in PRIMARY && a.provider.isNotBlank() && a.model.isNotBlank())
        require(listOf(a.interpretation.summary, a.interpretation.insight, a.interpretation.action).all { it.isNotBlank() && it.length <= 8000 })
        require(a.secondaryTags.size <= 5 && a.secondaryTags.all { it.name.isNotBlank() && it.name.length <= 60 && it.name !in PRIMARY && it.status in setOf("reused", "pending") })
        require(a.keywords.size == 3 && a.keywords.all { it.isNotBlank() && it.length <= 60 } && a.keywords.map { it.trim().lowercase(Locale.ROOT) }.distinct().size == 3 && a.value in setOf("high", "medium", "low"))
        require(a.questions.size <= 3 && (a.value != "high" || a.questions.size >= 2) && a.questions.all { it.isNotBlank() && it.length <= 400 })
        require(a.books.size <= 3 && a.books.all { it.title.isNotBlank() && it.title.length <= 600 && it.author.length <= 400 && it.reason.length <= 600 && it.verified && it.openLibraryKey?.matches(Regex("/works/OL[0-9]+W")) == true && it.confidence in setOf("semantic", "speculative") })
        require(a.articles.size <= 3 && a.articles.all { validArticleUrl(it.url) && it.title.isNotBlank() && it.title.length <= 600 && it.summary.isNotBlank() && it.summary.length <= 400 && it.checkedAt.isNotBlank() })
        require(a.articles.map { it.url }.distinct().size == a.articles.size)
        a.articles.forEach { article ->
            val enriched = listOf(article.relation, article.reason, article.quote, article.sourceQuote).any { it != null }
            if (enriched) {
                require(article.relation in setOf("supports", "contradicts", "extends", "example"))
                require(!article.reason.isNullOrBlank() && article.reason.codePointCount(0, article.reason.length) <= 200)
                require(!article.quote.isNullOrBlank() && article.quote.trim().let { it.codePointCount(0, it.length) } in 20..200)
                require(!article.sourceQuote.isNullOrBlank() && article.sourceQuote.codePointCount(0, article.sourceQuote.length) <= 200)
                if (sourceText != null) {
                    require(KnowledgeContract.claimQuoteValid(sourceText, article.sourceQuote, 200))
                }
            }
        }
        true
    }.getOrDefault(false)
    fun validArticleUrl(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.rawUserInfo == null && uri.port == -1 && uri.host in setOf("mp.weixin.qq.com", "zhuanlan.zhihu.com", "sspai.com", "infoq.cn", "www.infoq.cn")
    }.getOrDefault(false)
    fun safeText(text: String) = text.isNotBlank() && text.codePointCount(0, text.length) <= 6000 && KnowledgeContract.safeText(text)
}

@Dao
interface ReadingDao {
    @Query("SELECT * FROM card_reading") fun observeReading(): kotlinx.coroutines.flow.Flow<List<ReadingEntity>>
    @Query("SELECT * FROM card_reading WHERE cardId = :id") suspend fun reading(id: Long): ReadingEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveReading(row: ReadingEntity)
    @Query("SELECT * FROM card_vectors") suspend fun vectors(): List<VectorEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveVector(row: VectorEntity)
    @Query("SELECT * FROM reader_documents ORDER BY updatedAt DESC") fun observeDocuments(): kotlinx.coroutines.flow.Flow<List<ReaderDocument>>
    @Query("SELECT * FROM reader_documents WHERE kind = :kind ORDER BY updatedAt DESC") suspend fun documents(kind: String): List<ReaderDocument>
    @Query("SELECT * FROM reader_documents WHERE id = :id") suspend fun document(id: String): ReaderDocument?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveDocument(row: ReaderDocument)
    @Query("DELETE FROM reader_documents WHERE id = :id") suspend fun deleteDocument(id: String)
    @Query("SELECT * FROM review_schedule") fun observeReviews(): kotlinx.coroutines.flow.Flow<List<ReviewEntity>>
    @Query("SELECT * FROM review_schedule WHERE cardId = :id") suspend fun review(id: Long): ReviewEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveReview(row: ReviewEntity)
}
