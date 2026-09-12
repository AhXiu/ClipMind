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
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(state: MainUiState, vm: MainViewModel, padding: PaddingValues, listState: LazyListState) {
    val session = vm.librarySession
    val query = session.query
    val source = session.source
    val time = session.time
    val ai = session.ai
    val ascending = session.ascending
    val selected = session.selected
    fun select(ids: Set<Long>) { vm.librarySession = vm.librarySession.copy(selected = ids) }
    var showTag by remember { mutableStateOf(false) }
    var showTopic by remember { mutableStateOf(false) }
    var confirmAi by remember { mutableStateOf(false) }
    val notes = state.readerDocuments.mapNotNull { it.annotation }.groupBy({ it.cardId }, { it.text })
    val cards = filterCards(state.localCards, query, source, time, ai, ascending, state.clock, notes)
    val sources = state.localCards.mapNotNull { it.sourceApp }.distinct()
    LaunchedEffect(state.localCards.map { it.id }) { select(selected.intersect(state.localCards.map { it.id }.toSet())) }

    Scaffold(
        modifier = Modifier.padding(padding),
        containerColor = MaterialTheme.colorScheme.background,
    ) { inner ->
        LazyColumn(
            Modifier.fillMaxSize().padding(inner),
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                OutlinedTextField(
                    query,
                    { vm.librarySession = session.copy(query = it) },
                    Modifier.fillMaxWidth(),
                    placeholder = { Text("搜索原文、想法、标签或来源") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({ vm.librarySession = session.copy(filtersExpanded = !session.filtersExpanded) }) { Text(if (session.filtersExpanded) "收起筛选" else "筛选与排序") }
                    if (query.isNotBlank() || source != null || time != CardTimeFilter.ALL || ai != CardAiFilter.ALL) TextButton({ vm.librarySession = LibrarySession(selected = selected) }) { Text("清除条件") }
                }
                if (session.filtersExpanded) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    FilterChip(source == null, { vm.librarySession = session.copy(source = null) }, { Text("全部来源") })
                    sources.forEach { value -> FilterChip(source == value, { vm.librarySession = session.copy(source = value) }, { Text(value) }) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    AssistChip({ vm.librarySession = session.copy(time = CardTimeFilter.entries[(time.ordinal + 1) % CardTimeFilter.entries.size]) }, { Text(time.label()) })
                    AssistChip({ vm.librarySession = session.copy(ai = CardAiFilter.entries[(ai.ordinal + 1) % CardAiFilter.entries.size]) }, { Text("AI ${ai.label()}") })
                    AssistChip({ vm.librarySession = session.copy(ascending = !ascending) }, { Text(if (ascending) "最早优先" else "最新优先") })
                }
                }
            }
            if (selected.isNotEmpty()) {
                item {
                    FlatCard(Modifier.fillMaxWidth()) {
                        Text("已选择 ${selected.size} 张卡片", style = MaterialTheme.typography.titleMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            Button({ showTag = true }) { Text("加标签") }
                            OutlinedButton({ showTopic = true }) { Text("加入主题") }
                            OutlinedButton({ confirmAi = true }, enabled = state.aiEnabled) { Text("帮我整理") }
                            OutlinedButton({ vm.knowledge.prepare(selected) }, enabled = selected.size in 2..8 && !state.knowledge.running && state.aiEnabled) { Text("多卡归纳") }
                            TextButton({ vm.deleteCards(selected) }) { Text("删除") }
                            TextButton({ select(emptySet()) }) { Text("取消选择") }
                        }
                    }
                }
            }
            item { SectionHeader("${cards.size} 张卡片") }
            if (cards.isEmpty()) item {
                if (state.localCards.isEmpty()) FlatCard(Modifier.fillMaxWidth()) {
                    Text("从一张卡片开始", style = MaterialTheme.typography.headlineSmall)
                    Text("留下一段摘抄，或写下刚刚想到的事。无需开启采集或配置 AI。")
                    Button(vm::openComposer) { Text("记录第一张卡片") }
                } else EmptyState("没有找到卡片", "试试其他关键词或清除筛选条件")
            }
            items(cards, key = { it.id }) { card ->
                val checked = card.id in selected
                Card(
                    Modifier.fillMaxWidth().combinedClickable(
                        onClick = { if (selected.isEmpty()) vm.openCard(card.id) else select(selected.toggle(card.id)) },
                        onLongClick = { select(selected.toggle(card.id)) },
                    ),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        if (checked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(highlightMatch(searchSnippet(card.content, query), query), maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                        if (query.isNotBlank()) {
                            val metadata = card.tags.map { "#${it.name}" } + notes[card.id].orEmpty().map { "我的想法：$it" } + listOfNotNull(card.sourceUrl)
                            metadata.firstOrNull { it.contains(query.trim(), true) }?.let { Text(highlightMatch(searchSnippet(it, query), query), maxLines = 2) }
                        }
                        Text(
                            highlightMatch("${card.sourceApp ?: "未知来源"} · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(card.capturedAt))}", query),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            StatusPill(if (card.contentAvailable) "已存本机" else "内容无法解密", positive = card.contentAvailable)
                            if (card.sync.analysisState() !in setOf(CardAnalysisState.LOCAL, CardAnalysisState.CONFIRM)) StatusPill(card.sync.aiLabel())
                            card.tags.firstOrNull()?.let { StatusPill("#${it.name}") }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(72.dp)) }
        }
    }
    if (showTag) TextEntryDialog("为 ${selected.size} 张卡片添加标签", { showTag = false }, { vm.addTag(selected, it); select(emptySet()); showTag = false })
    if (showTopic) AddToTopicDialog(state, vm, selected) { showTopic = false }
    if (confirmAi) AnalysisConsent(state, selected, { confirmAi = false }) { vm.queueAi(selected); confirmAi = false }
}

internal fun Set<Long>.toggle(id: Long) = if (id in this) this - id else this + id

@Composable
internal fun highlightMatch(text: String, query: String): AnnotatedString {
    val needle = query.trim()
    val color = MaterialTheme.colorScheme.primaryContainer
    return buildAnnotatedString {
        append(text)
        if (needle.isNotEmpty()) {
            var start = text.indexOf(needle, ignoreCase = true)
            while (start >= 0) {
                addStyle(SpanStyle(background = color, fontWeight = FontWeight.Bold), start, start + needle.length)
                start = text.indexOf(needle, start + needle.length, ignoreCase = true)
            }
        }
    }
}

@Composable
internal fun AnalysisConsent(state: MainUiState, ids: Set<Long>, dismiss: () -> Unit, confirm: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text("整理 ${ids.size} 张卡片？") },
        text = { Text("原文将发送至 ${state.aiMode.label} 和 ClipMind 后端，可能产生 API 费用。新任务使用 ${state.aiModel}；旧版本和已导出的副本不会被撤回。个人想法不会随此操作发送。") },
        confirmButton = { TextButton(confirm) { Text("确认发送并整理") } }, dismissButton = { TextButton(dismiss) { Text("取消") } })
}
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
