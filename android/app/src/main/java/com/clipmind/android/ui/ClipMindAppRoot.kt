package com.clipmind.android.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.clipmind.android.export.ExportFormat

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
    var tab by remember { mutableStateOf(AppTab.CAPTURE) }
    val detail = state.selectedCard
    Scaffold(
        topBar = { TopAppBar(title = { Text(detail?.let { "卡片详情" } ?: tab.title) }) },
        bottomBar = {
            if (detail == null) NavigationBar {
                AppTab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Text(item.title.take(1), Modifier.semantics { contentDescription = item.title }) },
                        label = { Text(item.title) },
                    )
                }
            }
        },
        snackbarHost = { state.message?.let { Snackbar { Text(it) } } },
    ) { padding ->
        if (detail != null) CardDetailScreen(state, vm, padding, onCopy)
        else when (tab) {
            AppTab.CAPTURE -> CaptureHomeScreen(state, vm, padding, onStartCapture, onVoiceInput, onOpenShizuku)
            AppTab.LIBRARY -> LibraryScreen(state, vm, padding)
            AppTab.AI -> AiWorkbenchScreen(state, vm, padding, onExport)
            AppTab.SETTINGS -> SettingsScreen(state, vm, padding, onOpenShizuku)
        }
    }
}
