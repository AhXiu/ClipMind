package com.clipmind.android.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import com.clipmind.android.data.analysisState
import com.clipmind.android.data.CardAnalysisState

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
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
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            FloatingActionButton(
                { showAdd = true },
                Modifier.semantics { contentDescription = "新增本地卡片" },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) { Text("+") }
        },
    ) { inner ->
        LazyColumn(
            Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                OutlinedTextField(
                    query,
                    { query = it },
                    Modifier.fillMaxWidth(),
                    placeholder = { Text("搜索卡片内容") },
                    supportingText = { Text("仅在本机解密搜索") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    FilterChip(source == null, { source = null }, { Text("全部来源") })
                    sources.forEach { value -> FilterChip(source == value, { source = value }, { Text(value) }) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    AssistChip({ time = CardTimeFilter.entries[(time.ordinal + 1) % CardTimeFilter.entries.size] }, { Text(time.label()) })
                    AssistChip({ ai = CardAiFilter.entries[(ai.ordinal + 1) % CardAiFilter.entries.size] }, { Text("AI ${ai.label()}") })
                    AssistChip({ ascending = !ascending }, { Text(if (ascending) "最早优先" else "最新优先") })
                }
            }
            if (selected.isNotEmpty()) {
                item {
                    FlatCard(Modifier.fillMaxWidth()) {
                        Text("已选择 ${selected.size} 张卡片", style = MaterialTheme.typography.titleMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            Button({ showTag = true }) { Text("加标签") }
                            OutlinedButton({ vm.queueAi(selected); selected = emptySet() }) { Text("提交 AI") }
                            OutlinedButton({ vm.knowledge.prepare(selected) }, enabled = selected.size in 2..8 && !state.knowledge.running && state.aiEnabled) { Text("多卡归纳") }
                            TextButton({ vm.deleteCards(selected); selected = emptySet() }) { Text("删除") }
                            TextButton({ selected = emptySet() }) { Text("取消") }
                        }
                    }
                }
            }
            item { SectionHeader("${cards.size} 张卡片") }
            if (cards.isEmpty()) item { EmptyState("没有找到卡片", "试试清除筛选条件或新增一张卡片") }
            items(cards, key = { it.id }) { card ->
                val checked = card.id in selected
                Card(
                    Modifier.fillMaxWidth().combinedClickable(
                        onClick = { if (selected.isEmpty()) vm.openCard(card.id) else selected = selected.toggle(card.id) },
                        onLongClick = { selected = selected.toggle(card.id) },
                    ),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        if (checked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(card.content, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${card.sourceApp ?: "未知来源"} · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(card.capturedAt))}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            StatusPill(card.sync.aiLabel(), positive = card.sync.analysisState() == CardAnalysisState.COMPLETE)
                            card.tags.firstOrNull()?.let { StatusPill("#${it.name}") }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(72.dp)) }
        }
    }
    if (showAdd) TextEntryDialog("新增卡片", { showAdd = false }, { vm.addManualText(it) { showAdd = false } }, state.manualDraft, vm::setManualDraft)
    if (showTag) TextEntryDialog("为 ${selected.size} 张卡片添加标签", { showTag = false }, { vm.addTag(selected, it); selected = emptySet(); showTag = false })
}

private fun Set<Long>.toggle(id: Long) = if (id in this) this - id else this + id
private fun CardTimeFilter.label() = when (this) { CardTimeFilter.ALL -> "全部时间"; CardTimeFilter.TODAY -> "24 小时"; CardTimeFilter.WEEK -> "7 天" }
private fun CardAiFilter.label() = when (this) { CardAiFilter.ALL -> "全部"; CardAiFilter.PENDING -> "待处理"; CardAiFilter.COMPLETE -> "已完成"; CardAiFilter.FAILED -> "异常" }
internal fun com.clipmind.android.data.SyncMetadataEntity?.aiLabel(): String = analysisState().label

@Composable
fun TextEntryDialog(title: String, dismiss: () -> Unit, submit: (String) -> Unit, draft: String? = null, onDraftChange: ((String) -> Unit)? = null) {
    var localText by remember { mutableStateOf("") }
    val text = draft ?: localText
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { if (onDraftChange != null) onDraftChange(it) else localText = it }, label = { Text("内容") }, minLines = 2, shape = RoundedCornerShape(14.dp)) },
        confirmButton = { TextButton({ submit(text) }, enabled = text.isNotBlank()) { Text("确定") } },
        dismissButton = { TextButton(dismiss) { Text("取消") } },
    )
}
