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
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun LibraryScreen(state: MainUiState, vm: MainViewModel, padding: PaddingValues) {
    var query by remember { mutableStateOf("") }
    var source by remember { mutableStateOf<String?>(null) }
    var time by remember { mutableStateOf(CardTimeFilter.ALL) }
    var ai by remember { mutableStateOf(CardAiFilter.ALL) }
    var ascending by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    var showAdd by remember { mutableStateOf(false) }
    var showTag by remember { mutableStateOf(false) }
    val cards = filterCards(state.localCards, query, source, time, ai, ascending, System.currentTimeMillis())
    val sources = state.localCards.mapNotNull { it.sourceApp }.distinct().take(4)

    Scaffold(
        modifier = Modifier.padding(padding),
        floatingActionButton = { FloatingActionButton({ showAdd = true }, Modifier.semantics { contentDescription = "新增本地卡片" }) { Text("＋") } },
    ) { inner ->
        LazyColumn(Modifier.fillMaxSize().padding(inner).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("在本机解密搜索") }, singleLine = true)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(source == null, { source = null }, { Text("全部来源") })
                    sources.forEach { value -> FilterChip(source == value, { source = value }, { Text(value) }) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AssistChip({ time = CardTimeFilter.entries[(time.ordinal + 1) % CardTimeFilter.entries.size] }, { Text("时间：${time.label()}") })
                    AssistChip({ ai = CardAiFilter.entries[(ai.ordinal + 1) % CardAiFilter.entries.size] }, { Text("AI：${ai.label()}") })
                    AssistChip({ ascending = !ascending }, { Text(if (ascending) "时间正序" else "时间倒序") })
                }
            }
            if (selected.isNotEmpty()) item {
                Card { Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text("已选择 ${selected.size} 张")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button({ vm.deleteCards(selected); selected = emptySet() }) { Text("删除") }
                        OutlinedButton({ showTag = true }) { Text("加标签") }
                        OutlinedButton({ vm.queueAi(selected); selected = emptySet() }) { Text("提交 AI") }
                        TextButton({ selected = emptySet() }) { Text("取消") }
                    }
                } }
            }
            if (cards.isEmpty()) item { Text("没有符合条件的本地卡片") }
            items(cards, key = { it.id }) { card ->
                val checked = card.id in selected
                Card(
                    Modifier.fillMaxWidth().combinedClickable(
                        onClick = { if (selected.isEmpty()) vm.openCard(card.id) else selected = selected.toggle(card.id) },
                        onLongClick = { selected = selected.toggle(card.id) },
                    ),
                    colors = CardDefaults.cardColors(if (checked) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant),
                ) { Column(Modifier.padding(12.dp)) {
                    Text(card.content, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text("${card.sourceApp ?: "未知来源"} · ${DateFormat.getDateTimeInstance().format(Date(card.capturedAt))}", style = MaterialTheme.typography.bodySmall)
                    Text("AI：${card.sync.aiLabel()} · ${card.tags.joinToString { it.name }.ifEmpty { "无标签" }}", style = MaterialTheme.typography.labelSmall)
                } }
            }
            item { Spacer(Modifier.height(72.dp)) }
        }
    }
    if (showAdd) TextEntryDialog("新增卡片", { showAdd = false }, { vm.addManualText(it); showAdd = false })
    if (showTag) TextEntryDialog("为 ${selected.size} 张卡片添加标签", { showTag = false }, { vm.addTag(selected, it); selected = emptySet(); showTag = false })
}

private fun Set<Long>.toggle(id: Long) = if (id in this) this - id else this + id
private fun CardTimeFilter.label() = when (this) { CardTimeFilter.ALL -> "全部"; CardTimeFilter.TODAY -> "24小时"; CardTimeFilter.WEEK -> "7天" }
private fun CardAiFilter.label() = when (this) { CardAiFilter.ALL -> "全部"; CardAiFilter.PENDING -> "待处理"; CardAiFilter.COMPLETE -> "已完成"; CardAiFilter.FAILED -> "异常" }
internal fun com.clipmind.android.data.SyncMetadataEntity?.aiLabel(): String = when {
    this == null -> "无元数据"
    encryptedClientAnalysis != null -> "已生成"
    lastErrorCode != null -> "异常"
    aiProvider != null -> "待处理"
    else -> "服务端处理"
}

@Composable
fun TextEntryDialog(title: String, dismiss: () -> Unit, submit: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = {
        OutlinedTextField(text, { text = it }, label = { Text("内容") }, minLines = 2)
    }, confirmButton = { TextButton({ submit(text) }, enabled = text.isNotBlank()) { Text("确定") } }, dismissButton = { TextButton(dismiss) { Text("取消") } })
}
