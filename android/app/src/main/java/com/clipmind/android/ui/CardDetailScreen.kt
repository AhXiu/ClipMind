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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CardDetailScreen(state: MainUiState, vm: MainViewModel, padding: PaddingValues, onCopy: (String) -> Unit) {
    val card = state.selectedCard ?: return
    val editing = state.editDraft?.cardId == card.id
    val text = state.editDraft?.takeIf { it.cardId == card.id }?.content ?: card.content
    var addTag by remember { mutableStateOf(false) }
    var confirmGenerate by remember(card.id) { mutableStateOf(false) }
    val working = state.cardOperations[card.id] == CardOperationUiState.Working
    LazyColumn(
        Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            FlatCard(Modifier.fillMaxWidth()) {
                Text("AI 解读", style = MaterialTheme.typography.titleLarge)
                StatusPill(card.sync.aiLabel(), positive = card.sync.analysisState() == CardAnalysisState.COMPLETE)
                card.analysis?.let { analysis ->
                    Text("总结", style = MaterialTheme.typography.titleMedium)
                    Text(analysis.interpretation.summary)
                    Text("解读 · 模型推论", style = MaterialTheme.typography.titleMedium)
                    Text(analysis.interpretation.insight)
                    Text("行动建议", style = MaterialTheme.typography.titleMedium)
                    Text(analysis.interpretation.action)
                    Text("${analysis.provider} / ${analysis.model} · 内容版本 ${card.contentRevision}", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton({ onCopy(analysis.interpretation.summary) }) { Text("复制总结") }
                } ?: Text(
                    if (card.sync.analysisState() == CardAnalysisState.COMPLETE) "分析已完成，点击下方获取结果；获取后可离线阅读。"
                    else "提交后将在这里展示总结、解读和行动建议。",
                )
                Text(card.sync.syncLabel(), style = MaterialTheme.typography.bodySmall)
                card.sync?.lastErrorCode?.let { Text("处理错误：$it", color = MaterialTheme.colorScheme.error) }
                when (val operation = state.cardOperations[card.id]) {
                    is CardOperationUiState.Failed -> Text("操作失败：${operation.errorCode}", color = MaterialTheme.colorScheme.error)
                    CardOperationUiState.Working -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    else -> Unit
                }
            }
        }
        item {
            FlatCard(Modifier.fillMaxWidth()) {
                Text("原文", style = MaterialTheme.typography.titleMedium)
                if (editing) OutlinedTextField(text, vm::setEditDraft, Modifier.fillMaxWidth(), minLines = 5)
                else Text(card.content, style = MaterialTheme.typography.bodyLarge)
                Text("来源应用：${card.sourceApp ?: "未知"}", style = MaterialTheme.typography.bodySmall)
                card.sourceUrl?.let { Text("来源链接：$it", style = MaterialTheme.typography.bodySmall) }
                StatusPill(card.sync.aiLabel(), positive = card.sync.analysisState() == CardAnalysisState.COMPLETE)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (editing) Button({ vm.editCard(card.id, text) }, enabled = text.isNotBlank()) { Text("保存") }
                    else Button({ vm.beginEditing(card) }, enabled = card.contentAvailable && !working && card.sync?.uploadState?.let(::canEditCard) == true) { Text("编辑") }
                    OutlinedButton({ onCopy(card.content) }, enabled = card.contentAvailable) { Text("复制原文") }
                    OutlinedButton({ confirmGenerate = true }, enabled = !editing && !working && state.aiEnabled && card.contentAvailable && card.sync?.uploadState?.let(::canQueueAnalysis) == true) {
                        Text(if (card.sync?.serverCardId != null) "重新生成 AI" else "提交 AI")
                    }
                    if (card.sync?.uploadState == OutboxState.RETRYABLE_ERROR) {
                        OutlinedButton({ vm.retryAi(setOf(card.id)) }, enabled = state.aiEnabled) { Text("重试原任务") }
                    }
                }
                card.sync?.serverCardId?.let { serverId ->
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton({ vm.refreshServerCard(card.id, serverId) }, enabled = !working && card.sync.uploadState == OutboxState.SUCCEEDED) { Text("获取 AI 结果") }
                        if (shouldShowPublishAction(card.mode, card.sync.serverCardStatus) && card.analysis != null) {
                            Button({ vm.confirmServerCard(card.id, serverId) }, enabled = !working && !editing) { Text("确认写入 Obsidian") }
                        }
                    }
                }
                TextButton({ vm.deleteCards(setOf(card.id)) }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("删除卡片") }
            }
        }
        item {
            FlatCard(Modifier.fillMaxWidth()) {
                Text("标签", style = MaterialTheme.typography.titleMedium)
                Text("已确认：${card.tags.joinToString { it.name }.ifEmpty { "暂无" }}")
                card.analysis?.primaryTag?.takeIf { candidate -> card.tags.none { it.name == candidate } }?.let { candidate ->
                    Text("AI 候选：$candidate", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton({ vm.addTag(setOf(card.id), candidate) }) { Text("采用标签") }
                }
                OutlinedButton({ addTag = true }) { Text("添加确认标签") }
            }
        }
        item {
            FlatCard(Modifier.fillMaxWidth()) {
                Text("发现关联", style = MaterialTheme.typography.titleMedium)
                Text("在最近 300 张本地卡片中按词语重合检索，最多推荐 5 张；不会外发正文。词语相似不代表观点一致。", style = MaterialTheme.typography.bodySmall)
                OutlinedButton({ vm.knowledge.discover(card.id) }, enabled = !editing && card.contentAvailable && !state.knowledge.discovering) {
                    Text(if (state.knowledge.discovering) "检索中" else "查找本地关联")
                }
            }
        }
        val candidates = state.selectedRelations.filter { it.status == RelationStatus.CANDIDATE }
        if (candidates.isEmpty()) item { Text("暂无候选关系") }
        items(candidates, key = { "candidate-${it.id}" }) { relation ->
            FlatCard(Modifier.fillMaxWidth()) {
                val otherId = if (relation.sourceCardId == card.id) relation.targetCardId else relation.sourceCardId
                Text("${relationLabel(relation.relationType)} · ${if (relation.origin == "local_overlap") "本机词语匹配" else if (relation.origin == "llm") "模型建议" else "手动关联"}")
                Text("方向：卡片 ${relation.sourceCardId} → 卡片 ${relation.targetCardId}", style = MaterialTheme.typography.bodySmall)
                state.relationEvidence[relation.id]?.let { evidence ->
                    Text(evidence.reason)
                    Text("来源证据：“${evidence.sourceQuote}”", style = MaterialTheme.typography.bodySmall)
                    Text("目标证据：“${evidence.targetQuote}”", style = MaterialTheme.typography.bodySmall)
                }
                state.localCards.firstOrNull { it.id == otherId }?.let { other ->
                    Text(other.content.take(120))
                    TextButton({ vm.openCard(otherId) }, enabled = !editing) { Text("查看相关卡片") }
                    OutlinedButton({ vm.knowledge.prepare(setOf(card.id, otherId)) }, enabled = state.aiEnabled && !editing && !state.knowledge.running) { Text("归纳这两张卡片") }
                }
                Row { TextButton({ vm.resolveRelation(relation.id, true) }) { Text("确认") }; TextButton({ vm.resolveRelation(relation.id, false) }) { Text("忽略") } }
            }
        }
        item { Text("已确认关系", style = MaterialTheme.typography.titleMedium) }
        val confirmed = state.selectedRelations.filter { it.status == RelationStatus.CONFIRMED }
        if (confirmed.isEmpty()) item { Text("暂无已确认关系") }
        items(confirmed, key = { "confirmed-${it.id}" }) { relation ->
            Text("${relationLabel(relation.relationType)}：卡片 ${relation.sourceCardId} → 卡片 ${relation.targetCardId}")
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
    if (addTag) TextEntryDialog("添加标签", { addTag = false }, { vm.addTag(setOf(card.id), it); addTag = false })
    if (confirmGenerate) AlertDialog(
        onDismissRequest = { confirmGenerate = false },
        title = { Text("提交当前内容进行分析？") },
        text = { Text("原文将发送至配置的 AI 服务及后端，可能产生模型费用。重新生成会创建新任务并保留云端旧版本，新结果需确认后才写入 Obsidian。") },
        confirmButton = { TextButton({ confirmGenerate = false; vm.queueAi(setOf(card.id)) }) { Text("确认提交") } },
        dismissButton = { TextButton({ confirmGenerate = false }) { Text("取消") } },
    )
}
