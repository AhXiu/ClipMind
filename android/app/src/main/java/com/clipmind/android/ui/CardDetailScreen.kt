package com.clipmind.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.clipmind.android.data.RelationStatus

@Composable
fun CardDetailScreen(state: MainUiState, vm: MainViewModel, padding: PaddingValues, onCopy: (String) -> Unit) {
    val card = state.selectedCard ?: return
    var editing by remember(card.id) { mutableStateOf(false) }
    var text by remember(card.id, card.content) { mutableStateOf(card.content) }
    var addTag by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { TextButton(vm::closeCard) { Text("← 返回卡片库") } }
        item {
            Card { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("原文", style = MaterialTheme.typography.titleMedium)
                if (editing) OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), minLines = 5)
                else Text(card.content)
                Text("来源应用：${card.sourceApp ?: "未知"}", style = MaterialTheme.typography.bodySmall)
                card.sourceUrl?.let { Text("来源链接：$it", style = MaterialTheme.typography.bodySmall) }
                Text("同步/AI：${card.sync.aiLabel()}", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (editing) Button({ vm.editCard(card.id, text); editing = false }) { Text("保存") }
                    else Button({ editing = true }) { Text("编辑") }
                    OutlinedButton({ onCopy(card.content) }) { Text("复制") }
                    OutlinedButton({ vm.queueAi(setOf(card.id)) }) { Text("重新提交 AI") }
                }
                card.sync?.serverCardId?.let { serverId ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton({ vm.refreshServerCard(card.id, serverId) }) { Text("刷新云端状态") }
                        if (shouldShowPublishAction(card.mode, card.sync.serverCardStatus)) {
                            Button({ vm.confirmServerCard(card.id, serverId) }) { Text("发布到 Obsidian") }
                        }
                    }
                }
                TextButton({ vm.deleteCards(setOf(card.id)) }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("删除卡片") }
            } }
        }
        item {
            Card { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("标签", style = MaterialTheme.typography.titleMedium)
                Text("已确认：${card.tags.joinToString { it.name }.ifEmpty { "暂无" }}")
                Text("候选标签：暂无（AI 标签候选解析尚未接入）", style = MaterialTheme.typography.bodySmall)
                OutlinedButton({ addTag = true }) { Text("添加确认标签") }
            } }
        }
        item { Text("卡片关系", style = MaterialTheme.typography.titleMedium) }
        val candidates = state.selectedRelations.filter { it.status == RelationStatus.CANDIDATE }
        if (candidates.isEmpty()) item { Text("暂无候选关系") }
        items(candidates, key = { "candidate-${it.id}" }) { relation ->
            Card { Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Text("${relation.relationType} · 卡片 ${relation.targetCardId}")
                Row { TextButton({ vm.resolveRelation(relation.id, true) }) { Text("确认") }; TextButton({ vm.resolveRelation(relation.id, false) }) { Text("忽略") } }
            } }
        }
        item { Text("已确认关系", style = MaterialTheme.typography.titleMedium) }
        val confirmed = state.selectedRelations.filter { it.status == RelationStatus.CONFIRMED }
        if (confirmed.isEmpty()) item { Text("暂无已确认关系") }
        items(confirmed, key = { "confirmed-${it.id}" }) { Text("${it.relationType}：卡片 ${if (it.sourceCardId == card.id) it.targetCardId else it.sourceCardId}") }
        item { Spacer(Modifier.height(20.dp)) }
    }
    if (addTag) TextEntryDialog("添加标签", { addTag = false }, { vm.addTag(setOf(card.id), it); addTag = false })
}
