package com.clipmind.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.clipmind.android.shizuku.ShizukuState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CaptureHomeScreen(state: MainUiState, vm: MainViewModel, padding: PaddingValues, onStartCapture: () -> Unit, onVoiceInput: () -> Unit, onOpenShizuku: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            FlatCard(Modifier.fillMaxWidth()) {
                Text("剪贴板自动采集（可选）", style = MaterialTheme.typography.titleLarge)
                Text("不开启也可以通过 +、系统分享或语音记录卡片。")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (state.captureRequested) "正在采集" else "采集已暂停")
                    Switch(state.captureRequested, { if (it) onStartCapture() else vm.stopCapture() })
                }
                Text("开启后会读取剪贴板内容；AI 与上传行为遵循你在设置中授权的开关。", style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            FlatCard(Modifier.fillMaxWidth()) {
                Text("采集服务：${state.shizukuState.name}")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.shizukuState == ShizukuState.PERMISSION_REQUIRED) Button(vm::requestShizukuPermission) { Text("授权 Shizuku") }
                    OutlinedButton(onOpenShizuku) { Text("打开 Shizuku") }
                    OutlinedButton(vm::reconnect) { Text("重新连接") }
                    OutlinedButton(vm::testClipboardRead) { Text("只读诊断") }
                }
                Text("诊断不显示正文：${state.clipboardDiagnostic}", style = MaterialTheme.typography.bodySmall)
                state.captureProcessingDiagnostic?.let { Text("处理诊断：$it", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
