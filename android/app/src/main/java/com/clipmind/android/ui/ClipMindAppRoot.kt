package com.clipmind.android.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.clipmind.android.export.ExportFormat
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.saveable.rememberSaveable

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipMindAppRoot(
    state: MainUiState,
    vm: MainViewModel,
    onStartCapture: () -> Unit,
    onVoiceInput: () -> Unit,
    onOpenShizuku: () -> Unit,
    onCopy: (String) -> Unit,
    onExport: (ExportFormat) -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(AppTab.LIBRARY) }
    var secondary by rememberSaveable { mutableStateOf<AppTab?>(null) }
    var composerPosition by rememberMovableDialogPosition()
    val screen = secondary ?: tab
    val libraryScroll = rememberLazyListState()
    LaunchedEffect(state.reviewNavigation) { if (state.reviewNavigation > 0) { tab = AppTab.REVIEW; secondary = null } }
    var confirmLeave by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val detail = state.selectedCard
    val close: () -> Unit = { if (state.editDraft != null) confirmLeave = true else vm.closeCard() }
    BackHandler(enabled = secondary != null && detail == null) { secondary = null }
    BackHandler(enabled = detail != null, onBack = close)
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.clearMessage(it) }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            detail?.let { "卡片详情" } ?: screen.title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        if (detail == null) Text(
                            screen.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    if (detail != null) TextButton(close) { Text("返回") }
                    else if (secondary != null) TextButton({ secondary = null }) { Text("返回") }
                },
                actions = {
                    if (detail == null && secondary == null) {
                        TextButton({ secondary = AppTab.STATUS }) { Text("状态") }
                        TextButton({ secondary = AppTab.SETTINGS }) { Text("设置") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            if (detail == null && secondary == null) NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
            ) {
                listOf(AppTab.LIBRARY, AppTab.AI, AppTab.REVIEW).forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { TabGlyph(item, tab == item, Modifier.semantics { contentDescription = item.title }) },
                        label = { Text(item.label) },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    )
                }
            }
        },
        floatingActionButton = {
            if (detail == null && secondary == null) FloatingActionButton(vm::openComposer,
                Modifier.semantics { contentDescription = "记录卡片：输入、粘贴或语音" }) { Text("+") }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (detail != null) CardDetailScreen(state, vm, padding, onCopy)
        else when (screen) {
            AppTab.CAPTURE -> CaptureHomeScreen(state, vm, padding, onStartCapture, onVoiceInput, onOpenShizuku)
            AppTab.LIBRARY -> LibraryScreen(state, vm, padding, libraryScroll)
            AppTab.AI -> TopicScreen(state, vm, padding)
            AppTab.REVIEW -> ReviewScreen(state, vm, padding, onCopy)
            AppTab.SETTINGS -> SettingsScreen(state, vm, padding, onOpenShizuku, onExport) { secondary = AppTab.CAPTURE }
            AppTab.STATUS -> AiWorkbenchScreen(state, vm, padding, onExport)
        }
    }
    if (state.composerOpen) MovableDialog(
        title = "记录一张卡片",
        position = composerPosition,
        onPositionChange = { composerPosition = it },
        onDismissRequest = vm::closeComposer,
        confirmButton = { TextButton({ vm.addManualText(state.manualDraft, vm::closeComposer) }, enabled = state.manualDraft.isNotBlank() && !state.savingDraft) { Text(if (state.savingDraft) "保存中" else "保存卡片") } },
        dismissButton = { TextButton(vm::closeComposer) { Text("稍后继续") } },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            DraftRecoveryNotice(state, vm, "manual")
            OutlinedTextField(state.manualDraft, vm::setManualDraft, Modifier.fillMaxWidth().heightIn(max = 260.dp), minLines = 4, label = { Text(if (state.learning.draftsLoaded) "输入或长按粘贴内容" else "正在恢复加密草稿") }, enabled = state.learning.draftsLoaded && "manual" !in state.learning.unreadableDrafts)
            Text(state.learning.draftStatus["manual"] ?: "先保存到本机，不需要 AI 或采集权限。", style = MaterialTheme.typography.bodySmall)
            TextButton(onVoiceInput) { Text("语音输入") }
            Text("语音由系统识别服务处理，可能联网。", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (confirmLeave) AlertDialog(
        onDismissRequest = { confirmLeave = false },
        title = { Text("放弃未保存的修改？") },
        text = { Text("返回将丢弃当前编辑草稿，已保存的原文不会改变。") },
        confirmButton = { TextButton({ confirmLeave = false; vm.closeCard() }) { Text("放弃修改") } },
        dismissButton = { TextButton({ confirmLeave = false }) { Text("继续编辑") } },
    )
    KnowledgeDialogs(state, vm, onCopy)
    LearningDialogs(state, vm)
    if (state.pendingDeletion.isNotEmpty()) AlertDialog(
        onDismissRequest = vm::cancelDeletion,
        title = { Text("删除 ${state.pendingDeletion.size} 张本地卡片？") },
        text = { Text("卡片将从本机列表移除，不是安全擦除。此操作不会撤回正在发送的内容，也不会删除原始备份、云端、Obsidian、Notion、主题笔记、周报或批注中的历史副本；这些副本需单独管理。") },
        confirmButton = { TextButton({ vm.confirmDeletion() }) { Text("删除本地卡片") } },
        dismissButton = { TextButton(vm::cancelDeletion) { Text("取消") } },
    )
}
