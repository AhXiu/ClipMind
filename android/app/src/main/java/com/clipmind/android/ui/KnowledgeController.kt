package com.clipmind.android.ui

import com.clipmind.android.data.AiCaptureConfiguration
import com.clipmind.android.knowledge.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class KnowledgePreview(val input: KnowledgeRequest, val config: AiCaptureConfiguration)
data class KnowledgeUiState(
    val preview: KnowledgePreview? = null,
    val running: Boolean = false,
    val notes: List<KnowledgeNote> = emptyList(),
    val selectedNoteId: String? = null,
    val discovering: Boolean = false,
)

class KnowledgeController(
    private val scope: CoroutineScope,
    private val repository: KnowledgeStore,
    private val generator: KnowledgeGenerator,
    private val config: () -> AiCaptureConfiguration,
    private val enabled: () -> Boolean,
    private val apiKey: () -> String?,
    private val authorization: () -> String?,
    private val message: (String) -> Unit,
) {
    private val local = MutableStateFlow(KnowledgeUiState())
    val state = combine(local, repository.observeNotes()) { state, notes -> state.copy(notes = notes) }
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), KnowledgeUiState())
    private var generation: Job? = null
    private var preparing = false

    fun prepare(ids: Set<Long>) = scope.launch {
        if (preparing || local.value.running || local.value.preview != null) return@launch
        if (!enabled()) { message("请先启用 AI"); return@launch }
        preparing = true
        try {
            val preview = KnowledgePreview(repository.prepare(ids), config())
            local.update { it.copy(preview = preview) }
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { message(errorLabel(error)) }
        finally { preparing = false }
    }

    fun dismissPreview() { local.update { it.copy(preview = null) } }
    fun openNote(id: String) { local.update { it.copy(selectedNoteId = id) } }
    fun closeNote() { local.update { it.copy(selectedNoteId = null) } }
    fun deleteNote(id: String) = scope.launch { repository.deleteNote(id); closeNote() }

    fun generate() {
        val preview = local.value.preview ?: return
        if (local.value.running) return
        local.update { it.copy(preview = null, running = true) }
        generation = scope.launch {
            try {
                if (!enabled()) throw KnowledgeFailure("AI_DISABLED")
                if (config() != preview.config) throw KnowledgeFailure("CONFIG_CHANGED")
                if (!repository.isCurrent(preview.input)) throw KnowledgeFailure("SOURCE_CHANGED")
                val byok = preview.config.mode.isByok
                val result = generator.generate(preview.input, preview.config, if (byok) apiKey() else null, if (byok) null else authorization())
                ensureActive()
                if (!enabled()) throw KnowledgeFailure("AI_DISABLED")
                val id = repository.save(preview.input, result)
                local.update { it.copy(selectedNoteId = id) }
                message("主题笔记已加密保存，关系候选需要逐条确认")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { message(errorLabel(error)) }
            finally { local.update { it.copy(running = false) } }
        }
    }

    fun cancel() { generation?.cancel(); message("已取消等待；已经发出的模型请求可能仍产生费用，不会自动重试") }

    fun discover(id: Long) = scope.launch {
        if (local.value.discovering) return@launch
        local.update { it.copy(discovering = true) }
        try { message("本机检索新增 ${repository.discover(id)} 条同主题候选；未找到关联时不会强行推荐") }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { message(errorLabel(error)) }
        finally { local.update { it.copy(discovering = false) } }
    }
}

internal fun errorLabel(error: Exception): String = when ((error as? KnowledgeFailure)?.code) {
    "SELECT_2_TO_8_CARDS" -> "请选择 2–8 张卡片"
    "CONTENT_LIMIT_EXCEEDED" -> "单卡最多 6000 字，总计最多 16000 字；请缩小选择范围"
    "SENSITIVE_CONTENT" -> "选中内容未通过敏感信息检查，未发送"
    "SOURCE_CHANGED" -> "来源已修改或删除，请重新选择；本次结果未保存"
    "CONFIG_CHANGED" -> "AI 配置已变化，请重新确认提交范围"
    "INVALID_CITATIONS", "INVALID_RESULT" -> "模型引用或结构无效，结果未保存，请核对后重试"
    "BYOK_CONFIG_MISSING" -> "请先配置模型和 API Key"
    "HTTP_401", "HTTP_403", "BYOK_HTTP_401", "BYOK_HTTP_403" -> "服务认证失败，请检查对应 Token 或 API Key；不会自动重试"
    "HTTP_404", "HTTP_503" -> "后端尚未支持或配置多卡归纳，请先升级配置；不会切换其他模型"
    "HTTP_429", "BYOK_HTTP_429" -> "服务繁忙或限流，请稍后手动重试"
    "HTTP_502" -> "模型调用或引用校验失败，结果未保存；不会自动重试"
    "AI_DISABLED" -> "AI 已关闭，已停止后续处理"
    else -> "归纳未完成，请检查配置或网络后重试；不会自动重复计费"
}
