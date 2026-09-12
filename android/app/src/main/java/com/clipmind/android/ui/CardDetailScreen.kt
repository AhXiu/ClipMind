package com.clipmind.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.clipmind.android.data.*
import com.clipmind.android.knowledge.relationLabel
import com.clipmind.android.network.ClientAnalysisRejection

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CardDetailScreen(state: MainUiState, vm: MainViewModel, padding: PaddingValues, onCopy: (String) -> Unit) {
    val card = state.selectedCard ?: return
    val editing = state.editDraft?.cardId == card.id
    val text = state.editDraft?.takeIf { it.cardId == card.id }?.content ?: card.content
    var addTag by remember(card.id) { mutableStateOf(false) }
    var addTopic by remember(card.id) { mutableStateOf(false) }
    var confirmGenerate by remember(card.id) { mutableStateOf(false) }
    var confirmResubmit by remember(card.id) { mutableStateOf(false) }
    var more by remember(card.id) { mutableStateOf(false) }
    var advancedAi by remember(card.id) { mutableStateOf(false) }
    val working = state.cardOperations[card.id] == CardOperationUiState.Working
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            FlatCard(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("原文", style = MaterialTheme.typography.titleLarge)
                    StatusPill(if (card.contentAvailable) "已存本机" else "无法解密", positive = card.contentAvailable)
                }
                if (editing) OutlinedTextField(text, vm::setEditDraft, Modifier.fillMaxWidth(), minLines = 5)
                else Text(card.content, style = MaterialTheme.typography.bodyLarge)
                Text("来源：${card.sourceApp ?: "未知"}", style = MaterialTheme.typography.bodySmall)
                card.sourceUrl?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (editing) {
                        Button({ vm.editCard(card.id, text) }, enabled = text.isNotBlank()) { Text("保存原文") }
                        TextButton(vm::cancelEditing) { Text("取消编辑") }
                    } else {
                        OutlinedButton({ vm.beginEditing(card) }, enabled = card.contentAvailable && !working && card.sync?.uploadState?.let(::canEditCard) == true) { Text("编辑") }
                        TextButton({ onCopy(card.content) }, enabled = card.contentAvailable) { Text("复制") }
                        Box {
                            TextButton({ more = true }) { Text("更多") }
                            DropdownMenu(more, { more = false }) {
                                DropdownMenuItem(text = { Text("加入主题") }, onClick = { more = false; addTopic = true })
                                DropdownMenuItem(text = { Text("添加标签") }, onClick = { more = false; addTag = true })
                                DropdownMenuItem(text = { Text("删除卡片") }, onClick = { more = false; vm.deleteCards(setOf(card.id)) })
                            }
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { card.tags.filter { it.status == "confirmed" }.forEach { tag -> StatusPill("#${tag.name}") } }
            }
        }
        item { PersonalThoughts(card, state, vm) }
        item {
            FlatCard(Modifier.fillMaxWidth()) {
                Text("相关卡片", style = MaterialTheme.typography.titleLarge)
                OutlinedButton({ vm.knowledge.discover(card.id) }, enabled = !editing && card.contentAvailable && !state.knowledge.discovering) { Text(if (state.knowledge.discovering) "查找中" else "查找本地关联") }
                Text("仅在本机匹配，不发送正文；相似不代表观点一致。", style = MaterialTheme.typography.bodySmall)
                if (state.selectedRelations.isEmpty()) Text("还没有关联。也可以把卡片加入同一个主题。")
            }
        }
        items(state.selectedRelations.filter { it.status in setOf(RelationStatus.CANDIDATE, RelationStatus.CONFIRMED) }, key = { "relation-${it.id}" }) { relation ->
            val otherId = if (relation.sourceCardId == card.id) relation.targetCardId else relation.sourceCardId
            val other = state.localCards.firstOrNull { it.id == otherId }
            FlatCard(Modifier.fillMaxWidth()) {
                Text(relationLabel(relation.relationType), style = MaterialTheme.typography.titleMedium)
                Text(other?.content.orEmpty(), maxLines = 3)
                state.relationEvidence[relation.id]?.let { Text("${it.reason}\n原文证据：${it.sourceQuote}\n关联证据：${it.targetQuote}", style = MaterialTheme.typography.bodySmall) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({ vm.openCard(otherId) }, enabled = !editing && other != null) { Text("查看卡片") }
                    if (relation.status == RelationStatus.CANDIDATE) {
                        TextButton({ vm.resolveRelation(relation.id, true) }) { Text("确认关联") }
                        TextButton({ vm.resolveRelation(relation.id, false) }) { Text("忽略") }
                    }
                }
            }
        }
        item {
            FlatCard(Modifier.fillMaxWidth()) {
                Text("AI 辅助", style = MaterialTheme.typography.titleLarge)
                Text("不影响原文与个人想法，可按需使用。", style = MaterialTheme.typography.bodySmall)
                StatusPill(card.sync.aiLabel(), positive = card.sync.analysisState() == CardAnalysisState.COMPLETE)
                card.analysis?.let { a ->
                    Text("核心观点", style = MaterialTheme.typography.titleMedium); Text(a.interpretation.summary)
                    Text("启发与应用 · AI 推论", style = MaterialTheme.typography.titleMedium); Text(a.interpretation.insight); Text(a.interpretation.action)
                    TextButton({ onCopy(a.interpretation.summary) }) { Text("复制总结") }
                }
                if (card.analysis == null) Button({ confirmGenerate = true }, enabled = !editing && !working && state.aiEnabled && card.contentAvailable && card.sync?.let { canQueueAnalysis(it.uploadState, it.lastErrorCode) } == true) { Text("帮我整理") }
                if (!state.aiEnabled) Text("AI 已关闭，返回卡片库后可在右上角设置中按需开启。", style = MaterialTheme.typography.bodySmall)
                card.sync?.lastErrorCode?.let { Text("处理错误：${clientAnalysisFailureMessage(it) ?: it}", color = MaterialTheme.colorScheme.error) }
                if (canResubmitCachedAnalysis(card.sync)) OutlinedButton({ confirmResubmit = true },
                    enabled = state.aiEnabled && !editing && !working && card.contentAvailable) { Text("重新提交已有结果") }
                card.sync?.serverLastError?.let { Text("同步错误：$it", color = MaterialTheme.colorScheme.error) }
                when (val operation = state.cardOperations[card.id]) {
                    is CardOperationUiState.Failed -> Text("操作失败：${operation.errorCode}", color = MaterialTheme.colorScheme.error)
                    CardOperationUiState.Working -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    else -> Unit
                }
                TextButton({ advancedAi = !advancedAi }) { Text(if (advancedAi) "收起高级操作" else "更多 AI 与同步操作") }
                if (advancedAi) {
                    Text(card.sync.syncLabel(), style = MaterialTheme.typography.bodySmall)
                    card.analysis?.let { Text("${it.provider} / ${it.model} · 原文版本 ${card.contentRevision}", style = MaterialTheme.typography.bodySmall) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({ confirmGenerate = true }, enabled = !editing && !working && state.aiEnabled && card.contentAvailable && card.sync?.let { canQueueAnalysis(it.uploadState, it.lastErrorCode) } == true) { Text("重新整理") }
                        if (card.sync?.uploadState == OutboxState.RETRYABLE_ERROR && ClientAnalysisRejection.fromStored(card.sync.lastErrorCode) == null)
                            OutlinedButton({ vm.retryAi(setOf(card.id)) }, enabled = state.aiEnabled) { Text("重试原任务") }
                        OutlinedButton({ vm.learning.prepareAnalysis(card.id) }, enabled = state.aiEnabled && !editing && !state.learning.busy) { Text("深度解读与思考题") }
                        OutlinedButton({ vm.learning.prepareAnalysis(card.id, true) }, enabled = state.aiEnabled && !editing && !state.learning.busy) { Text("检索延伸阅读") }
                        OutlinedButton({ vm.learning.prepareSemantic(card.id) }, enabled = state.aiEnabled && !editing && !state.learning.busy) { Text("AI 查找关联") }
                    }
                    card.sync?.serverCardId?.let { serverId ->
                        TextButton({ vm.refreshServerCard(card.id, serverId) }, enabled = !working && card.sync.uploadState == OutboxState.SUCCEEDED) { Text("获取云端结果") }
                        if (shouldShowPublishAction(card.mode, card.sync.serverCardStatus) && card.analysis != null) TextButton({ vm.confirmServerCard(card.id, serverId) }, enabled = !working && !editing) { Text("确认写入 Obsidian") }
                    }
                    Text("深度解读、搜索使用后端模型。完整结果保存在本机，可通过设置导出；云端 Obsidian 副本独立管理。", style = MaterialTheme.typography.bodySmall)
                    card.tags.filter { it.status == "pending" }.forEach { tag ->
                        Row { Text(tag.name, Modifier.weight(1f)); TextButton({ vm.learning.tag(tag.id, "confirm") }) { Text("采用标签") }; TextButton({ vm.removeTag(card.id, tag.id) }) { Text("移除") } }
                    }
                    card.analysis?.primaryTag?.takeIf { tag -> card.tags.none { it.name == tag } }?.let { tag -> TextButton({ vm.addTag(setOf(card.id), tag) }) { Text("采用分类：$tag") } }
                }
                if (state.learning.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(state.learning.progress.ifBlank { "处理中" }); TextButton(vm.learning::cancel) { Text("取消等待") } }
            }
        }
        item { ReadingResults(card, state, vm) }
    }
    if (addTag) TextEntryDialog("添加标签", { addTag = false }, { vm.addTag(setOf(card.id), it); addTag = false })
    if (addTopic) AddToTopicDialog(state, vm, setOf(card.id)) { addTopic = false }
    if (confirmGenerate) AnalysisConsent(state, setOf(card.id), { confirmGenerate = false }) { vm.queueAi(setOf(card.id)); confirmGenerate = false }
    if (confirmResubmit) AlertDialog(onDismissRequest = { confirmResubmit = false }, title = { Text("重新提交已有分析？") },
        text = { Text("请先确认后端已更新或校验问题已处理。将向 ClipMind 后端发送原文和已缓存的 ${card.sync?.aiProvider} / ${card.sync?.aiModel} 分析结果，不重新调用模型、不切换服务商。此次创建新的提交标识，旧拒绝记录不删除；后端仍会完整校验。") },
        confirmButton = { TextButton({ confirmResubmit = false; vm.resubmitCachedAnalysis(card.id) }) { Text("确认重新提交") } },
        dismissButton = { TextButton({ confirmResubmit = false }) { Text("取消") } })
}

