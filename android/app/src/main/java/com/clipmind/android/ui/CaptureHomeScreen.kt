package com.clipmind.android.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clipmind.android.data.CaptureMode
import com.clipmind.android.shizuku.ShizukuState
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CaptureHomeScreen(
    state: MainUiState, vm: MainViewModel, padding: PaddingValues,
    onStartCapture: () -> Unit, onVoiceInput: () -> Unit, onOpenShizuku: () -> Unit,
) {
    var manualText by remember { mutableStateOf("") }
    LazyColumn(
        Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ShizukuHealthCard(state, vm, onOpenShizuku) }
        item {
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column { Text("剪贴板采集总开关", style = MaterialTheme.typography.titleMedium); Text(if (state.captureRequested) "采集中" else "已暂停，不会读取新剪贴板", style = MaterialTheme.typography.bodySmall) }
                    Switch(
                        checked = state.captureRequested,
                        onCheckedChange = { if (it) onStartCapture() else vm.stopCapture() },
                        modifier = Modifier.semantics { contentDescription = "剪贴板采集总开关" },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(state.mode == CaptureMode.AUTO, { vm.setMode(CaptureMode.AUTO) }, { Text("自动") })
                    FilterChip(state.mode == CaptureMode.CONFIRM, { vm.setMode(CaptureMode.CONFIRM) }, { Text("确认后上传") })
                }
            } }
        }
        item {
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("手动新增", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(manualText, { manualText = it }, Modifier.fillMaxWidth(), label = { Text("卡片原文") }, minLines = 3)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ vm.addManualText(manualText); manualText = "" }, enabled = manualText.isNotBlank()) { Text("保存到本地") }
                    OutlinedButton(onVoiceInput) { Text("系统语音转文字") }
                }
                Text("也可在其他应用中选择“分享”→ ClipMind，分享文本会先进入本地卡片库。", style = MaterialTheme.typography.bodySmall)
            } }
        }
        item { Text("最近捕获", style = MaterialTheme.typography.titleLarge) }
        if (state.recent.isEmpty()) item { Text("暂无捕获") }
        items(state.recent.take(8), key = { it.id }) { card ->
            Card(Modifier.fillMaxWidth().combinedClickable(onClick = { vm.openCard(card.id) }, onLongClick = { vm.deleteCards(setOf(card.id)) })) {
                Column(Modifier.padding(14.dp)) {
                    Text(card.content, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(DateFormat.getDateTimeInstance().format(Date(card.capturedAt)), style = MaterialTheme.typography.bodySmall)
                    Text("状态：${card.state.name}；长按删除", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun ShizukuHealthCard(state: MainUiState, vm: MainViewModel, onOpenShizuku: () -> Unit) {
    val healthy = state.shizukuState == ShizukuState.ACTIVE
    Card(colors = CardDefaults.cardColors(containerColor = if (healthy) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (healthy) "Shizuku 健康" else "Shizuku 需要处理", style = MaterialTheme.typography.headlineSmall)
            Text("当前状态：${state.shizukuState.name}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.shizukuState == ShizukuState.PERMISSION_REQUIRED) Button(vm::requestShizukuPermission) { Text("授权") }
                if (!healthy) OutlinedButton(onOpenShizuku) { Text("打开 Shizuku") }
                if (state.shizukuState == ShizukuState.UNAVAILABLE || state.shizukuState == ShizukuState.DEAD) OutlinedButton(vm::reconnect) { Text("重连") }
            }
        }
    }
}
