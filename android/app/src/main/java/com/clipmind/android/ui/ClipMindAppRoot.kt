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
                    if (detail != null) TextButton(vm::closeCard) { Text("返回") }
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
        snackbarHost = { state.message?.let { Snackbar(Modifier.padding(12.dp)) { Text(it) } } },
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
