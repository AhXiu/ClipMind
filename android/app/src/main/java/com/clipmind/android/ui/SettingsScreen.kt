package com.clipmind.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.clipmind.android.data.*

@Composable
fun SettingsScreen(state: MainUiState, vm: MainViewModel, padding: PaddingValues, onOpenShizuku: () -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { LearningSettingsView(state,vm) }
        item { SettingsSection("剪贴采集") { CaptureConfiguration(state, vm, onOpenShizuku) } }
        item { SettingsSection("AI 服务") { AiConfiguration(state, vm) } }
        item { SettingsSection("同步备份") { SyncConfiguration(state, vm) } }
        item { SettingsSection("Obsidian 模板") { ExportConfiguration(state, vm) } }
        item { SettingsSection("关于与帮助") {
            Text("ClipMind MVP · 本地卡片为权威数据，云端和 AI 为附属能力。")
            Text("遇到采集问题，请先确认 Shizuku 运行、授权和通知权限。", style = MaterialTheme.typography.bodySmall)
        } }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) =
    FlatCard(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        content()
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun CaptureConfiguration(state: MainUiState, vm: MainViewModel, onOpenShizuku: () -> Unit) {
    var minimum by remember(state.minimumCaptureLength) { mutableStateOf(state.minimumCaptureLength.toString()) }
    Text("Shizuku：${state.shizukuState.name}")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onOpenShizuku) { Text("打开 Shizuku") }; OutlinedButton(vm::testClipboardRead) { Text("只读测试") } }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(state.mode == CaptureMode.AUTO, { vm.setMode(CaptureMode.AUTO) }, { Text("自动") })
        FilterChip(state.mode == CaptureMode.CONFIRM, { vm.setMode(CaptureMode.CONFIRM) }, { Text("确认") })
    }
    OutlinedTextField(minimum, { minimum = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), label = { Text("采集最小字符数") }, singleLine = true)
    Button({ minimum.toIntOrNull()?.let(vm::setMinimumCaptureLength) }, enabled = minimum.toIntOrNull() != null) { Text("保存最小长度") }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("允许 24 小时内重复内容"); Switch(state.duplicateStrategy == DuplicateStrategy.ALLOW, { vm.setDuplicateStrategy(if (it) DuplicateStrategy.ALLOW else DuplicateStrategy.SKIP_24_HOURS) }) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("捕获成功时震动"); Switch(state.vibrationEnabled, vm::setVibrationEnabled) }
    Text("诊断只显示字符数/错误，不展示剪贴板正文。", style = MaterialTheme.typography.bodySmall)
}

@Composable private fun ExportConfiguration(state: MainUiState, vm: MainViewModel) {
    var template by remember(state.markdownTemplate) { mutableStateOf(state.markdownTemplate) }
    OutlinedTextField(template, { template = it }, Modifier.fillMaxWidth(), label = { Text("Markdown 模板") }, minLines = 5)
    Text("变量：{{title}} {{content}} {{wikilinks}} {{captured_at}} {{source_app}} {{source_url}} {{tags}}", style = MaterialTheme.typography.bodySmall)
    Row { FilterChip(state.wikiLinkFormat == WikiLinkFormat.FILE_NAME, { vm.setWikiLinkFormat(WikiLinkFormat.FILE_NAME) }, { Text("[[文件名]]") }); Spacer(Modifier.width(6.dp)); FilterChip(state.wikiLinkFormat == WikiLinkFormat.FILE_NAME_WITH_TITLE, { vm.setWikiLinkFormat(WikiLinkFormat.FILE_NAME_WITH_TITLE) }, { Text("带标题别名") }) }
    SettingSwitch("Frontmatter 标签", state.frontmatterTags, vm::setFrontmatterTags)
    SettingSwitch("Frontmatter 时间", state.frontmatterTime, vm::setFrontmatterTime)
    SettingSwitch("Frontmatter 来源", state.frontmatterSource, vm::setFrontmatterSource)
    Button({ vm.setMarkdownTemplate(template) }) { Text("保存导出模板") }
}

@Composable private fun SettingSwitch(label: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text(label); Switch(checked, change) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun AiConfiguration(state: MainUiState, vm: MainViewModel) {
    var key by remember { mutableStateOf("") }
    var model by remember(state.aiMode) { mutableStateOf(if (state.aiMode == AiMode.BYOK_OPENROUTER) state.openRouterModel else state.arkModel) }
    SettingSwitch("启用 AI", state.aiEnabled, vm::setAiEnabled)
    SettingSwitch("采集后自动提交 AI", state.aiAutoSubmit, vm::setAiAutoSubmit)
    if (!state.aiEnabled) Text("AI 已关闭：新捕获仅保存在本地，Worker 也不会上传正文。重新启用后才能手动提交。", style = MaterialTheme.typography.bodySmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { AiMode.entries.forEach { mode -> FilterChip(state.aiMode == mode, { vm.setAiMode(mode) }, { Text(mode.name) }) } }
    if (state.aiMode == AiMode.SERVER_ARK) Text("使用后端 Ark；客户端不持有 Provider Key。") else {
        OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), label = { Text("模型 ID") }, singleLine = true)
        Button({ if (state.aiMode == AiMode.BYOK_ARK) vm.setArkModel(model) else vm.setOpenRouterModel(model) }, enabled = model.isNotBlank()) { Text("保存模型") }
        Text("API Key：${if (state.apiKeyConfigured) "已安全保存" else "未配置"}")
        OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text("API Key") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ if (vm.saveApiKey(key)) key = "" }, enabled = key.isNotBlank()) { Text("保存 Key") }
            OutlinedButton({ vm.clearApiKey(); key = "" }, enabled = state.apiKeyConfigured) { Text("清除") }
            OutlinedButton(vm::testModelConnection) { Text("测试") }
        }
        Text("测试状态：${state.aiConnectionState}", style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun SyncConfiguration(state: MainUiState, vm: MainViewModel) {
    var token by remember { mutableStateOf("") }
    Text("后端：${state.apiBaseUrl}", style = MaterialTheme.typography.bodySmall)
    Text("Token：${if (state.tokenConfigured) "已安全保存" else "未配置"}")
    OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), label = { Text("上传 Token") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button({ if (vm.saveToken(token)) token = "" }, enabled = token.isNotBlank()) { Text("保存") }
        OutlinedButton({ vm.clearToken(); token = "" }, enabled = state.tokenConfigured) { Text("清除") }
        OutlinedButton(vm::testConnection) { Text("测试连接") }
    }
    Text("连接状态：${state.connectionState}", style = MaterialTheme.typography.bodySmall)
    Text("Room 数据库物理备份/恢复尚未安全实现；请使用上方本地导出，不提供可能损坏数据的按钮。", style = MaterialTheme.typography.bodySmall)
}
