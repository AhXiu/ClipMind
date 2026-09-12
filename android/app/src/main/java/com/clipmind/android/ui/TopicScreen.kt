package com.clipmind.android.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.clipmind.android.data.LocalCard
import com.clipmind.android.reading.DocumentView
import com.clipmind.android.reading.Topic

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TopicScreen(state: MainUiState, vm: MainViewModel, padding: PaddingValues) {
    val topics = state.readerDocuments.filter { it.topic != null }
    val active = topics.firstOrNull { it.id == vm.selectedTopicId }
    var editing by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    var selected by remember(active?.id) { mutableStateOf(emptySet<Long>()) }
    var showAi by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(active != null) { vm.selectedTopicId = null }
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (active?.topic != null) {
            val topic = active.topic
            item {
                TextButton({ vm.selectedTopicId = null }) { Text("全部主题") }
                FlatCard(Modifier.fillMaxWidth()) {
                    Text(topic.title, style = MaterialTheme.typography.headlineSmall)
                    Text(topic.note.ifBlank { "写下你对这个主题的理解，不必等 AI 来总结。" })
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ editing = true }) { Text("编辑主题与卡片") }
                        TextButton({ delete = true }) { Text("删除主题") }
                    }
                }
            }
            val cards = topic.cardIds.mapNotNull { id -> state.localCards.firstOrNull { it.id == id } }
            item { SectionHeader("${cards.size} 张卡片") }
            if (topic.cardIds.size > cards.size) item { Text("${topic.cardIds.size - cards.size} 张卡片已移除，编辑主题时可清理引用。", style = MaterialTheme.typography.bodySmall) }
            if (selected.isNotEmpty()) item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ vm.learning.saveTopic(active.id, topic.copy(cardIds = topic.cardIds - selected)) { selected = emptySet() } }, enabled = !state.learning.localBusy) { Text("从主题移出 ${selected.size} 张") }
                    OutlinedButton({ vm.knowledge.prepare(selected) }, enabled = selected.size in 2..8 && state.aiEnabled && !state.knowledge.running) { Text("AI 归纳（可选）") }
                    TextButton({ selected = emptySet() }) { Text("取消选择") }
                }
            }
            if (cards.isEmpty()) item { EmptyState("还没有加入卡片", "编辑主题，选择已有卡片；同一卡片可以属于多个主题。") }
            items(cards, key = { it.id }) { card ->
                SelectableCard(card, card.id in selected, {
                    if (selected.isEmpty()) vm.openCard(card.id) else selected = selected.toggle(card.id)
                }, { selected = selected.toggle(card.id) })
            }
        } else {
            item {
                FlatCard(Modifier.fillMaxWidth()) {
                    Text("按自己的思路组织", style = MaterialTheme.typography.headlineSmall)
                    Text("一个问题、一本书或一项计划，都可以是主题。把相关卡片放在一起，再写下你的理解。")
                    Button({ creating = true }) { Text("新建主题") }
                }
            }
            items(topics, key = { it.id }) { document ->
                val topic = document.topic!!
                Card(onClick = { vm.selectedTopicId = document.id }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(topic.title, style = MaterialTheme.typography.titleLarge)
                        Text("${topic.cardIds.count { id -> state.localCards.any { it.id == id } }} 张卡片", style = MaterialTheme.typography.bodySmall)
                        if (topic.note.isNotBlank()) Text(topic.note, maxLines = 3)
                    }
                }
            }
            item { TextButton({ showAi = !showAi }) { Text(if (showAi) "收起 AI 辅助与归纳笔记" else "AI 辅助与历史归纳笔记（可选）") } }
            if (showAi) {
                item { KnowledgeOverview(state, vm) }
                item { Text("在卡片库长按选中 2–8 张卡片，可生成独立的 AI 归纳笔记。不会覆盖你的主题笔记。") }
                if (state.knowledge.running) item { LinearProgressIndicator(Modifier.fillMaxWidth()); TextButton(vm.knowledge::cancel) { Text("取消等待") } }
                items(state.knowledge.notes, key = { "ai-${it.id}" }) { note ->
                    Card(onClick = { vm.knowledge.openNote(note.id) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(note.payload?.response?.result?.title ?: "笔记无法解密")
                            if (note.stale) Text("来源已变化，保留为历史快照")
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(80.dp)) }
    }
    if (creating || editing) TopicEditor(state, vm, if (editing) active else null, { creating = false; editing = false })
    if (delete && active != null) AlertDialog(onDismissRequest = { delete = false }, title = { Text("删除这个主题？") },
        text = { Text("只删除本机主题名称、主题笔记和分组关系，不删除卡片。导出的历史副本不会被删除。") },
        confirmButton = { TextButton({ vm.learning.deleteTopic(active.id); delete = false }) { Text("删除主题") } }, dismissButton = { TextButton({ delete = false }) { Text("取消") } })
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SelectableCard(card: LocalCard, selected: Boolean, open: () -> Unit, select: () -> Unit, status: String? = null) {
    Card(Modifier.fillMaxWidth().combinedClickable(onClick = open, onLongClick = select),
        colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (selected) Text("已选择", color = MaterialTheme.colorScheme.primary)
            Text(card.content, maxLines = 4)
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun TopicEditor(state: MainUiState, vm: MainViewModel, document: DocumentView?, close: () -> Unit, initialCards: Set<Long> = emptySet()) {
    val original = remember { document?.topic ?: Topic("", cardIds = initialCards.toList()) }
    var title by remember { mutableStateOf(original.title) }
    var note by remember { mutableStateOf(original.note) }
    var ids by remember { mutableStateOf(original.cardIds.toSet()) }
    var query by remember { mutableStateOf("") }
    var confirmDiscard by remember { mutableStateOf(false) }
    val dismiss: () -> Unit = {
        if (!state.learning.localBusy) {
            if (title != original.title || note != original.note || ids != original.cardIds.toSet()) confirmDiscard = true else close()
        }
    }
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (document == null) "新建主题" else "编辑主题") }, text = {
        LazyColumn(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                OutlinedTextField(title, { if (it.length <= 80) title = it }, Modifier.fillMaxWidth(), label = { Text("主题名称") }, singleLine = true)
                OutlinedTextField(note, { if (it.length <= 20000) note = it }, Modifier.fillMaxWidth(), label = { Text("我的主题笔记") }, minLines = 3, maxLines = 6)
                OutlinedTextField(query, { query = it }, label = { Text("搜索要加入的卡片") }, singleLine = true)
                Text("已选 ${ids.size} 张；仅本机保存")
                if (ids.any { id -> state.localCards.none { it.id == id } }) TextButton({ ids = ids.intersect(state.localCards.map { it.id }.toSet()) }) { Text("清理已删除卡片的引用") }
            }
            items(state.localCards.filter { query.isBlank() || it.content.contains(query, true) }, key = { it.id }) { card ->
                Row(Modifier.fillMaxWidth()) {
                    Checkbox(card.id in ids, { ids = ids.toggle(card.id) })
                    Text(card.content, Modifier.weight(1f).padding(top = 12.dp), maxLines = 3)
                }
            }
        }
    }, confirmButton = { TextButton({ vm.learning.saveTopic(document?.id, original.copy(title = title, note = note, cardIds = ids.toList()), close) }, enabled = title.isNotBlank() && !state.learning.localBusy) { Text("保存") } }, dismissButton = { TextButton(dismiss) { Text("取消") } })
    if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false }, title = { Text("放弃未保存的主题修改？") }, text = { Text("已保存的主题和卡片不会改变。本次编辑尚未保存。") }, confirmButton = { TextButton(close) { Text("放弃修改") } }, dismissButton = { TextButton({ confirmDiscard = false }) { Text("继续编辑") } })
}

@Composable
internal fun AddToTopicDialog(state: MainUiState, vm: MainViewModel, ids: Set<Long>, close: () -> Unit) {
    var create by remember { mutableStateOf(false) }
    if (create) TopicEditor(state, vm, null, close, ids)
    else AlertDialog(onDismissRequest = close, title = { Text("加入主题") }, text = {
        LazyColumn(Modifier.heightIn(max = 400.dp)) {
            item { TextButton({ create = true }) { Text("新建主题") } }
            items(state.readerDocuments.filter { it.topic != null }, key = { it.id }) { document ->
                TextButton({ vm.learning.saveTopic(document.id, document.topic!!.copy(cardIds = (document.topic.cardIds + ids).distinct()), close) }, enabled = !state.learning.localBusy) { Text(document.topic!!.title) }
            }
        }
    }, confirmButton = { TextButton(close) { Text("关闭") } })
}
