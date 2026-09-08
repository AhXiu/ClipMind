package com.clipmind.android.ui

import com.clipmind.android.AppContainer
import com.clipmind.android.data.LocalCard
import com.clipmind.android.knowledge.*
import com.clipmind.android.network.BatchRequestMetadata
import com.clipmind.android.reading.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.ZoneId

sealed interface LearningPreview {
    data class Analysis(val plan: ReadingPlan): LearningPreview
    data class Semantic(val plan: SemanticPlan): LearningPreview
    data class Week(val plan: WeeklyPlan): LearningPreview
    data class Notion(val plan: NotionPlan): LearningPreview
    data class Recommend(val request: RecommendationRequest): LearningPreview
}
data class LearningUiState(val busy: Boolean = false, val progress: String = "", val preview: LearningPreview? = null, val matches: List<SemanticMatch> = emptyList(), val annotationDrafts: Map<String,String> = emptyMap(), val matchSourceKey: String? = null)

class LearningController(private val scope: CoroutineScope, private val container: AppContainer, private val message: (String) -> Unit) {
    private val mutable = MutableStateFlow(LearningUiState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var expectedConfig = container.settings.captureAiConfiguration()
    private var expectedAuth: String? = null
    private fun auth() = BatchRequestMetadata.authorizationHeader(container.tokenStore.readToken().orEmpty())
    private fun allowed() = container.settings.aiEnabled.value && expectedConfig == container.settings.captureAiConfiguration() && expectedAuth == auth()
    private fun run(block: suspend () -> Unit) {
        if (job?.isActive == true) { message("已有任务处理中，请等待或取消后再操作"); return }
        mutable.update { it.copy(busy = true) }
        job = scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { message(if (e is KnowledgeFailure) e.code else "操作未完成，请检查配置或输入") }
            finally { mutable.update { it.copy(busy = false, progress = "") } }
        }
    }
    private fun captureConfig() {
        if (!container.settings.aiEnabled.value) throw KnowledgeFailure("AI_DISABLED")
        expectedConfig = container.settings.captureAiConfiguration(); expectedAuth = auth()
    }
    fun prepareAnalysis(id: Long, search: Boolean = false) = run {
        captureConfig(); mutable.update { it.copy(preview = null) }
        val plan = container.readingRepository.prepare(id,search)
        mutable.update { it.copy(preview = LearningPreview.Analysis(plan)) }
    }
    fun prepareSemantic(id: Long) = run {
        captureConfig(); mutable.update { it.copy(preview = null, matches = emptyList()) }
        val plan = container.semanticIndex.prepare(id,expectedAuth)
        mutable.update { it.copy(preview = LearningPreview.Semantic(plan)) }
    }
    fun prepareWeek(cards: List<LocalCard>) = run {
        captureConfig()
        val plan = weeklyPlan(cards,System.currentTimeMillis(),ZoneId.systemDefault(),false)
        mutable.update { it.copy(preview = LearningPreview.Week(plan)) }
    }
    fun prepareNotion(title: String, text: String) = run {
        val page = container.learningSettings.state.value.notionPage
        if (page.isBlank() || !container.notionKeyStore.configured.value) throw KnowledgeFailure("NOTION_CONFIG_REQUIRED")
        java.util.UUID.fromString(page)
        if (text.length > 100000 || !KnowledgeContract.safeText(text)) throw KnowledgeFailure("NOTION_CONTENT_LIMIT_OR_SENSITIVE")
        mutable.update { it.copy(preview = LearningPreview.Notion(NotionPlan(page,title,text))) }
    }
    fun prepareRecommendations(cards: List<LocalCard>) = run {
        captureConfig()
        val previous = container.readingRepository.dao.documents("recommendation").flatMap { row ->
            runCatching { com.google.gson.Gson().fromJson(container.textCipher.decrypt(row.encryptedPayload),RecommendationResponse::class.java).books.map { it.title } }.getOrDefault(emptyList())
        }.distinct()
        val read = container.readingRepository.readBooks()
        if (previous.size + read.size > 1000 || (previous + read).any { !KnowledgeContract.safeText(it) }) throw KnowledgeFailure("PROFILE_LIMIT_OR_SENSITIVE")
        val counts = cards.mapNotNull { it.analysis?.primaryTag?.takeIf { tag -> tag in ReadingContract.PRIMARY } }.groupingBy { it }.eachCount()
        mutable.update { it.copy(preview = LearningPreview.Recommend(RecommendationRequest(counts,read,previous))) }
    }
    fun dismiss() { mutable.update { it.copy(preview = null) } }
    fun cancel() { job?.cancel(); dismiss(); message("已取消等待；服务商已接受的请求可能仍产生费用") }
    fun confirm() {
        val preview = mutable.value.preview ?: return
        if (mutable.value.busy) return
        dismiss()
        run {
            if (!LearningTaskGate.tryAcquire()) throw KnowledgeFailure("LEARNING_TASK_ALREADY_RUNNING")
            try {
            if (preview !is LearningPreview.Notion && !allowed()) throw KnowledgeFailure("CONFIG_CHANGED_CONFIRM_AGAIN")
            when(preview) {
                is LearningPreview.Recommend -> {
                    val response = container.api.recommendReading(expectedAuth,preview.request)
                    val result = response.body()
                    if (!response.isSuccessful || result == null || result.books.size > 2 || result.focusTopics.size != 2 || result.focusTopics.any { it !in ReadingContract.PRIMARY } || result.books.any { !it.verified || it.openLibraryKey?.matches(Regex("/works/OL[0-9]+W")) != true || it.topic !in result.focusTopics }) throw KnowledgeFailure("RECOMMENDATION_FAILED")
                    if (!allowed()) throw KnowledgeFailure("CONFIG_CHANGED")
                    container.readingRepository.saveDocument("recommendation",com.google.gson.Gson().toJson(result))
                    message(if(result.books.isEmpty()) "没有通过真实性验证且未重复的候选；不编造推荐" else "低覆盖主题推荐已保存")
                }
                is LearningPreview.Analysis -> {
                    container.readingRepository.generate(preview.plan,expectedAuth,::allowed)
                    message("完整解读已加密保存；新增标签等待确认")
                }
                is LearningPreview.Semantic -> {
                    val plan = preview.plan
                    mutable.update { it.copy(progress = "生成或复用本地向量索引") }
                    val matches = container.semanticIndex.buildAndFind(plan,expectedAuth,::allowed)
                    mutable.update { it.copy(matches = matches,matchSourceKey="${plan.sourceId}:${plan.cards.first { c -> c.id == plan.sourceId.toString() }.revision}") }
                    val source = plan.cards.first { it.id == plan.sourceId.toString() }
                    for ((i, match) in matches.withIndex()) {
                        if (!allowed() || !container.semanticIndex.current(plan)) throw KnowledgeFailure("SOURCE_OR_CONSENT_CHANGED")
                        mutable.update { it.copy(progress = "LLM 判断 ${i+1}/${matches.size}") }
                        val input = KnowledgeRequest(listOf(source,match.card))
                        val result = container.knowledgeClient.generate(input,expectedConfig,container.apiKeyStore.readForAuthorization(),expectedAuth)
                        if (!allowed()) throw KnowledgeFailure("CONFIG_CHANGED")
                        container.knowledgeRepository.save(input,result)
                    }
                    message("已对 ${matches.size} 张语义召回卡片进行判断；无充分证据时不建立关系")
                }
                is LearningPreview.Week -> {
                    val plan = preview.plan
                    val markdown = generateWeek(container.api,expectedAuth,plan)
                    container.readingRepository.saveWeek(plan,markdown,::allowed)
                    message("周报已保存至本地知识库，可在复习页查看和导出")
                }
                is LearningPreview.Notion -> {
                    val plan = preview.plan
                    if (plan.page != container.learningSettings.state.value.notionPage) throw KnowledgeFailure("NOTION_CONFIG_CHANGED")
                    val fingerprint = com.clipmind.android.domain.CaptureHash.sha256(plan.page + plan.text)
                    val id = "notion-transfer:$fingerprint"
                    if (container.readingRepository.dao.document(id) != null) throw KnowledgeFailure("NOTION_ALREADY_SENT_OR_UNCERTAIN_CHECK_REMOTE")
                    val key = container.notionKeyStore.readForAuthorization() ?: throw KnowledgeFailure("NOTION_KEY_MISSING")
                    container.readingRepository.saveDocument("transfer","Notion请求已开始；如结果未知，请先核对远端以免重复创建。",id)
                    val page = NotionClient().send(plan,key)
                    container.readingRepository.saveDocument("transfer","已创建 Notion 页面 $page",id)
                    message("已创建 Notion 页面；本地仍保留独立副本")
                }
            }
            } finally { LearningTaskGate.release() }
        }
    }
    fun tag(id: Long, action: String, name: String = "") = run { container.readingRepository.manageTag(id,action,name) }
    fun readBook(title: String) = run { container.readingRepository.addReadBook(title) }
    fun deleteDocument(id: String) = run {
        val document = container.readingRepository.dao.document(id) ?: return@run
        if (document.kind !in setOf("read_book", "annotation", "weekly", "recommendation", "book_source", "task_error")) throw KnowledgeFailure("DOCUMENT_RETENTION_PROTECTED")
        container.readingRepository.dao.deleteDocument(id)
    }
    fun draft(card: LocalCard, text: String) {
        val key = "${card.id}:${card.contentRevision}"
        if (text.length > 10000 || (key !in mutable.value.annotationDrafts && mutable.value.annotationDrafts.size >= 20)) { message("请先保存已有草稿；单条批注最多10000字符"); return }
        mutable.update { it.copy(annotationDrafts = if(text.isEmpty()) it.annotationDrafts-key else it.annotationDrafts+(key to text)) }
    }
    fun saveNote(card: LocalCard, text: String) = run {
        container.readingRepository.saveNote(card,text)
        val key = "${card.id}:${card.contentRevision}"
        mutable.update { if(it.annotationDrafts[key] == text) it.copy(annotationDrafts=it.annotationDrafts-key) else it }
        message("个人批注已加密保存，原文未修改")
    }
    fun bookSource(card: LocalCard, workKey: String, quote: String, location: String) = run { container.readingRepository.confirmBookSource(card,workKey,quote,location); message("已保存人工核对的出处证据；编辑原文后需重新核对") }
    fun review(card: LocalCard, recall: Recall) = run { container.readingRepository.review(card,recall) }
}
