package com.clipmind.android.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import com.clipmind.android.BuildConfig
import com.clipmind.android.ClipMindApp
import com.clipmind.android.data.*
import com.clipmind.android.network.ByokAnalysisResult
import com.clipmind.android.network.HealthCheckResult
import com.clipmind.android.export.ExportFormat
import com.clipmind.android.service.CaptureForegroundService
import com.clipmind.android.service.CaptureProcessingDiagnostic
import com.clipmind.android.shizuku.ClipboardDiagnosticUiState
import com.clipmind.android.shizuku.ShizukuState
import com.clipmind.android.worker.UploadScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import com.clipmind.android.knowledge.RelationEvidence
import com.clipmind.android.network.BatchRequestMetadata

enum class AppTab(val title: String, val label: String, val subtitle: String) {
    CAPTURE("采集", "采集", "随手记录，沉淀想法"),
    LIBRARY("卡片库", "卡片", "搜索与整理本地内容"),
    AI("主题", "主题", "把卡片整理成自己的知识"),
    REVIEW("回顾", "回顾", "每天一点，重新遇见好想法"),
    SETTINGS("设置", "设置", "偏好与服务配置"),
    STATUS("处理状态", "状态", "本地已保存，AI 与同步单独查看"),
}
enum class CardTimeFilter { ALL, TODAY, WEEK }
enum class CardAiFilter { ALL, PENDING, COMPLETE, FAILED }

sealed interface ConnectionUiState { data object Idle : ConnectionUiState; data object Checking : ConnectionUiState; data object Connected : ConnectionUiState; data class Failed(val reason: String) : ConnectionUiState }
sealed interface AiConnectionUiState { data object Idle : AiConnectionUiState; data object Checking : AiConnectionUiState; data object Success : AiConnectionUiState; data class Failed(val errorCode: String, val httpStatus: Int? = null) : AiConnectionUiState }
sealed interface CardOperationUiState { data object Working : CardOperationUiState; data class Success(val status: String) : CardOperationUiState; data class Failed(val errorCode: String) : CardOperationUiState }
sealed interface ExportUiState { data object Idle : ExportUiState; data object Running : ExportUiState; data class Success(val cardCount: Int) : ExportUiState; data class Failed(val reason: String) : ExportUiState }

internal fun shouldShowPublishAction(mode: CaptureMode, serverCardStatus: String?): Boolean =
    serverCardStatus == "awaiting_confirm"

internal fun filterCards(
    cards: List<LocalCard>, query: String, source: String?, time: CardTimeFilter,
    ai: CardAiFilter, ascending: Boolean, now: Long,
    annotations: Map<Long, List<String>> = emptyMap(),
): List<LocalCard> {
    val since = when (time) { CardTimeFilter.ALL -> Long.MIN_VALUE; CardTimeFilter.TODAY -> now - 86_400_000; CardTimeFilter.WEEK -> now - 7 * 86_400_000 }
    return cards.asSequence().filter { card -> query.isBlank() ||
        (listOf(card.content, card.sourceApp.orEmpty(), card.sourceUrl.orEmpty()) + card.tags.map { it.name } + annotations[card.id].orEmpty()).any { it.contains(query.trim(), true) } }
        .filter { source == null || it.sourceApp == source }
        .filter { it.capturedAt >= since }
        .filter {
            when (ai) {
                CardAiFilter.ALL -> true
                CardAiFilter.PENDING -> it.sync.analysisState() in setOf(CardAnalysisState.CONFIRM, CardAnalysisState.QUEUED, CardAnalysisState.RUNNING)
                CardAiFilter.COMPLETE -> it.sync.analysisState() == CardAnalysisState.COMPLETE
                CardAiFilter.FAILED -> it.sync.analysisState() in setOf(CardAnalysisState.FAILED, CardAnalysisState.UNREADABLE) ||
                    it.sync?.uploadState == OutboxState.RETRYABLE_ERROR || it.sync?.serverLastError != null
            }
        }.let { if (ascending) it.sortedBy(LocalCard::capturedAt) else it.sortedByDescending(LocalCard::capturedAt) }.toList()
}

