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
import com.clipmind.android.export.ExportFormat

@Composable
fun SettingsScreen(state: MainUiState, vm: MainViewModel, padding: PaddingValues, onOpenShizuku: () -> Unit, onExport: (ExportFormat) -> Unit, onCapture: () -> Unit) {
    var section by remember { mutableStateOf<String?>(null) }
    var advanced by remember(section) { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(section != null) { section = null }
    LazyColumn(
        Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (section == null) {
            listOf("采集" to "剪贴板与采集偏好，按需开启", "AI" to "服务商、模型与 API Key", "数据与备份" to "导出、同步与 Notion", "提醒" to "每日回顾数量与通知").forEach { (title, description) ->
                item { Card(onClick = { section = title }, modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp)) { Text(title, style = MaterialTheme.typography.titleLarge); Text(description) } } }
            }
            item { Text("ClipMind · 卡片、主题和个人想法优先保存在本机。AI 与自动采集均为可选能力。", style = MaterialTheme.typography.bodySmall) }
        } else {
            item { TextButton({ section = null }) { Text("全部设置") } }
            when (section) {
                "采集" -> {
                    item { SettingsSection("剪贴采集") { Text("无需开启采集，也可使用 + 或系统分享记录。"); Button(onCapture) { Text("采集开关与权限") }; CaptureConfiguration(state, vm, onOpenShizuku) } }
                }
                "AI" -> {
                    item { SettingsSection("AI 服务") { AiConfiguration(state, vm) } }
                    item { TextButton({ advanced = !advanced }) { Text(if (advanced) "收起自动任务" else "自动任务（高级）") } }
                    if (advanced) item { LearningSettingsView(state, vm, "AI") }
                }
                "数据与备份" -> {
                    item { SettingsSection("本地导出") { ExportActions(state, onExport) } }
                    item { LearningSettingsView(state, vm, "数据") }
                    item { TextButton({ advanced = !advanced }) { Text(if (advanced) "收起高级配置" else "后端同步与导出模板（高级）") } }
                    if (advanced) {
                        item { SettingsSection("同步配置") { SyncConfiguration(state, vm) } }
                        item { SettingsSection("Obsidian 模板") { ExportConfiguration(state, vm) } }
                    }
                }
                "提醒" -> item { LearningSettingsView(state, vm) }
            }
        }
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
    var key by remember(state.aiMode) { mutableStateOf("") }
    var model by remember(state.aiMode, state.aiModel) { mutableStateOf(state.aiModel) }
    var custom by remember(state.aiMode) { mutableStateOf(state.aiModel !in AiModelCatalog.models(state.aiMode.providerId)) }
    var nextMode by remember { mutableStateOf<AiMode?>(null) }
    var confirmation by remember(state.aiMode) { mutableStateOf<String?>(null) }
    SettingSwitch("启用 AI", state.aiEnabled) { if (it) confirmation = "enable" else vm.setAiEnabled(false) }
    if (!state.aiEnabled) Text("AI 已关闭：新捕获仅保存在本地，Worker 也不会上传正文。重新启用后才能手动提交。", style = MaterialTheme.typography.bodySmall)
    AiDropdown("服务商", state.aiMode.label, AiMode.entries.map { it.label }, false) { label ->
        val selected = AiMode.entries.first { it.label == label }
        if (selected != state.aiMode) nextMode = selected
    }
    Text("适用于官方 API Key，聊天会员或 Coding Plan 不等于通用 API 额度。", style = MaterialTheme.typography.bodySmall)
    if (state.aiMode == AiMode.SERVER_ARK) Text("使用后端 Ark；客户端不持有 Provider Key。") else {
        val customLabel = "自定义模型 ID / 方舟部署 ID"
        AiDropdown("模型", if (custom) customLabel else state.aiModel.ifBlank { "请选择模型" },
            (AiModelCatalog.options(state.aiMode.providerId, state.aiModel) + state.recentAiModels[state.aiMode.providerId].orEmpty()).distinct() + customLabel, true,
            preferred = AiModelCatalog.common(state.aiMode.providerId, state.aiModel, state.recentAiModels[state.aiMode.providerId].orEmpty()) + customLabel,
        ) { selected ->
            custom = selected == customLabel
            if (!custom) { model = selected; vm.setAiModel(state.aiMode, selected) }
        }
        if (custom) {
            OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), label = { Text("模型 ID") }, singleLine = true,
                supportingText = { Text(if (state.aiMode == AiMode.BYOK_ARK) "填写你自己的 ep-... 部署 ID" else "填写该平台 API 请求中的 model 值，不是 API Key") })
            Button({ vm.setAiModel(state.aiMode, model) }, enabled = AiDefaults.validModel(model.trim())) { Text("保存模型") }
        }
        Text("当前已保存：${state.aiModel.ifBlank { "未选择" }}", style = MaterialTheme.typography.bodySmall)
        Text("可搜索全部模型；目录不保证账户权限、余额或持续可用。", style = MaterialTheme.typography.bodySmall)
        if (state.aiMode == AiMode.BYOK_OPENROUTER) Text("GPT / Claude / Gemini / Kimi / GLM / DeepSeek / Qwen 等跨厂商模型使用 OpenRouter Key，不使用各厂商官方 Key。", style = MaterialTheme.typography.bodySmall)
        Text("${state.aiMode.label} API Key：${if (state.apiKeyConfigured) "已安全保存" else "未配置"}")
        OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text("API Key") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ confirmation = "save" }, enabled = key.isNotBlank()) { Text("保存 Key") }
            OutlinedButton({ vm.clearApiKey(state.aiMode); key = "" }, enabled = state.apiKeyConfigured) { Text("清除当前 Key") }
            OutlinedButton({ confirmation = "test" }, enabled = state.aiEnabled && state.apiKeyConfigured && AiDefaults.validModel(state.aiModel) && state.aiConnectionState != AiConnectionUiState.Checking && model.trim() == state.aiModel) { Text("测试连接") }
        }
        if (state.legacyKeyConfigured) {
            Text("检测到旧版通用 Key：为防止发错服务商，已停止自动使用。请确认原服务商后迁移，或重新输入。", style = MaterialTheme.typography.bodySmall)
            if (state.aiMode in setOf(AiMode.BYOK_ARK, AiMode.BYOK_OPENROUTER) && !state.apiKeyConfigured) {
                OutlinedButton({ confirmation = "migrate" }) { Text("将旧 Key 归属到当前服务商") }
            }
        }
        Text("测试状态：${aiConnectionLabel(state.aiConnectionState)}", style = MaterialTheme.typography.bodySmall)
        Text("此配置用于摘录分析、多卡归纳和关系判断；完整阅读分析、搜索、向量、周报仍使用后端配置。", style = MaterialTheme.typography.bodySmall)
    }
    var advanced by remember { mutableStateOf(false) }
    TextButton({ advanced = !advanced }) { Text(if (advanced) "收起接入说明与自动提交" else "接入说明与自动提交（高级）") }
    if (advanced) {
        SettingSwitch("采集后自动提交 AI", state.aiAutoSubmit) { if (it) confirmation = "auto" else vm.setAiAutoSubmit(false) }
        Text("目录更新：${AiModelCatalog.UPDATED_AT}。自动提交仍遵循采集确认模式。", style = MaterialTheme.typography.bodySmall)
        if (state.aiMode.isByok) Text("固定接口：${AiDefaults.endpoint(state.aiMode.providerId)}", style = MaterialTheme.typography.bodySmall)
    }
    nextMode?.let { selected ->
        AlertDialog(onDismissRequest = { nextMode = null }, title = { Text("切换到 ${selected.label}？") },
            text = { Text("新任务将使用此服务商；已排队任务保留原服务商和模型。不会混用 Key 或自动降级到其他服务商。启用 AI 后，任务内容会发送至所选服务商，可能产生 API 费用。") },
            confirmButton = { TextButton({ vm.setAiMode(selected); nextMode = null }) { Text("确认切换") } },
            dismissButton = { TextButton({ nextMode = null }) { Text("取消") } })
    }
    confirmation?.let { action ->
        val explanation = when (action) {
            "enable" -> "开启后，已授权的待处理任务可继续向服务商和后端发送内容，可能产生 API 费用。新卡片是否自动提交由采集模式和自动提交开关共同决定。"
            "auto" -> "自动采集模式下，新卡片将发送给当前服务商及后端，可能产生 API 费用。确认模式仍需逐次确认。可随时关闭，已接受的请求不能保证撤回。"
            "test" -> "向 ${state.aiMode.label} 的 ${state.aiModel} 发送一段固定测试文本，不读取剪贴板或卡片。会产生一次真实 API 调用，可能计费，不会自动重试。"
            "migrate" -> "请确认旧 Key 确实由 ${state.aiMode.label} 签发。迁移后只用于此服务商，不会覆盖其他 Key。"
            else -> "Key 将加密保存在本机，仅发往 ${AiDefaults.endpoint(state.aiMode.providerId)}。启用 AI 后，待处理任务可使用此 Key 发送摘录并产生费用；同步还会向 ClipMind 后端发送原文和分析结果，但不会发送 Key。"
        }
        AlertDialog(onDismissRequest = { confirmation = null }, title = { Text("确认授权") }, text = { Text(explanation) },
            confirmButton = { TextButton({
                when (action) {
                    "enable" -> vm.setAiEnabled(true)
                    "auto" -> vm.setAiAutoSubmit(true)
                    "test" -> vm.testModelConnection()
                    "migrate" -> vm.migrateLegacyKey(state.aiMode)
                    else -> if (vm.saveApiKey(state.aiMode, key)) key = ""
                }
                confirmation = null
            }) { Text("确认") } },
            dismissButton = { TextButton({ confirmation = null }) { Text("取消") } })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun AiDropdown(label: String, value: String, options: List<String>, searchable: Boolean, preferred: List<String> = options, select: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var all by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded, { expanded = !expanded; query = ""; all = false }) {
        OutlinedTextField(value, {}, Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true), readOnly = true,
            label = { Text(label) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, singleLine = true)
        ExposedDropdownMenu(expanded, { expanded = false }, Modifier.heightIn(max = 360.dp)) {
            if (searchable) OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(8.dp), label = { Text("搜索模型") }, singleLine = true)
            val visible = AiModelCatalog.search(if (query.isNotBlank() || all) options else preferred, query)
            if (searchable && query.isBlank() && !all) DropdownMenuItem(text = { Text("当前、最近与常用") }, onClick = {}, enabled = false)
            if (visible.isEmpty()) DropdownMenuItem(text = { Text("无匹配项，可清空搜索后选择自定义") }, onClick = {}, enabled = false)
            visible.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { expanded = false; select(option) }) }
            if (searchable && query.isBlank() && !all) DropdownMenuItem(text = { Text("查看全部 ${options.size - 1} 个模型") }, onClick = { all = true })
        }
    }
}

internal fun aiConnectionLabel(state: AiConnectionUiState): String = when (state) {
    AiConnectionUiState.Idle -> "尚未测试"
    AiConnectionUiState.Checking -> "正在测试"
    AiConnectionUiState.Success -> "连接与结构化输出校验通过"
    is AiConnectionUiState.Failed -> when (state.errorCode) {
        "BYOK_AUTH" -> "认证失败，请检查当前服务商的 Key 和权限"
        "BYOK_KEY_MISSING" -> "请先保存当前服务商的 Key"
        "BYOK_MODEL_MISSING", "BYOK_MODEL_UNAVAILABLE" -> "模型未配置、已下线或当前账户无权限"
        "BYOK_QUOTA" -> "额度不足，请检查 API 余额"
        "BYOK_RATE_LIMIT" -> "限流或额度不足，请查看服务商控制台后手动重试"
        "BYOK_VALIDATION" -> "模型参数或输出不兼容，请核对模型 ID"
        "BYOK_JSON" -> "返回内容不是有效的结构化结果"
        "BYOK_NETWORK" -> "网络失败或超时；已接受的请求仍可能计费"
        "AI_DISABLED" -> "AI 已关闭"
        else -> "服务请求失败，请检查配置后手动重试"
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
