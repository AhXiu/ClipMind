package com.clipmind.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clipmind.android.export.ExportFormat

@Composable
fun AiWorkbenchScreen(state: MainUiState, vm: MainViewModel, padding: PaddingValues, onExport: (ExportFormat) -> Unit) {
    val pending = state.localCards.filter {
        it.sync?.encryptedClientAnalysis == null && it.sync?.uploadState in setOf(
            com.clipmind.android.data.OutboxState.READY,
            com.clipmind.android.data.OutboxState.RETRYABLE_ERROR,
            com.clipmind.android.data.OutboxState.UPLOADING,
            com.clipmind.android.data.OutboxState.SUCCEEDED,
        )
    }
    LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("AI 任务", style = MaterialTheme.typography.headlineSmall)
                Text("待处理候选 ${pending.size} 张。AI 是附属能力，本地卡片不依赖云端存在。")
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(vm::uploadNow) { Text("手动上传") }
                    OutlinedButton(vm::pullAiResults) { Text("拉取 AI 结果/状态（当前仅状态）") }
                }
            } }
        }
        item { Text("待处理候选", style = MaterialTheme.typography.titleMedium) }
        if (pending.isEmpty()) item { Text("当前没有可识别的待处理任务") }
        items(pending.take(20), key = { it.id }) { card ->
            Card(onClick = { vm.openCard(card.id) }) { Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Text(card.content, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${card.sync.aiLabel()} · ${card.sync?.uploadState?.name ?: "LOCAL_ONLY"}", style = MaterialTheme.typography.bodySmall)
            } }
        }
        item {
            Card { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("发现", style = MaterialTheme.typography.titleMedium)
                Text("随机回顾：占位，尚未接入抽样策略。")
                Text("AI 推荐：占位，当前没有推荐排序数据。")
            } }
        }
        item {
            Card { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("本地导出", style = MaterialTheme.typography.titleMedium)
                Text("通过系统文件选择器保存；导出只读取未删除的本地卡片。")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button({ onExport(ExportFormat.OBSIDIAN_ZIP) }, enabled = state.exportState != ExportUiState.Running) { Text("Vault ZIP") }
                    OutlinedButton({ onExport(ExportFormat.CSV) }, enabled = state.exportState != ExportUiState.Running) { Text("CSV") }
                    OutlinedButton({ onExport(ExportFormat.PLAIN_TEXT) }, enabled = state.exportState != ExportUiState.Running) { Text("纯文本") }
                }
                Text(when (val export = state.exportState) {
                    ExportUiState.Idle -> "尚未导出"
                    ExportUiState.Running -> "正在导出…"
                    is ExportUiState.Success -> "导出成功：${export.cardCount} 张卡片"
                    is ExportUiState.Failed -> "导出失败：${export.reason}"
                }, style = MaterialTheme.typography.bodySmall)
            } }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}
