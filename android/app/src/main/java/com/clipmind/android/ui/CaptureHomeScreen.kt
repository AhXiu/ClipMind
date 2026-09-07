package com.clipmind.android.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clipmind.android.data.CaptureMode
import com.clipmind.android.shizuku.ShizukuState
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CaptureHomeScreen(
    state: MainUiState,
    vm: MainViewModel,
    padding: PaddingValues,
    onStartCapture: () -> Unit,
    onVoiceInput: () -> Unit,
    onOpenShizuku: () -> Unit,
) {
    val manualText = state.manualDraft
    LazyColumn(
        Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { CaptureControlCard(state, vm, onStartCapture) }
        if (state.shizukuState != ShizukuState.ACTIVE) {
            item { ShizukuNotice(state, vm, onOpenShizuku) }
        }
        item {
            FlatCard(Modifier.fillMaxWidth()) {
                Text("快速记录", style = MaterialTheme.typography.titleMedium)
                Text(
                    "文字只会先保存到本机。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = manualText,
                    onValueChange = vm::setManualDraft,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("粘贴或输入一段内容...") },
                    minLines = 3,
                    shape = RoundedCornerShape(14.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { vm.addManualText(manualText) },
                        enabled = manualText.isNotBlank() && !state.savingDraft,
                    ) { Text(if (state.savingDraft) "保存中" else "保存卡片") }
                    OutlinedButton(onVoiceInput) { Text("语音输入") }
                }
            }
        }
        if (state.pending.isNotEmpty()) {
            item { SectionHeader("待确认上传 · ${state.pending.size}") }
            items(state.pending.take(5), key = { "pending-${it.id}" }) { card ->
                FlatCard(Modifier.fillMaxWidth()) {
                    Text(card.content, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text("确认后原文将发送至配置的 AI 服务及后端。", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ vm.confirm(card.id) }, enabled = card.contentAvailable && state.aiEnabled) { Text("确认上传") }
                        TextButton({ vm.deleteCards(setOf(card.id)) }) { Text("删除") }
                    }
                }
            }
        }
        item {
            SectionHeader("最近捕获") {
                StatusPill("${state.recent.size} 条")
            }
        }
        if (state.recent.isEmpty()) {
            item { EmptyState("还没有卡片", "复制一段文字或在上方手动记录") }
        }
        items(state.recent.take(8), key = { it.id }) { card ->
            Card(
                Modifier.fillMaxWidth().combinedClickable(
                    onClick = { vm.openCard(card.id) },
                    onLongClick = { vm.deleteCards(setOf(card.id)) },
                ),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(card.content, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(card.capturedAt)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        StatusPill(when (card.state) {
                            com.clipmind.android.data.OutboxState.LOCAL_ONLY -> "仅本地"
                            com.clipmind.android.data.OutboxState.PENDING_CONFIRMATION -> "待确认上传"
                            com.clipmind.android.data.OutboxState.READY -> "等待上传"
                            com.clipmind.android.data.OutboxState.UPLOADING -> "正在处理"
                            com.clipmind.android.data.OutboxState.SUCCEEDED -> "已上传"
                            com.clipmind.android.data.OutboxState.RETRYABLE_ERROR -> "等待重试"
                            com.clipmind.android.data.OutboxState.REJECTED -> "已拒绝"
                            com.clipmind.android.data.OutboxState.DECRYPTION_FAILED -> "无法解密"
                            com.clipmind.android.data.OutboxState.DISCARDED -> "已丢弃"
                        })
                    }
                }
            }
        }
        item { Spacer(Modifier.height(10.dp)) }
    }
}

@Composable
private fun CaptureControlCard(state: MainUiState, vm: MainViewModel, onStartCapture: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        if (state.captureRequested) "剪贴板采集中" else "剪贴板采集已暂停",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        if (state.captureRequested) "复制文字后将自动进入处理流程" else "暂停期间不会读取新的剪贴板内容",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .72f),
                    )
                }
                Switch(
                    checked = state.captureRequested,
                    onCheckedChange = { if (it) onStartCapture() else vm.stopCapture() },
                    modifier = Modifier.semantics { contentDescription = "剪贴板采集总开关" },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(state.mode == CaptureMode.AUTO, { vm.setMode(CaptureMode.AUTO) }, { Text("自动收集") })
                FilterChip(state.mode == CaptureMode.CONFIRM, { vm.setMode(CaptureMode.CONFIRM) }, { Text("确认后上传") })
            }
        }
    }
}

@Composable
private fun ShizukuNotice(state: MainUiState, vm: MainViewModel, onOpenShizuku: () -> Unit) {
    FlatCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("采集服务需要处理", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Shizuku 当前状态：${state.shizukuState.name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusPill("未就绪", warning = true)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.shizukuState == ShizukuState.PERMISSION_REQUIRED) {
                Button(vm::requestShizukuPermission) { Text("授权") }
            }
            OutlinedButton(onOpenShizuku) { Text("打开 Shizuku") }
            if (state.shizukuState == ShizukuState.UNAVAILABLE || state.shizukuState == ShizukuState.DEAD) {
                OutlinedButton(vm::reconnect) { Text("重连") }
            }
        }
    }
}
