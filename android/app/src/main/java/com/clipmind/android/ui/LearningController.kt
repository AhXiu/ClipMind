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
data class LearningUiState(val busy: Boolean = false, val progress: String = "", val preview: LearningPreview? = null, val matches: List<SemanticMatch> = emptyList(), val annotationDrafts: Map<String,String> = emptyMap(), val matchSourceKey: String? = null, val localBusy: Boolean = false, val draftStatus: Map<String, String> = emptyMap(), val draftsLoaded: Boolean = false, val unreadableDrafts: Set<String> = emptySet())

class LearningController(private val scope: CoroutineScope, private val container: AppContainer, private val message: (String) -> Unit) {
    private val mutable = MutableStateFlow(LearningUiState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private val touchedDrafts = mutableSetOf<String>()
    private val draftVersions = mutableMapOf<String, Long>()
    private val localWrites = OrderedLocalWriter(scope,
        onError = { message("本地保存未完成，内容仍保留在当前页面，请重试") },
        initialize = {
            try {
                restoreDrafts()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message("草稿读取失败，请重试恢复；不要清除应用数据") }
        }
    )
    private suspend fun restoreDrafts() {
        val result = container.readingRepository.drafts()
        val restored = result.values.filterKeys { it !in touchedDrafts }
        mutable.update { it.copy(draftsLoaded = true, annotationDrafts = restored + it.annotationDrafts, unreadableDrafts = result.unreadableKeys,
            draftStatus = restored.keys.associateWith { "草稿已加密保存" } + it.draftStatus) }
        if (result.unreadableKeys.isNotEmpty()) message("${result.unreadableKeys.size} 份草稿无法解密，已保留原数据，不会自动覆盖")
    }
    fun retryDraftRecovery() { localWrites.enqueue { restoreDrafts() } }
    private fun local(block: suspend () -> Unit) {
        if (mutable.value.localBusy) return
        mutable.update { it.copy(localBusy = true) }
        localWrites.enqueue {
            try { block() } finally { mutable.update { it.copy(localBusy = false) } }
        }
    }
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
                        val result = container.knowledgeClient.generate(input,expectedConfig,container.apiKeyStore.readForAuthorization(expectedConfig.mode.providerId),expectedAuth)
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
    fun deleteDocument(id: String) = local {
        val document = container.readingRepository.dao.document(id) ?: return@local
        if (document.kind !in setOf("read_book", "annotation", "weekly", "recommendation", "book_source", "task_error")) throw KnowledgeFailure("DOCUMENT_RETENTION_PROTECTED")
        container.readingRepository.dao.deleteDocument(id)
    }
    fun draft(card: LocalCard, text: String) {
        draftText(NotebookPolicy.draftKey(card.id, card.contentRevision), text)
    }
    fun importManualDraft(text: String) {
        localWrites.enqueue {
            val previous = mutable.value.annotationDrafts["manual"].orEmpty()
            draftText("manual", if (previous.isBlank()) text else "$previous\n\n$text")
        }
    }
    fun draftText(key: String, text: String) {
        if (!mutable.value.draftsLoaded) { message("旧草稿尚未读取，请先重试恢复后再输入或分享"); return }
        if (key in mutable.value.unreadableDrafts) { message("旧草稿无法解密，已阻止覆盖；请重试恢复，不要清除应用数据"); return }
        if (!NotebookPolicy.validDraft(key, text)) { message("单条草稿最多10000字符"); return }
        touchedDrafts += key
        val version = (draftVersions[key] ?: 0) + 1
        draftVersions[key] = version
        mutable.update { it.copy(annotationDrafts = if(text.isEmpty()) it.annotationDrafts-key else it.annotationDrafts+(key to text)) }
        mutable.update { it.copy(draftStatus = it.draftStatus + (key to "正在加密保存草稿")) }
        localWrites.enqueue {
            if (draftVersions[key] != version) return@enqueue
            val result = try {
                container.readingRepository.saveDraft(key, text)
                "草稿已加密保存"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { "草稿未写入，请重试；关闭应用可能丢失" }
            if (draftVersions[key] == version) mutable.update { it.copy(draftStatus = it.draftStatus + (key to result)) }
        }
    }
    fun clearDraftIfMatches(key: String, text: String) {
        localWrites.enqueue { container.readingRepository.clearDraft(key, text) }
        clearDraftState(key, text)
    }
    private fun clearDraftState(key: String, text: String) {
        mutable.update { if(it.annotationDrafts[key] == text) it.copy(annotationDrafts=it.annotationDrafts-key, draftStatus=it.draftStatus-key) else it }
    }
    fun saveNote(card: LocalCard, text: String) = local {
        container.readingRepository.saveNote(card,text)
        val key = "${card.id}:${card.contentRevision}"
        clearDraftState(key, text)
        message("个人批注已加密保存，原文未修改")
    }
    fun bookSource(card: LocalCard, workKey: String, quote: String, location: String) = run { container.readingRepository.confirmBookSource(card,workKey,quote,location); message("已保存人工核对的出处证据；编辑原文后需重新核对") }
    fun review(card: LocalCard, recall: Recall) {
        val key = NotebookPolicy.draftKey(card.id, card.contentRevision)
        if (!mutable.value.draftsLoaded || key in mutable.value.unreadableDrafts) { message("请先恢复这张卡片的想法草稿，再完成回顾"); return }
        val note = mutable.value.annotationDrafts[key].orEmpty()
        local {
            if (container.readingRepository.completeReview(card,recall,note)) {
                clearDraftState(key, note)
                message(if (note.isBlank()) "已完成这张卡片的回顾" else "想法已保存，回顾已完成")
            } else message("今天已回顾过这张卡片；新的想法仍保留在草稿中")
        }
    }
    fun skipToday(card: LocalCard) = local { container.readingRepository.skipToday(card); message("今天先放过这张，明天再见；草稿会保留") }
    fun saveTopic(id: String?, topic: Topic, saved: () -> Unit) = local {
        container.readingRepository.saveTopic(id, topic)
        saved(); message("主题已保存在本机")
    }
    fun deleteTopic(id: String) = local { container.readingRepository.deleteTopic(id); message("已删除主题，卡片未删除") }
}
