package com.clipmind.android.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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

enum class AppTab(val title: String) { CAPTURE("采集卡片"), LIBRARY("本地卡片库"), AI("AI工作台"), SETTINGS("设置") }
enum class CardTimeFilter { ALL, TODAY, WEEK }
enum class CardAiFilter { ALL, PENDING, COMPLETE, FAILED }

sealed interface ConnectionUiState { data object Idle : ConnectionUiState; data object Checking : ConnectionUiState; data object Connected : ConnectionUiState; data class Failed(val reason: String) : ConnectionUiState }
sealed interface AiConnectionUiState { data object Idle : AiConnectionUiState; data object Checking : AiConnectionUiState; data object Success : AiConnectionUiState; data class Failed(val errorCode: String) : AiConnectionUiState }
sealed interface CardOperationUiState { data object Working : CardOperationUiState; data class Success(val status: String) : CardOperationUiState; data class Failed(val errorCode: String) : CardOperationUiState }
sealed interface ExportUiState { data object Idle : ExportUiState; data object Running : ExportUiState; data class Success(val cardCount: Int) : ExportUiState; data class Failed(val reason: String) : ExportUiState }

internal fun shouldShowPublishAction(mode: CaptureMode, serverCardStatus: String?): Boolean =
    mode == CaptureMode.CONFIRM && serverCardStatus == "awaiting_confirm"

