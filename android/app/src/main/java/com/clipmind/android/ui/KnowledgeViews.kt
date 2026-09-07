package com.clipmind.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.clipmind.android.knowledge.*

@Composable
fun KnowledgeDialogs(state: MainUiState, vm: MainViewModel, onCopy: (String) -> Unit) {
    val knowledge = state.knowledge
    knowledge.preview?.let { preview ->
        AlertDialog(
            onDismissRequest = vm.knowledge::dismissPreview,
            title = { Text("确认 ${preview.input.cards.size} 张卡片的归纳范围") },
            text = {
                Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (preview.config.mode.isByok) "将直连 ${preview.config.mode.providerId} / ${preview.config.model}，不经过后端。" else "将经已配置的后端调用其模型服务。")
                    Text("仅发送下列卡片的完整正文、ID 和内容版本，不读取其他卡片。可能产生费用；失败不会自动重试。")
                    preview.input.cards.forEach { card ->
                        Text("卡片 ${card.id} · 内容版本 ${card.revision}", style = MaterialTheme.typography.titleSmall)
                        Text(card.text, style = MaterialTheme.typography.bodySmall)
                        HorizontalDivider()
                    }
                }
            },
            confirmButton = { TextButton(vm.knowledge::generate) { Text("确认发送并归纳") } },
            dismissButton = { TextButton(vm.knowledge::dismissPreview) { Text("取消") } },
        )
    }
    val note = knowledge.notes.firstOrNull { it.id == knowledge.selectedNoteId } ?: return
    var confirmDelete by remember(note.id) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = vm.knowledge::closeNote,
        title = { Text(note.payload?.response?.result?.title ?: "笔记无法解密") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (note.stale) Text("来源已修改、删除或不可用。这是历史快照，不能视为当前卡片的结论。", color = MaterialTheme.colorScheme.error)
                note.payload?.let { payload ->
                    Text("${payload.response.provider} / ${payload.response.model} · ${payload.response.promptVersion}", style = MaterialTheme.typography.bodySmall)
                    Text("引用已通过原文匹配校验；推论本身仍需人工核对。", style = MaterialTheme.typography.bodySmall)
                    payload.response.result.points.forEach { point ->
                        Text(pointLabel(point.kind), style = MaterialTheme.typography.titleMedium)
                        Text(point.text)
                        point.evidence.forEach { evidence ->
                            Text("“${evidence.quote}”", style = MaterialTheme.typography.bodySmall)
                            val source = payload.input.cards.first { it.id == evidence.cardId }
                            TextButton({ vm.knowledge.closeNote(); vm.openCard(source.id.toLong()) }, enabled = state.localCards.any { it.id.toString() == source.id }) {
                                Text("查看卡片 ${source.id}（引用版本 ${source.revision}）")
                            }
                        }
                        HorizontalDivider()
                    }
                    if (payload.response.result.relations.isNotEmpty()) Text("关系判断已生成候选，请在对应卡片详情核对并确认。曾忽略的卡片对不会重复推荐。")
                }
            }
        },
        confirmButton = { TextButton({ note.payload?.let { onCopy(renderKnowledgeMarkdown(it, note.stale)) } }, enabled = note.payload != null) { Text("复制 Markdown") } },
        dismissButton = { Row {
            TextButton({ confirmDelete = true }) { Text("删除") }
            TextButton(vm.knowledge::closeNote) { Text("关闭") }
        } },
    )
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false }, title = { Text("删除这份主题笔记？") },
        text = { Text("只删除这份本地归纳快照，不删除原卡片或已确认的关系。") },
        confirmButton = { TextButton({ vm.knowledge.deleteNote(note.id) }) { Text("删除") } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("取消") } },
    )
}

internal fun pointLabel(kind: String) = when (kind) {
    "summary" -> "要点归纳"
    "agreement" -> "共同观点"
    "difference" -> "观点分歧"
    "question" -> "待验证问题"
    else -> "归纳"
}
