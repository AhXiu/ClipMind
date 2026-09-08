package com.clipmind.android.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
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
    var tab by rememberSaveable { mutableStateOf(AppTab.CAPTURE) }
    LaunchedEffect(state.reviewNavigation) { if (state.reviewNavigation > 0) tab = AppTab.REVIEW }
    var confirmLeave by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val detail = state.selectedCard
    val close: () -> Unit = { if (state.editDraft != null) confirmLeave = true else vm.closeCard() }
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
                            detail?.let { "卡片详情" } ?: tab.title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        if (detail == null) Text(
                            tab.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    if (detail != null) TextButton(close) { Text("返回") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            if (detail == null) NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
            ) {
                AppTab.entries.forEach { item ->
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
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (detail != null) CardDetailScreen(state, vm, padding, onCopy)
        else when (tab) {
            AppTab.CAPTURE -> CaptureHomeScreen(state, vm, padding, onStartCapture, onVoiceInput, onOpenShizuku)
            AppTab.LIBRARY -> LibraryScreen(state, vm, padding)
            AppTab.AI -> AiWorkbenchScreen(state, vm, padding, onExport)
            AppTab.REVIEW -> ReviewScreen(state, vm, padding, onCopy)
            AppTab.SETTINGS -> SettingsScreen(state, vm, padding, onOpenShizuku)
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