data class CardEditDraft(val cardId: Long, val content: String)
data class LibrarySession(val query: String = "", val source: String? = null, val time: CardTimeFilter = CardTimeFilter.ALL,
    val ai: CardAiFilter = CardAiFilter.ALL, val ascending: Boolean = false, val selected: Set<Long> = emptySet(), val filtersExpanded: Boolean = false)

data class MainUiState(
    val shizukuState: ShizukuState = ShizukuState.UNAVAILABLE,
    val captureRequested: Boolean = false,
    val mode: CaptureMode = CaptureMode.CONFIRM,
    val tokenConfigured: Boolean = false,
    val pending: List<CaptureUiModel> = emptyList(),
    val recent: List<CaptureUiModel> = emptyList(),
    val localCards: List<LocalCard> = emptyList(),
    val selectedCard: LocalCard? = null,
    val selectedRelations: List<CardRelationEntity> = emptyList(),
    val apiBaseUrl: String = BuildConfig.API_BASE_URL,
    val connectionState: ConnectionUiState = ConnectionUiState.Idle,
    val clipboardDiagnostic: ClipboardDiagnosticUiState = ClipboardDiagnosticUiState.Idle,
    val captureProcessingDiagnostic: CaptureProcessingDiagnostic? = null,
    val cardOperations: Map<Long, CardOperationUiState> = emptyMap(),
    val aiMode: AiMode = AiMode.SERVER_ARK,
    val aiModels: Map<String, String> = emptyMap(),
    val recentAiModels: Map<String, List<String>> = emptyMap(),
    val configuredProviders: Set<String> = emptySet(),
    val legacyKeyConfigured: Boolean = false,
    val aiConnectionState: AiConnectionUiState = AiConnectionUiState.Idle,
    val modelCatalog: ModelCatalogUiState = ModelCatalogUiState(),
    val exportState: ExportUiState = ExportUiState.Idle,
    val markdownTemplate: String = SettingsDefaults.MARKDOWN_TEMPLATE,
    val wikiLinkFormat: WikiLinkFormat = WikiLinkFormat.FILE_NAME,
    val frontmatterTags: Boolean = true,
    val frontmatterTime: Boolean = true,
    val frontmatterSource: Boolean = true,
    val minimumCaptureLength: Int = SettingsDefaults.MIN_CAPTURE_LENGTH,
    val duplicateStrategy: DuplicateStrategy = DuplicateStrategy.SKIP_24_HOURS,
    val vibrationEnabled: Boolean = false,
    val aiEnabled: Boolean = false,
    val aiAutoSubmit: Boolean = false,
    val message: String? = null,
    val manualDraft: String = "",
    val composerOpen: Boolean = false,
    val editDraft: CardEditDraft? = null,
    val pendingDeletion: Set<Long> = emptySet(),
    val pullingResults: Boolean = false,
    val savingDraft: Boolean = false,
    val knowledge: KnowledgeUiState = KnowledgeUiState(),
    val relationEvidence: Map<Long, RelationEvidence> = emptyMap(),
    val learning: LearningUiState = LearningUiState(),
    val learningPreferences: com.clipmind.android.reading.LearningPreferences = com.clipmind.android.reading.LearningPreferences(),
    val allTags: List<TagEntity> = emptyList(),
    val readerDocuments: List<com.clipmind.android.reading.DocumentView> = emptyList(),
    val reviewSchedule: List<com.clipmind.android.reading.ReviewEntity> = emptyList(),
    val clock: Long = System.currentTimeMillis(),
    val reviewNavigation: Int = 0,
) {
    val apiKeyConfigured: Boolean get() = aiMode.isByok && aiMode.providerId in configuredProviders
    val aiModel: String get() = aiModels[aiMode.providerId] ?: AiDefaults.defaultModel(aiMode.providerId)
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as ClipMindApp
    private val container = app.container
    private val connectionState = MutableStateFlow<ConnectionUiState>(ConnectionUiState.Idle)
    private val aiConnectionState = MutableStateFlow<AiConnectionUiState>(AiConnectionUiState.Idle)
    private var modelTest: kotlinx.coroutines.Job? = null
    private val modelCatalog = ModelCatalogController(viewModelScope, com.clipmind.android.network.ProviderModelClient()) {
        provider -> container.apiKeyStore.readForAuthorization(provider)
    }.apply { select(container.settings.aiMode.value) }
    private val cardOperations = MutableStateFlow<Map<Long, CardOperationUiState>>(emptyMap())
    private val selectedCardId = MutableStateFlow<Long?>(null)
    private val selectedRelations = selectedCardId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else container.localCardRepository.observeRelations(id)
    }
    // Draft text stays in memory, not plaintext SavedState/Bundle persistence.
    private val composerOpen = MutableStateFlow(false)
    var librarySession by mutableStateOf(LibrarySession())
    var selectedTopicId by mutableStateOf<String?>(null)
    private val editDraft = MutableStateFlow<CardEditDraft?>(null)
    private val pendingDeletion = MutableStateFlow<Set<Long>>(emptySet())
    private val pullingResults = MutableStateFlow(false)
    private val savingDraft = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)
    private val reviewNavigation = MutableStateFlow(0)
    fun requestReview() { reviewNavigation.value += 1 }
    private val exportState = MutableStateFlow<ExportUiState>(ExportUiState.Idle)
    val knowledge = KnowledgeController(
        viewModelScope, container.knowledgeRepository, container.knowledgeClient,
        { container.settings.captureAiConfiguration() }, { container.settings.aiEnabled.value },
        { provider -> container.apiKeyStore.readForAuthorization(provider) },
        { BatchRequestMetadata.authorizationHeader(container.tokenStore.readToken().orEmpty()) },
        { message.value = it },
    )
    val learning = LearningController(viewModelScope,container) { message.value = it }
    fun setLearningPreferences(value: com.clipmind.android.reading.LearningPreferences) {
        container.learningSettings.update(value)
        com.clipmind.android.reading.LearningWorker.schedule(app)
    }
    fun saveNotionKey(value: String) { container.notionKeyStore.overwrite(value) }
    fun clearNotionKey() { container.notionKeyStore.clear() }

    private val baseState = combine(
        container.shizuku.state, container.settings.captureRequested, container.settings.mode,
        container.repository.pending, container.repository.recent,
    ) { shizuku, capture, mode, pending, recent -> MainUiState(shizuku, capture, mode, pending = pending, recent = recent) }

    val uiState: StateFlow<MainUiState> = baseState
        .combine(reviewNavigation) { state, value -> state.copy(reviewNavigation = value) }
        .combine(learning.state) { state, value -> state.copy(learning = value, manualDraft = value.annotationDrafts["manual"].orEmpty()) }
        .combine(container.learningSettings.state) { state, value -> state.copy(learningPreferences = value) }
        .combine(container.database.localCardDao().observeTags()) { state, value -> state.copy(allTags = value) }
        .combine(container.readingRepository.dao.observeDocuments().map { rows -> rows.mapNotNull(container.readingRepository::decode) }.flowOn(Dispatchers.Default)) { state, value -> state.copy(readerDocuments = value) }
        .combine(container.readingRepository.dao.observeReviews()) { state, value -> state.copy(reviewSchedule = value) }
        .combine(flow { while (true) { emit(System.currentTimeMillis()); kotlinx.coroutines.delay(30_000) } }) { state, value -> state.copy(clock = value) }
        .combine(container.localCardRepository.observeCards()) { state, cards -> state.copy(localCards = cards) }
        .combine(container.tokenStore.configured) { state, value -> state.copy(tokenConfigured = value) }
        .combine(connectionState) { state, value -> state.copy(connectionState = value) }
        .combine(container.shizuku.clipboardDiagnostic) { state, value -> state.copy(clipboardDiagnostic = value) }
        .combine(container.captureDiagnostics.latest) { state, value -> state.copy(captureProcessingDiagnostic = value) }
        .combine(cardOperations) { state, value -> state.copy(cardOperations = value) }
        .combine(container.settings.aiMode) { state, value -> state.copy(aiMode = value) }
        .combine(container.settings.aiModels) { state, value -> state.copy(aiModels = value) }
        .combine(container.settings.recentAiModels) { state, value -> state.copy(recentAiModels = value) }
        .combine(container.apiKeyStore.configured) { state, value -> state.copy(configuredProviders = value) }
        .combine(container.apiKeyStore.legacyConfigured) { state, value -> state.copy(legacyKeyConfigured = value) }
        .combine(aiConnectionState) { state, value -> state.copy(aiConnectionState = value) }
        .combine(modelCatalog.state) { state, value -> state.copy(modelCatalog = value) }
        .combine(selectedCardId) { state, value -> state.copy(selectedCard = state.localCards.firstOrNull { it.id == value }) }
        .combine(selectedRelations.map { it to container.knowledgeRepository.decodeEvidence(it) }.flowOn(Dispatchers.Default)) { state, value ->
            val activeIds = state.localCards.map { it.id }.toSet()
            state.copy(selectedRelations = value.first.filter { it.sourceCardId in activeIds && it.targetCardId in activeIds }, relationEvidence = value.second)
        }
        .combine(knowledge.state) { state, value -> state.copy(knowledge = value) }
        .combine(message) { state, value -> state.copy(message = value) }
        .combine(composerOpen) { state, value -> state.copy(composerOpen = value) }
        .combine(editDraft) { state, value -> state.copy(editDraft = value) }
        .combine(pendingDeletion) { state, value -> state.copy(pendingDeletion = value) }
        .combine(pullingResults) { state, value -> state.copy(pullingResults = value) }
        .combine(savingDraft) { state, value -> state.copy(savingDraft = value) }
        .combine(exportState) { state, value -> state.copy(exportState = value) }
        .combine(container.settings.markdownTemplate) { state, value -> state.copy(markdownTemplate = value) }
        .combine(container.settings.wikiLinkFormat) { state, value -> state.copy(wikiLinkFormat = value) }
        .combine(container.settings.frontmatterTags) { state, value -> state.copy(frontmatterTags = value) }
        .combine(container.settings.frontmatterTime) { state, value -> state.copy(frontmatterTime = value) }
        .combine(container.settings.frontmatterSource) { state, value -> state.copy(frontmatterSource = value) }
        .combine(container.settings.minimumCaptureLength) { state, value -> state.copy(minimumCaptureLength = value) }
        .combine(container.settings.duplicateStrategy) { state, value -> state.copy(duplicateStrategy = value) }
        .combine(container.settings.vibrationEnabled) { state, value -> state.copy(vibrationEnabled = value) }
        .combine(container.settings.aiEnabled) { state, value -> state.copy(aiEnabled = value) }
        .combine(container.settings.aiAutoSubmit) { state, value -> state.copy(aiAutoSubmit = value) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    fun requestShizukuPermission() = container.shizuku.requestPermission()
    fun reconnect() = container.shizuku.reconnect()
    fun setMode(value: CaptureMode) = container.settings.setMode(value)
    private fun resetModelTest() { modelTest?.cancel(); aiConnectionState.value = AiConnectionUiState.Idle }
    fun setAiMode(value: AiMode) {
        resetModelTest()
        modelCatalog.select(value)
        container.settings.setAiMode(value)
        if (value.providerId in container.apiKeyStore.configured.value) modelCatalog.refresh(value)
    }
    fun refreshAiModels() = modelCatalog.refresh(container.settings.aiMode.value)
    fun setAiModel(mode: AiMode, value: String): Boolean {
        if (!mode.isByok || mode != container.settings.aiMode.value) return false
        resetModelTest()
        return container.settings.setAiModel(mode.providerId, value)
    }
    fun setMarkdownTemplate(value: String) = container.settings.setMarkdownTemplate(value)
    fun setWikiLinkFormat(value: WikiLinkFormat) = container.settings.setWikiLinkFormat(value)
    fun setFrontmatterTags(value: Boolean) = container.settings.setFrontmatterTags(value)
    fun setFrontmatterTime(value: Boolean) = container.settings.setFrontmatterTime(value)
    fun setFrontmatterSource(value: Boolean) = container.settings.setFrontmatterSource(value)
    fun setMinimumCaptureLength(value: Int) = container.settings.setMinimumCaptureLength(value)
    fun setDuplicateStrategy(value: DuplicateStrategy) = container.settings.setDuplicateStrategy(value)
    fun setVibrationEnabled(value: Boolean) = container.settings.setVibrationEnabled(value)
    fun setAiEnabled(value: Boolean) { if (!value) resetModelTest(); container.settings.setAiEnabled(value) }
    fun setAiAutoSubmit(value: Boolean) = container.settings.setAiAutoSubmit(value)
    fun saveApiKey(mode: AiMode, value: String): Boolean {
        if (!mode.isByok || mode != container.settings.aiMode.value) return false
        resetModelTest()
        return container.apiKeyStore.overwrite(mode.providerId, value).also {
            if (!it) message.value = "Key 保存失败，请检查格式或设备安全存储；不要填写 Bearer 前缀"
            else { modelCatalog.invalidate(mode); modelCatalog.refresh(mode) }
        }
    }
    fun clearApiKey(mode: AiMode) {
        if (!mode.isByok || mode != container.settings.aiMode.value) return
        resetModelTest()
        modelCatalog.invalidate(mode)
        if (!container.apiKeyStore.clear(mode.providerId)) message.value = "Key 清除失败，请重试"
    }
    fun migrateLegacyKey(mode: AiMode) {
        if (!mode.isByok || mode != container.settings.aiMode.value) return
        resetModelTest()
        if (!container.apiKeyStore.migrateLegacy(mode.providerId)) message.value = "旧 Key 迁移失败，请重新输入当前服务商 Key"
        else { modelCatalog.invalidate(mode); modelCatalog.refresh(mode) }
    }
    fun saveToken(value: String) = container.tokenStore.saveToken(value)
    fun clearToken() = container.tokenStore.clearToken()
    fun startCapture() = CaptureForegroundService.start(app)
    fun stopCapture() = CaptureForegroundService.stop(app)
    fun confirm(id: Long) = viewModelScope.launch { container.repository.confirm(id) }
    fun discard(id: Long) = viewModelScope.launch { container.repository.discard(id) }

    fun setManualDraft(text: String) { learning.draftText("manual", text) }
    fun openComposer() { composerOpen.value = true }
    fun closeComposer() { composerOpen.value = false }
    fun importDraft(text: String) {
        learning.importManualDraft(text)
        openComposer()
    }
    fun beginEditing(card: LocalCard) { editDraft.value = CardEditDraft(card.id, card.content) }
    fun setEditDraft(text: String) { editDraft.value = editDraft.value?.copy(content = text) }
    fun cancelEditing() { editDraft.value = null }
    fun clearMessage(value: String) { if (message.value == value) message.value = null }

    fun addManualText(text: String, onSaved: () -> Unit = {}) = viewModelScope.launch {
        if (savingDraft.value) return@launch
        savingDraft.value = true
        try {
        message.value = when (val result = container.repository.capture(text, "manual", CaptureMode.CONFIRM, System.currentTimeMillis())) {
            is CaptureDecision.Stored -> {
                learning.clearDraftIfMatches("manual", text)
                onSaved()
                "已保存到本地卡片库"
            }
            CaptureDecision.Duplicate -> "最近已存在相同内容"
            is CaptureDecision.Filtered -> "内容未保存：${result.reason}"
            is CaptureDecision.EncryptionFailed -> "加密失败：${result.code}"
        }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { message.value = "保存失败，草稿已保留，请重试" }
        finally { savingDraft.value = false }
    }

    fun openCard(id: Long) = viewModelScope.launch { reloadCard(id) }
    fun closeCard() { selectedCardId.value = null; editDraft.value = null }
    fun editCard(id: Long, text: String) = viewModelScope.launch {
        try {
            if (container.localCardRepository.edit(id, text, minimumLength = container.settings.minimumCaptureLength.value)) {
                editDraft.value = null
                message.value = "已保存；内容变更后的旧分析已失效，请重新提交"
            } else message.value = "任务正在处理，请完成后再编辑"
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { message.value = "保存失败：请检查内容长度和敏感信息，原草稿已保留" }
    }
    fun deleteCards(ids: Set<Long>) { pendingDeletion.value = ids }
    fun cancelDeletion() { pendingDeletion.value = emptySet() }
    fun confirmDeletion() = viewModelScope.launch {
        val ids = pendingDeletion.value
        container.localCardRepository.softDelete(ids)
        pendingDeletion.value = emptySet()
        if (selectedCardId.value in ids) closeCard()
        message.value = "已删除本地卡片；已上传或导出的副本不受影响"
    }
    fun addTag(ids: Set<Long>, tag: String) = viewModelScope.launch {
        runCatching { container.localCardRepository.addTag(ids, tag) }.onFailure { message.value = "标签无效，请使用1–30字的名称" }
    }
    fun removeTag(id: Long, tag: Long) = viewModelScope.launch { container.localCardRepository.removeTag(id,tag) }
    fun queueAi(ids: Set<Long>) = viewModelScope.launch {
        if (!container.settings.aiEnabled.value) {
            message.value = "AI 已关闭，请先在设置中启用"
            return@launch
        }
        val changed = container.localCardRepository.queueAi(ids, container.settings.captureAiConfiguration())
        if (changed > 0) { UploadScheduler.scheduleImmediate(app); message.value = "已提交 $changed 张卡片" }
        else message.value = "当前卡片尚不可重新提交"
    }
    fun retryAi(ids: Set<Long>) = viewModelScope.launch {
        if (!container.settings.aiEnabled.value) { message.value = "请先启用 AI"; return@launch }
        val changed = container.localCardRepository.retryAi(ids)
        if (changed > 0) { UploadScheduler.scheduleImmediate(app); message.value = "已安排失败任务重试" }
        else message.value = "该任务不能直接重试；分析结果被拒绝时，请先处理后端问题，再重新提交已有结果"
    }
    fun resubmitCachedAnalysis(id: Long) = viewModelScope.launch {
        if (!container.settings.aiEnabled.value) { message.value = "请先启用 AI"; return@launch }
        if (container.localCardRepository.resubmitCachedAnalysis(id)) {
            UploadScheduler.scheduleImmediate(app)
            message.value = "已安排重新提交已有分析，不重新调用模型"
        } else message.value = "任务状态已变化或无可复用分析，请刷新后确认"
    }
    fun resolveRelation(id: Long, confirm: Boolean) = viewModelScope.launch {
        val changed = if (confirm) container.localCardRepository.confirmRelation(id) else container.localCardRepository.ignoreRelation(id)
        message.value = if (!changed) "来源或关系状态已变化，请重新检索" else if (confirm) "关系已确认，可随 Obsidian ZIP 导出双链" else "已忽略，这对卡片不会重复推荐"
    }
    fun export(uri: Uri, format: ExportFormat) {
        if (exportState.value == ExportUiState.Running) return
        exportState.value = ExportUiState.Running
        viewModelScope.launch(Dispatchers.IO) {
            exportState.value = try {
                val snapshot = container.localCardRepository.exportSnapshot()
                require(snapshot.cards.isNotEmpty() || (format == ExportFormat.OBSIDIAN_ZIP && snapshot.documents.isNotEmpty())) { "没有可导出的本地内容" }
                app.contentResolver.openOutputStream(uri, "w")?.use { output ->
                    container.exportService.write(format, snapshot, container.settings.exportPreferences(), output)
                } ?: error("无法打开目标文件")
                ExportUiState.Success(snapshot.cards.size)
            } catch (error: Exception) {
                ExportUiState.Failed(error.message ?: error.javaClass.simpleName)
            }
        }
    }

    fun uploadNow() { UploadScheduler.scheduleImmediate(app); message.value = "已安排上传；结果以实际同步状态为准" }
    fun showMessage(value: String) { message.value = value }
    fun pullAiResults() = viewModelScope.launch {
        if (pullingResults.value) return@launch
        pullingResults.value = true
        try {
        val cards = uiState.value.localCards.filter { it.sync?.serverCardId != null && it.sync.uploadState == OutboxState.SUCCEEDED }
        if (cards.isEmpty()) message.value = "暂无可拉取的服务端卡片"
        val successful = cards.count { card -> container.cardRepository.refreshServerCard(card.id, card.sync!!.serverCardId!!) is ServerCardOperationResult.Success }
        if (cards.isNotEmpty()) message.value = "已刷新 $successful/${cards.size} 张卡片；失败项可再次刷新"
        } finally { pullingResults.value = false }
    }

    private suspend fun reloadCard(id: Long) {
        selectedCardId.value = id
    }

    fun testModelConnection() {
        if (aiConnectionState.value == AiConnectionUiState.Checking) return
        val config = container.settings.captureAiConfiguration()
        if (!config.mode.isByok) { aiConnectionState.value = AiConnectionUiState.Failed("SERVER_MODE_NO_DIRECT_TEST"); return }
        if (!container.settings.aiEnabled.value) { aiConnectionState.value = AiConnectionUiState.Failed("AI_DISABLED"); return }
        val key = container.apiKeyStore.readForAuthorization(config.mode.providerId)
        if (key == null) { aiConnectionState.value = AiConnectionUiState.Failed("BYOK_KEY_MISSING"); return }
        aiConnectionState.value = AiConnectionUiState.Checking
        modelTest = viewModelScope.launch { aiConnectionState.value = when (val result = container.clientAnalyzer.analyze(config.mode.providerId, config.model, key, "阅读时记录关键观点，有助于回顾和比较不同文章的论证。")) {
            is ByokAnalysisResult.Success -> AiConnectionUiState.Success
            is ByokAnalysisResult.Failure -> AiConnectionUiState.Failed(result.code.name, result.httpStatus)
        } }
    }

    fun refreshServerCard(id: Long, cardId: String) = runCardOperation(id) { container.cardRepository.refreshServerCard(id, cardId) }
    fun confirmServerCard(id: Long, cardId: String) = runCardOperation(id) { container.cardRepository.confirmServerCard(id, cardId) }
    private fun runCardOperation(id: Long, operation: suspend () -> ServerCardOperationResult) {
        if (cardOperations.value[id] == CardOperationUiState.Working) return
        cardOperations.value = cardOperations.value + (id to CardOperationUiState.Working)
        viewModelScope.launch { cardOperations.value = cardOperations.value + (id to when (val result = operation()) {
            is ServerCardOperationResult.Success -> CardOperationUiState.Success(result.card.status)
            is ServerCardOperationResult.Failure -> CardOperationUiState.Failed(result.errorCode)
        }) }
    }
    fun testClipboardRead() { if (container.shizuku.clipboardDiagnostic.value != ClipboardDiagnosticUiState.Checking) { container.shizuku.markClipboardCheckStarted(); viewModelScope.launch(Dispatchers.IO) { container.shizuku.readClipboard() } } }
    fun testConnection() { if (connectionState.value != ConnectionUiState.Checking) { connectionState.value = ConnectionUiState.Checking; viewModelScope.launch { connectionState.value = container.healthChecker.check().toConnectionUiState() } } }
}

internal fun HealthCheckResult.toConnectionUiState(): ConnectionUiState = when (this) { HealthCheckResult.Success -> ConnectionUiState.Connected; is HealthCheckResult.Failure -> ConnectionUiState.Failed(reason) }
