package com.clipmind.android

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clipmind.android.data.CaptureMode
import com.clipmind.android.data.CaptureUiModel
import com.clipmind.android.shizuku.ClipboardDiagnosticUiState
import com.clipmind.android.shizuku.ShizukuState
import com.clipmind.android.ui.ConnectionUiState
import com.clipmind.android.ui.MainUiState
import com.clipmind.android.ui.MainViewModel
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by viewModel.uiState.collectAsState()
            MaterialTheme { MainScreen(state, viewModel, ::startCapture) }
        }
    }

    private fun startCapture() {
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        viewModel.startCapture()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(state: MainUiState, vm: MainViewModel, startCapture: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("ClipMind") }) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                StatusCard(state.shizukuState, state.clipboardDiagnostic, vm)
                Spacer(Modifier.height(12.dp))
                Text("采集模式", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(state.mode == CaptureMode.AUTO, { vm.setMode(CaptureMode.AUTO) }, { Text("自动") })
                    FilterChip(state.mode == CaptureMode.CONFIRM, { vm.setMode(CaptureMode.CONFIRM) }, { Text("确认后上传") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = startCapture, enabled = !state.captureRequested) { Text("启动采集") }
                    Button(onClick = vm::stopCapture, enabled = state.captureRequested) { Text("停止采集") }
                }
            }
            item { ConnectionCard(state.apiBaseUrl, state.connectionState, vm) }
            item { TokenCard(state.tokenConfigured, vm) }
            item { Text("待确认 (${state.pending.size})", style = MaterialTheme.typography.titleMedium) }
            if (state.pending.isEmpty()) item { Text("暂无待确认内容") }
            items(state.pending, key = { "pending-${it.id}" }) { CaptureCard(it, true, vm) }
            item { Text("最近采集", style = MaterialTheme.typography.titleMedium) }
            if (state.recent.isEmpty()) item { Text("暂无采集记录") }
            items(state.recent, key = { "recent-${it.id}" }) { CaptureCard(it, false, vm) }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun ConnectionCard(baseUrl: String, state: ConnectionUiState, vm: MainViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("后端连接", style = MaterialTheme.typography.titleMedium)
            Text("API Base URL：$baseUrl", style = MaterialTheme.typography.bodySmall)
            Text(when (state) {
                ConnectionUiState.Idle -> "连接状态：尚未检查"
                ConnectionUiState.Checking -> "连接状态：检查中…"
                ConnectionUiState.Connected -> "连接状态：连接成功"
                is ConnectionUiState.Failed -> "连接状态：连接失败（${state.reason}）"
            })
            Button(
                onClick = vm::testConnection,
                enabled = state != ConnectionUiState.Checking,
            ) { Text(if (state == ConnectionUiState.Checking) "检查中…" else "测试连接") }
        }
    }
}

@Composable
private fun TokenCard(configured: Boolean, vm: MainViewModel) {
    var token by remember { mutableStateOf("") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("上传凭证：${if (configured) "已安全保存" else "未配置"}", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Token") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { if (vm.saveToken(token)) token = "" }, enabled = token.isNotBlank()) { Text("保存") }
                Button(onClick = { vm.clearToken(); token = "" }, enabled = configured) { Text("清除") }
            }
            Text("Token 使用 Keystore 加密后存储，不在界面回显。", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun StatusCard(
    status: ShizukuState,
    diagnostic: ClipboardDiagnosticUiState,
    vm: MainViewModel,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Shizuku：${status.name}", style = MaterialTheme.typography.titleMedium)
            Text(when (status) {
                ShizukuState.UNAVAILABLE -> "请安装并启动 Shizuku，然后返回重连。"
                ShizukuState.PERMISSION_REQUIRED -> "Shizuku 已运行，请授予 ClipMind 权限。"
                ShizukuState.BINDER_READY -> "权限已就绪，正在连接最小权限 UserService。"
                ShizukuState.ACTIVE -> "UserService 已连接，可启动采集。"
                ShizukuState.DEAD -> "连接已断开；确认 Shizuku 正在运行后重连。"
            })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (status == ShizukuState.PERMISSION_REQUIRED) Button(onClick = vm::requestShizukuPermission) { Text("申请权限") }
                if (status == ShizukuState.UNAVAILABLE || status == ShizukuState.DEAD) Button(onClick = vm::reconnect) { Text("重连") }
            }
            Button(
                onClick = vm::testClipboardRead,
                enabled = diagnostic != ClipboardDiagnosticUiState.Checking,
            ) { Text(if (diagnostic == ClipboardDiagnosticUiState.Checking) "检查中…" else "测试读取剪贴板") }
            Text(when (diagnostic) {
                ClipboardDiagnosticUiState.Idle -> "剪贴板诊断：尚未检查"
                ClipboardDiagnosticUiState.Checking -> "剪贴板诊断：检查中…"
                is ClipboardDiagnosticUiState.Success -> "剪贴板诊断：读取成功（${diagnostic.characterCount} 个字符）"
                is ClipboardDiagnosticUiState.Failed -> "剪贴板诊断：失败 ${diagnostic.code}${diagnostic.detail?.let { "（$it）" } ?: ""}"
            }, style = MaterialTheme.typography.bodySmall)
            Text("诊断仅显示字符数和错误信息，不显示或记录剪贴板正文。", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CaptureCard(item: CaptureUiModel, actionable: Boolean, vm: MainViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(item.content, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text("状态：${item.state.name}", style = MaterialTheme.typography.bodySmall)
            Text("采集时间：${DateFormat.getDateTimeInstance().format(Date(item.capturedAt))}", style = MaterialTheme.typography.bodySmall)
            Text("最后错误码：${item.lastErrorCode ?: "无"}", style = MaterialTheme.typography.bodySmall)
            Text("重试次数：${item.retryCount}", style = MaterialTheme.typography.bodySmall)
            Text(
                "下次重试时间：${if (item.nextRetryAt > 0) DateFormat.getDateTimeInstance().format(Date(item.nextRetryAt)) else "无"}",
                style = MaterialTheme.typography.bodySmall,
            )
            if (actionable) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (item.contentAvailable) Button(onClick = { vm.confirm(item.id) }) { Text("确认") }
                Button(onClick = { vm.discard(item.id) }) { Text("丢弃") }
            }
        }
    }
}