internal fun filterCards(
    cards: List<LocalCard>, query: String, source: String?, time: CardTimeFilter,
    ai: CardAiFilter, ascending: Boolean, now: Long,
): List<LocalCard> {
    val since = when (time) { CardTimeFilter.ALL -> Long.MIN_VALUE; CardTimeFilter.TODAY -> now - 86_400_000; CardTimeFilter.WEEK -> now - 7 * 86_400_000 }
    return cards.asSequence().filter { query.isBlank() || it.content.contains(query.trim(), true) }
        .filter { source == null || it.sourceApp == source }
        .filter { it.capturedAt >= since }
        .filter {
            when (ai) {
                CardAiFilter.ALL -> true
                CardAiFilter.PENDING -> it.sync?.encryptedClientAnalysis == null && it.sync?.aiProvider != null
                CardAiFilter.COMPLETE -> it.sync?.encryptedClientAnalysis != null
                CardAiFilter.FAILED -> it.sync?.lastErrorCode != null
            }
        }.let { if (ascending) it.sortedBy(LocalCard::capturedAt) else it.sortedByDescending(LocalCard::capturedAt) }.toList()
}

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
    val arkModel: String = AiDefaults.ARK_MODEL,
    val openRouterModel: String = "",
    val apiKeyConfigured: Boolean = false,
    val aiConnectionState: AiConnectionUiState = AiConnectionUiState.Idle,
    val exportState: ExportUiState = ExportUiState.Idle,
    val markdownTemplate: String = SettingsDefaults.MARKDOWN_TEMPLATE,
    val wikiLinkFormat: WikiLinkFormat = WikiLinkFormat.FILE_NAME,
    val frontmatterTags: Boolean = true,
    val frontmatterTime: Boolean = true,
    val frontmatterSource: Boolean = true,
    val minimumCaptureLength: Int = SettingsDefaults.MIN_CAPTURE_LENGTH,
    val duplicateStrategy: DuplicateStrategy = DuplicateStrategy.SKIP_24_HOURS,
    val vibrationEnabled: Boolean = false,
    val aiEnabled: Boolean = true,
    val aiAutoSubmit: Boolean = true,
    val message: String? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as ClipMindApp
    private val container = app.container
    private val connectionState = MutableStateFlow<ConnectionUiState>(ConnectionUiState.Idle)
    private val aiConnectionState = MutableStateFlow<AiConnectionUiState>(AiConnectionUiState.Idle)
    private val cardOperations = MutableStateFlow<Map<Long, CardOperationUiState>>(emptyMap())
    private val selectedCard = MutableStateFlow<LocalCard?>(null)
    private val selectedRelations = MutableStateFlow<List<CardRelationEntity>>(emptyList())
    private val message = MutableStateFlow<String?>(null)
    private val exportState = MutableStateFlow<ExportUiState>(ExportUiState.Idle)

    private val baseState = combine(
        container.shizuku.state, container.settings.captureRequested, container.settings.mode,
        container.repository.pending, container.repository.recent,
    ) { shizuku, capture, mode, pending, recent -> MainUiState(shizuku, capture, mode, pending = pending, recent = recent) }

    val uiState: StateFlow<MainUiState> = baseState
        .combine(container.localCardRepository.observeCards()) { state, cards -> state.copy(localCards = cards) }
        .combine(container.tokenStore.configured) { state, value -> state.copy(tokenConfigured = value) }
        .combine(connectionState) { state, value -> state.copy(connectionState = value) }
        .combine(container.shizuku.clipboardDiagnostic) { state, value -> state.copy(clipboardDiagnostic = value) }
        .combine(container.captureDiagnostics.latest) { state, value -> state.copy(captureProcessingDiagnostic = value) }
        .combine(cardOperations) { state, value -> state.copy(cardOperations = value) }
        .combine(container.settings.aiMode) { state, value -> state.copy(aiMode = value) }
        .combine(container.settings.arkModel) { state, value -> state.copy(arkModel = value) }
        .combine(container.settings.openRouterModel) { state, value -> state.copy(openRouterModel = value) }
        .combine(container.apiKeyStore.configured) { state, value -> state.copy(apiKeyConfigured = value) }
        .combine(aiConnectionState) { state, value -> state.copy(aiConnectionState = value) }
        .combine(selectedCard) { state, value -> state.copy(selectedCard = value) }
        .combine(selectedRelations) { state, value -> state.copy(selectedRelations = value) }
        .combine(message) { state, value -> state.copy(message = value) }
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
    fun setAiMode(value: AiMode) { container.settings.setAiMode(value); aiConnectionState.value = AiConnectionUiState.Idle }
    fun setArkModel(value: String) { container.settings.setArkModel(value); aiConnectionState.value = AiConnectionUiState.Idle }
    fun setOpenRouterModel(value: String) { container.settings.setOpenRouterModel(value); aiConnectionState.value = AiConnectionUiState.Idle }
    fun setMarkdownTemplate(value: String) = container.settings.setMarkdownTemplate(value)
    fun setWikiLinkFormat(value: WikiLinkFormat) = container.settings.setWikiLinkFormat(value)
    fun setFrontmatterTags(value: Boolean) = container.settings.setFrontmatterTags(value)
    fun setFrontmatterTime(value: Boolean) = container.settings.setFrontmatterTime(value)
    fun setFrontmatterSource(value: Boolean) = container.settings.setFrontmatterSource(value)
    fun setMinimumCaptureLength(value: Int) = container.settings.setMinimumCaptureLength(value)
    fun setDuplicateStrategy(value: DuplicateStrategy) = container.settings.setDuplicateStrategy(value)
    fun setVibrationEnabled(value: Boolean) = container.settings.setVibrationEnabled(value)
    fun setAiEnabled(value: Boolean) = container.settings.setAiEnabled(value)
    fun setAiAutoSubmit(value: Boolean) = container.settings.setAiAutoSubmit(value)
    fun saveApiKey(value: String) = container.apiKeyStore.overwrite(value)
    fun clearApiKey() { container.apiKeyStore.clear(); aiConnectionState.value = AiConnectionUiState.Idle }
    fun saveToken(value: String) = container.tokenStore.saveToken(value)
    fun clearToken() = container.tokenStore.clearToken()
    fun startCapture() = CaptureForegroundService.start(app)
    fun stopCapture() = CaptureForegroundService.stop(app)
    fun confirm(id: Long) = viewModelScope.launch { container.repository.confirm(id) }
    fun discard(id: Long) = viewModelScope.launch { container.repository.discard(id) }

    fun addManualText(text: String) = viewModelScope.launch {
        message.value = when (val result = container.repository.capture(text, "manual", CaptureMode.CONFIRM, System.currentTimeMillis())) {
            is CaptureDecision.Stored -> "已保存到本地卡片库"
            CaptureDecision.Duplicate -> "最近已存在相同内容"
            is CaptureDecision.Filtered -> "内容未保存：${result.reason}"
            is CaptureDecision.EncryptionFailed -> "加密失败：${result.code}"
        }
    }

    fun openCard(id: Long) = viewModelScope.launch { reloadCard(id) }
    fun closeCard() { selectedCard.value = null; selectedRelations.value = emptyList() }
    fun editCard(id: Long, text: String) = viewModelScope.launch { container.localCardRepository.edit(id, text); reloadCard(id) }
    fun deleteCards(ids: Set<Long>) = viewModelScope.launch { container.localCardRepository.softDelete(ids); if (selectedCard.value?.id in ids) closeCard() }
    fun addTag(ids: Set<Long>, tag: String) = viewModelScope.launch { container.localCardRepository.addTag(ids, tag); selectedCard.value?.id?.takeIf(ids::contains)?.let { reloadCard(it) } }
    fun queueAi(ids: Set<Long>) = viewModelScope.launch {
        if (!container.settings.aiEnabled.value) {
            message.value = "AI 已关闭，请先在设置中启用"
            return@launch
        }
        val changed = container.localCardRepository.queueAi(ids)
        if (changed > 0) { UploadScheduler.scheduleImmediate(app); message.value = "已提交 $changed 张卡片" }
        else message.value = "当前卡片尚不可重新提交"
    }
    fun resolveRelation(id: Long, confirm: Boolean) = viewModelScope.launch {
        if (confirm) container.localCardRepository.confirmRelation(id) else container.localCardRepository.ignoreRelation(id)
        selectedCard.value?.id?.let { reloadCard(it) }
    }
    fun export(uri: Uri, format: ExportFormat) {
        if (exportState.value == ExportUiState.Running) return
        exportState.value = ExportUiState.Running
        viewModelScope.launch(Dispatchers.IO) {
            exportState.value = try {
                val snapshot = container.localCardRepository.exportSnapshot()
                require(snapshot.cards.isNotEmpty()) { "没有可导出的本地卡片" }
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
        val cards = uiState.value.localCards.filter { it.sync?.serverCardId != null }
        if (cards.isEmpty()) message.value = "暂无可拉取的服务端卡片"
        cards.forEach { card -> card.sync?.serverCardId?.let { container.cardRepository.refreshServerCard(card.id, it) } }
        if (cards.isNotEmpty()) message.value = "已完成 ${cards.size} 张卡片状态拉取"
    }

    private suspend fun reloadCard(id: Long) {
        selectedCard.value = container.localCardRepository.detail(id)
        selectedRelations.value = container.localCardRepository.relations(id)
    }

    fun testModelConnection() {
        if (aiConnectionState.value == AiConnectionUiState.Checking) return
        val config = container.settings.captureAiConfiguration()
        if (!config.mode.isByok) { aiConnectionState.value = AiConnectionUiState.Failed("SERVER_MODE_NO_DIRECT_TEST"); return }
        val key = container.apiKeyStore.readForAuthorization()
        if (key == null) { aiConnectionState.value = AiConnectionUiState.Failed("BYOK_KEY_MISSING"); return }
        aiConnectionState.value = AiConnectionUiState.Checking
        viewModelScope.launch { aiConnectionState.value = when (val result = container.clientAnalyzer.analyze(config.mode.providerId, config.model, key, "连接测试")) {
            is ByokAnalysisResult.Success -> AiConnectionUiState.Success
            is ByokAnalysisResult.Failure -> AiConnectionUiState.Failed(result.code.name)
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
