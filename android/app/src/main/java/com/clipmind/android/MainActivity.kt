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
import com.clipmind.android.shizuku.ShizukuState
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
                StatusCard(state.shizukuState, vm)
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
private fun StatusCard(status: ShizukuState, vm: MainViewModel) {
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
        }
    }
}

@Composable
private fun CaptureCard(item: CaptureUiModel, actionable: Boolean, vm: MainViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(item.content, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text("${item.state.name} · ${DateFormat.getDateTimeInstance().format(Date(item.capturedAt))}", style = MaterialTheme.typography.bodySmall)
            if (actionable) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (item.contentAvailable) Button(onClick = { vm.confirm(item.id) }) { Text("确认") }
                Button(onClick = { vm.discard(item.id) }) { Text("丢弃") }
            }
        }
    }
}
