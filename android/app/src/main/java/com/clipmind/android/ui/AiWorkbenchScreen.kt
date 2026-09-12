package com.clipmind.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.clipmind.android.data.*
import com.clipmind.android.export.ExportFormat

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiWorkbenchScreen(state: MainUiState, vm: MainViewModel, padding: PaddingValues, onExport: (ExportFormat) -> Unit) {
    val pending = state.localCards.filter {
        it.sync.analysisState() in setOf(CardAnalysisState.CONFIRM, CardAnalysisState.QUEUED, CardAnalysisState.RUNNING, CardAnalysisState.FAILED, CardAnalysisState.UNREADABLE) ||
            it.sync?.uploadState == OutboxState.RETRYABLE_ERROR || it.sync?.serverLastError != null
    }
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            FlatCard(Modifier.fillMaxWidth()) {
                Text("卡片与处理状态分开管理", style = MaterialTheme.typography.titleLarge)
                Text("AI 或同步失败不等于本地保存失败。这里保留真实处理进度和错误。")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(vm::uploadNow) { Text("继续已授权任务") }
                    OutlinedButton({ vm.pullAiResults() }, enabled = !state.pullingResults) { Text(if (state.pullingResults) "刷新中" else "刷新云端结果") }
                }
            }
        }
        item { SectionHeader("${pending.size} 张需要关注") }
        if (selected.isNotEmpty()) item {
            Row { TextButton({ vm.deleteCards(selected); selected = emptySet() }) { Text("删除所选") }; TextButton({ selected = emptySet() }) { Text("取消选择") } }
        }
        if (pending.isEmpty()) item { EmptyState("没有待处理事项", "安心记录，AI 整理可以稍后再做。") }
        items(pending, key = { it.id }) { card ->
            SelectableCard(card, card.id in selected, { if (selected.isEmpty()) vm.openCard(card.id) else selected = selected.toggle(card.id) }, { selected = selected.toggle(card.id) },
                "${if (card.contentAvailable) "已存本机" else "本机内容无法解密"} · ${card.sync.aiLabel()} · ${card.sync.syncLabel()}")
            card.sync?.lastErrorCode?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            card.sync?.serverLastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        items(state.readerDocuments.filter { it.kind in setOf("task_error", "transfer") }, key = { it.id }) { doc ->
            FlatCard(Modifier.fillMaxWidth()) { Text(doc.text) }
        }
        if (state.learning.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(state.learning.progress.ifBlank { "AI 正在处理" }); TextButton(vm.learning::cancel) { Text("取消等待") } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExportActions(state: MainUiState, onExport: (ExportFormat) -> Unit) {
    Text("导出本地内容", style = MaterialTheme.typography.titleLarge)
    Text("ZIP 包含卡片、主题、周报、个人想法与原始 JSON 备份，不包含未完成草稿。CSV 和纯文本仅导出卡片。导出文件为明文，历史副本可能含已删除卡片的引用，请保存到可信位置。", style = MaterialTheme.typography.bodySmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ExportFormat.entries.forEach { format ->
            OutlinedButton({ onExport(format) }, enabled = state.exportState != ExportUiState.Running) { Text(when (format) { ExportFormat.OBSIDIAN_ZIP -> "Obsidian ZIP"; ExportFormat.CSV -> "CSV"; ExportFormat.PLAIN_TEXT -> "纯文本" }) }
        }
    }
    Text(when (val value = state.exportState) {
        ExportUiState.Idle -> "尚未导出"
        ExportUiState.Running -> "正在导出"
        is ExportUiState.Success -> "已导出 ${value.cardCount} 张卡片"
        is ExportUiState.Failed -> "导出失败：${value.reason}"
    })
}