@Composable
fun PersonalThoughts(card: LocalCard, state: MainUiState, vm: MainViewModel) {
    val key = "${card.id}:${card.contentRevision}"
    val answer = state.learning.annotationDrafts[key].orEmpty()
    var history by remember(card.id) { mutableStateOf(false) }
    val notes = state.readerDocuments.filter { it.annotation?.cardId == card.id }
    FlatCard(Modifier.fillMaxWidth()) {
        Text("我的想法", style = MaterialTheme.typography.titleLarge)
        DraftRecoveryNotice(state, vm, key)
        OutlinedTextField(answer, { vm.learning.draft(card, it) }, Modifier.fillMaxWidth(), label = { Text("这让我想到什么？") }, minLines = 3, enabled = card.contentAvailable && state.learning.draftsLoaded && key !in state.learning.unreadableDrafts)
        state.learning.draftStatus[key]?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Row {
            TextButton({ vm.learning.saveNote(card, answer) }, enabled = answer.isNotBlank() && !state.learning.localBusy && card.contentAvailable) { Text("保存想法") }
            if (answer.isNotBlank()) TextButton({ vm.learning.draft(card, answer) }) { Text("重试草稿保存") }
        }
        Text("仅本机加密保存，不随 AI 整理发送。", style = MaterialTheme.typography.bodySmall)
        if (state.learning.annotationDrafts.keys.any { it.startsWith("${card.id}:") && it != key }) Text("有旧版本的未完成想法，可在回顾 → 想法记录中找回。", style = MaterialTheme.typography.bodySmall)
        if (notes.isNotEmpty()) {
            TextButton({ history = !history }) { Text(if (history) "收起历史想法" else "已保存 ${notes.size} 条想法") }
            if (history) notes.forEach { document ->
                Text(document.annotation!!.text)
                if (document.annotation.revision != card.contentRevision) Text("写于原文旧版本 ${document.annotation.revision}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
internal fun DraftRecoveryNotice(state: MainUiState, vm: MainViewModel, key: String? = null) {
    if (!state.learning.draftsLoaded || (if (key == null) state.learning.unreadableDrafts.isNotEmpty() else key in state.learning.unreadableDrafts)) {
        Text("草稿尚未恢复或无法解密，已保留原数据并阻止覆盖。", color = MaterialTheme.colorScheme.error)
        TextButton(vm.learning::retryDraftRecovery) { Text("重试恢复草稿") }
    }
}
