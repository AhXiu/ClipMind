package com.clipmind.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clipmind.android.data.OutboxState
import com.clipmind.android.data.analysisState
import com.clipmind.android.data.CardAnalysisState
import com.clipmind.android.data.syncLabel
import com.clipmind.android.export.ExportFormat

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiWorkbenchScreen(state: MainUiState, vm: MainViewModel, padding: PaddingValues, onExport: (ExportFormat) -> Unit) {
    val pending = state.localCards.filter {
        it.sync.analysisState() in setOf(CardAnalysisState.CONFIRM, CardAnalysisState.QUEUED, CardAnalysisState.RUNNING, CardAnalysisState.FAILED) ||
            it.sync?.uploadState == OutboxState.RETRYABLE_ERROR || it.sync?.serverLastError != null
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            FlatCard(Modifier.fillMaxWidth()) {
                Text("从摘录到主题笔记", style = MaterialTheme.typography.titleLarge)
                Text("在卡片库长按选择 2–8 张卡片，点击「多卡归纳」。提交前可核对全文，结果包含原文引用与关系候选。")
                if (state.knowledge.running) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("正在归纳选定卡片，不会自动重试")
                    TextButton(vm.knowledge::cancel) { Text("取消等待") }
                }
            }
        }
        item { SectionHeader("主题笔记 · 最近 50 份") }
        if (state.knowledge.notes.isEmpty()) item { EmptyState("尚无主题笔记", "选中相关摘录，比较共同观点、分歧和待验证问题") }
        items(state.knowledge.notes, key = { "knowledge-${it.id}" }) { note ->
            Card(onClick = { vm.knowledge.openNote(note.id) }, modifier = Modifier.fillMaxWidth()) {
                androidx.compose.foundation.layout.Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(note.payload?.response?.result?.title ?: "笔记无法解密", style = MaterialTheme.typography.titleMedium)
                    Text(if (note.stale) "来源已变化 · 历史快照" else "${note.payload?.input?.cards?.size ?: 0} 张卡片 · 引用可追溯", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                androidx.compose.foundation.layout.Column(
                    Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("AI 处理队列", style = MaterialTheme.typography.titleLarge)
                        StatusPill("${pending.size} 张待处理", positive = pending.isEmpty())
                    }
                    Text(
                        "AI 是附属能力，即使服务不可用，本地卡片也不会丢失。",
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .75f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(vm::uploadNow) { Text("立即处理") }
                        OutlinedButton({ vm.pullAiResults() }, enabled = !state.pullingResults) { Text(if (state.pullingResults) "刷新中" else "获取 AI 结果") }
                    }
                }
            }
        }
        item { SectionHeader("待处理卡片") }
        if (pending.isEmpty()) item { EmptyState("队列已清空", "需要处理的卡片会显示在这里") }
        items(pending, key = { it.id }) { card ->
            Card(
                onClick = { vm.openCard(card.id) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                androidx.compose.foundation.layout.Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(card.content, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                    StatusPill("${card.sync.aiLabel()} · ${card.sync.syncLabel()}")
                }
            }
        }
        item { SectionHeader("导出") }
        item {
            FlatCard(Modifier.fillMaxWidth()) {
                Text("保存本地副本", style = MaterialTheme.typography.titleMedium)
                Text(
                    "通过系统文件选择器导出未删除的本地卡片。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ onExport(ExportFormat.OBSIDIAN_ZIP) }, enabled = state.exportState != ExportUiState.Running) { Text("Obsidian ZIP") }
                    OutlinedButton({ onExport(ExportFormat.CSV) }, enabled = state.exportState != ExportUiState.Running) { Text("CSV") }
                    OutlinedButton({ onExport(ExportFormat.PLAIN_TEXT) }, enabled = state.exportState != ExportUiState.Running) { Text("纯文本") }
                }
                Text(exportLabel(state.exportState), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { Spacer(Modifier.height(10.dp)) }
    }
}

private fun exportLabel(export: ExportUiState) = when (export) {
    ExportUiState.Idle -> "尚未导出"
    ExportUiState.Running -> "正在导出..."
    is ExportUiState.Success -> "已导出 ${export.cardCount} 张卡片"
    is ExportUiState.Failed -> "导出失败：${export.reason}"
}
