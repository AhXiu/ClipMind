package com.clipmind.android.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.clipmind.android.data.TagEntity
import com.clipmind.android.reading.*
import java.time.ZoneId

@Composable
fun LearningDialogs(state: MainUiState, vm: MainViewModel) {
    val preview = state.learning.preview ?: return
    AlertDialog(onDismissRequest = vm.learning::dismiss, title = { Text("核对处理范围") }, text = {
        LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when(preview) {
                is LearningPreview.Recommend -> {
                    item { Text("发送分类统计、已读书名和历史推荐书名给后端模型，以探索收藏覆盖较低的主题。不发送原文或批注。候选书将经 OpenLibrary 校验，并过滤已读、已推荐和重复作者。") }
                    item { Text("分类统计：${preview.request.counts}\n已读：${preview.request.readBooks.joinToString("、")}\n已推荐：${preview.request.previousBooks.joinToString("、")}") }
                }
                is LearningPreview.Analysis -> {
                    item { Text("完整分析使用配置的后端模型（不是设备 BYOK）。发送下列原文、已确认二级标签和已读书名；标签池参与优先复用，书名用于过滤。可能产生费用。") }
                    item { Text("卡片 ${preview.plan.card.id} · v${preview.plan.card.contentRevision}\n${preview.plan.request.text}") }
                    item { Text("标签池：${preview.plan.request.knownTags.joinToString("、")}\n已读书：${preview.plan.request.readBooks.joinToString("、")}") }
                    item { Text(if (preview.plan.request.searchArticles) "另将3个主题关键词发给 Brave Search，访问白名单文章；正文片段与当前摘抄交给同一后端模型判断阅读价值并摘要，最多校验8篇、返回3篇。保留双方引文，关联需人工核对。无配置时明确提示，不生成链接。" else "不调用外部文章搜索。候选书名将经 OpenLibrary 校验。") }
                }
                is LearningPreview.Semantic -> {
                    item { Text("真实语义检索：${preview.plan.model}。以下 ${preview.plan.pending.size} 张需更新向量，将经后端发送给 OpenAI；向量加密保存在本机。全库 ${preview.plan.cards.size} 张参与本地召回，从最近20张中兼顾相关性和多样性选出最多5张。相似度不代表立场，对立关系必须有可比命题和双方证据。") }
                    item { Text("随后将源卡片与最多5张召回卡片的全文分5次交给当前配置的 LLM（${state.aiMode}）判断，可能产生费用；不自动确认关联。下面是本次可参与判断的完整范围。") }
                    items(preview.plan.cards, key = { it.id }) { Text("卡片 ${it.id} · v${it.revision}\n${it.text}") }
                }
                is LearningPreview.Week -> {
                    item { Text("发送 ${preview.plan.start} 至 ${preview.plan.end} 的 ${preview.plan.cards.size} 张摘抄给后端模型归纳，可能产生 API 费用。批注不会发送。生成周报将替换本周已有报告，引用作为独立历史副本保留。") }
                    items(preview.plan.cards, key = { it.id }) { Text("卡片 ${it.id} · v${it.contentRevision}\n${it.content}") }
                }
                is LearningPreview.Notion -> {
                    item { Text("将以下内容发送至 Notion，在已授权父页面 ${preview.plan.page} 下创建新页面。Notion 中的副本需单独删除；请求不自动重试。") }
                    item { Text(preview.plan.text) }
                }
            }
        }
    }, confirmButton = { TextButton(vm.learning::confirm, enabled = !state.learning.busy) { Text("确认处理") } }, dismissButton = { TextButton(vm.learning::dismiss) { Text("取消") } })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KnowledgeOverview(state: MainUiState, vm: MainViewModel) {
    var tags by remember { mutableStateOf(false) }
    var book by remember { mutableStateOf(false) }
    val counts = state.localCards.mapNotNull { it.analysis?.primaryTag }.groupingBy { it }.eachCount()
    FlatCard(Modifier.fillMaxWidth()) {
        Text("让摘抄连接起来", style = MaterialTheme.typography.headlineSmall)
        Text("在卡片详情发起完整解读与向量关联；到复习页将观点转化为自己的理解。")
        Text("知识覆盖 · ${state.localCards.size} 张摘抄", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { ReadingContract.PRIMARY.forEach { Text("$it ${counts[it] ?: 0}", Modifier.padding(4.dp)) } }
        val sparse = ReadingContract.PRIMARY.sortedBy { counts[it] ?: 0 }.take(2)
        Text("可探索：${sparse.joinToString("、")}。这里只反映收藏覆盖，不判断你的知识能力。", style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ tags = true }) { Text("标签管理 · ${state.allTags.count { it.status == "pending" }} 待确认") }
            OutlinedButton({ book = true }) { Text("记录已读书籍") }
            OutlinedButton({ vm.learning.prepareRecommendations(state.localCards) }, enabled = state.aiEnabled && !state.learning.busy) { Text("探索低覆盖主题") }
        }
        if (state.learning.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(state.learning.progress.ifBlank { "正在处理" })
            TextButton(vm.learning::cancel) { Text("取消等待") }
        }
        state.readerDocuments.filter { it.kind == "read_book" }.take(10).forEach { item ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(item.text, Modifier.weight(1f))
                TextButton({ vm.learning.deleteDocument(item.id) }) { Text("移出已读") }
            }
        }
        state.readerDocuments.filter { it.kind == "recommendation" }.take(3).forEach { Text(it.text) }
    }
    if (tags) TagManager(state.allTags, vm, { tags = false })
    if (book) TextEntryDialog("已读书名（用于推荐过滤）", { book = false }, { vm.learning.readBook(it); book = false })
}

@Composable
private fun TagManager(tags: List<TagEntity>, vm: MainViewModel, close: () -> Unit) {
    var action by remember { mutableStateOf<Pair<TagEntity,String>?>(null) }
    AlertDialog(onDismissRequest = close, title = { Text("受控标签管理") }, text = {
        LazyColumn(Modifier.heightIn(max = 460.dp)) {
            item { Text("一级分类固定不可编辑。二级标签确认后进入历史池；合并将迁移全部引用，删除不会删除原文。") }
            items(tags, key = { it.id }) { tag ->
                Column(Modifier.padding(vertical = 8.dp)) {
                    Text("${tag.name} · ${if(tag.level == 1) "一级" else if(tag.status == "pending") "新增待确认" else "二级已确认"}")
                    if (tag.level == 2) Row {
                        if (tag.status == "pending") TextButton({ vm.learning.tag(tag.id,"confirm") }) { Text("确认") }
                        TextButton({ action = tag to "rename" }) { Text("改名") }
                        TextButton({ action = tag to "merge" }) { Text("合并") }
                        TextButton({ action = tag to "delete" }) { Text("删除") }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(close) { Text("关闭") } })
    action?.let { (tag, mode) ->
        if (mode == "delete") AlertDialog(onDismissRequest = { action = null },title = { Text("删除标签 ${tag.name}？") },text = { Text("将移除所有卡片上的此标签引用，不删除卡片正文。") },confirmButton = { TextButton({ vm.learning.tag(tag.id,mode); action = null }) { Text("删除") } },dismissButton = { TextButton({ action = null }) { Text("取消") } })
        else TextEntryDialog(if(mode == "merge") "合并 ${tag.name} 到已有已确认标签" else "重命名 ${tag.name}", { action = null }, { vm.learning.tag(tag.id,mode,it); action = null })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReviewScreen(state: MainUiState, vm: MainViewModel, padding: PaddingValues, onCopy: (String) -> Unit) {
    val due = ReviewPolicy.due(state.localCards,state.reviewSchedule,state.learningPreferences.dailyLimit,state.clock,ZoneId.systemDefault())
    val card = due.firstOrNull()
    var reveal by remember(card?.id, card?.contentRevision) { mutableStateOf(false) }
    val answer = card?.let { state.learning.annotationDrafts["${it.id}:${it.contentRevision}"] }.orEmpty()
    var deleteDoc by remember { mutableStateOf<DocumentView?>(null) }
    var section by remember { mutableStateOf("今天") }
    var deleteDraft by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("今天", "想法记录", "每周回顾", "Notion").forEach { value -> FilterChip(section == value, { section = value }, { Text(value) }) } } }
        if (section == "今天") {
        item {
            FlatCard(Modifier.fillMaxWidth()) {
                Text(if (state.localCards.isEmpty()) "从记录开始" else if(due.isEmpty()) "今天先到这里" else "今天，重遇 ${due.size} 张卡片", style = MaterialTheme.typography.headlineSmall)
                Text(if (state.localCards.isEmpty()) "记录第一张卡片后，再来慢慢回顾。" else "每天最多 ${state.learningPreferences.dailyLimit} 张。不必背诵，想想它与你的生活有什么关系。")
            }
        }
        if (card != null) item {
            FlatCard(Modifier.fillMaxWidth()) {
                Text("卡片 ${card.id} · ${card.analysis?.primaryTag ?: "待分类"}", style = MaterialTheme.typography.titleMedium)
                Text(card.analysis?.interpretation?.summary ?: "回忆这条摘抄表达了什么，并想一个可以应用的场景。")
                TextButton({ reveal = !reveal }) { Text(if(reveal) "收起原文" else "回忆后查看原文") }
                if (reveal) Text(card.content)
                card.analysis?.takeIf { it.schemaVersion == 2 }?.let { a ->
                    a.questions.forEachIndexed { i,q -> Text("${i+1}. $q") }
                }
                DraftRecoveryNotice(state, vm, "${card.id}:${card.contentRevision}")
                OutlinedTextField(answer,{ vm.learning.draft(card,it) },Modifier.fillMaxWidth(),label = { Text("我的思考／应用经历（仅本机保存）") },minLines = 3, enabled = state.learning.draftsLoaded && "${card.id}:${card.contentRevision}" !in state.learning.unreadableDrafts)
                state.learning.draftStatus["${card.id}:${card.contentRevision}"]?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                TextButton({ vm.learning.saveNote(card,answer) },enabled = answer.isNotBlank() && !state.learning.localBusy) { Text("保存想法") }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Recall.entries.forEach { recall -> OutlinedButton({ vm.learning.review(card,recall) },enabled = reveal && !state.learning.localBusy) { Text(recall.label) } } }
                if (answer.isNotBlank()) Text("完成回顾时会一起保存想法，不需要清空。", style = MaterialTheme.typography.bodySmall)
                Row { TextButton({ vm.learning.skipToday(card) }, enabled = !state.learning.localBusy) { Text("今天跳过") }; TextButton({ vm.openCard(card.id) }) { Text("查看卡片") } }
            }
        }
        }
        if (section == "每周回顾") {
        item {
            FlatCard(Modifier.fillMaxWidth()) {
                Text("每周回顾",style = MaterialTheme.typography.titleLarge)
                Button({ vm.learning.prepareWeek(state.localCards) },enabled = state.aiEnabled && !state.learning.busy) { Text("生成本周知识简报") }
                if (state.learning.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); TextButton(vm.learning::cancel) { Text("取消等待") } }
            }
        }
        }
        if (section == "Notion") item {
            FlatCard(Modifier.fillMaxWidth()) {
                Text("按需分享，不自动同步", style = MaterialTheme.typography.titleLarge)
                Text("先在设置 → 数据与备份中配置 Notion。每次发送前都会预览确认，远端副本需单独管理。")
                OutlinedButton({ vm.learning.prepareNotion("ClipMind 今日回顾",due.joinToString("\n\n") { "卡片 ${it.id}\n${it.content}" }) },enabled = due.isNotEmpty() && !state.learning.busy) { Text("预览今日卡片") }
            }
        }
        if (section != "今天") {
        if (section == "想法记录") {
            item { DraftRecoveryNotice(state, vm) }
            val drafts = state.learning.annotationDrafts.filterKeys { it != "manual" }.toList()
            if (drafts.isNotEmpty()) item { SectionHeader("未完成草稿") }
            items(drafts, key = { "draft-${it.first}" }) { (key, text) ->
                FlatCard(Modifier.fillMaxWidth()) {
                    Text("卡片 ${key.substringBefore(':')} · 原文版本 ${key.substringAfter(':')}", style = MaterialTheme.typography.bodySmall)
                    Text(text)
                    Row { TextButton({ onCopy(text) }) { Text("复制") }; TextButton({ deleteDraft = key }) { Text("删除草稿") } }
                }
            }
        }
        val documents = state.readerDocuments.filter { if (section == "想法记录") it.kind in setOf("annotation", "book_source") else it.kind == "weekly" }
        if (documents.isEmpty()) item { EmptyState("这里还没有记录", if (section == "想法记录") "在卡片里写下想法，无需启用 AI。" else "每周回顾是可选能力，日常记录不依赖周报。") }
        items(documents, key = { it.id }) { document ->
            var expanded by remember(document.id) { mutableStateOf(false) }
            FlatCard(Modifier.fillMaxWidth()) {
                Text(document.text, maxLines = if(expanded) Int.MAX_VALUE else 4)
                TextButton({ expanded = !expanded }) { Text(if(expanded) "收起" else "展开") }
                Row {
                    TextButton({ onCopy(document.text) }) { Text("复制") }
                    if (section == "Notion") TextButton({ vm.learning.prepareNotion("ClipMind 知识周报",document.text) }) { Text("预览发送") }
                    else TextButton({ deleteDoc = document }) { Text("删除") }
                }
            }
        }
        item { Text("周报与批注是独立历史副本；删除原卡片不会删除它们。Notion 副本也需单独删除。",style = MaterialTheme.typography.bodySmall) }
        }
        item { Spacer(Modifier.height(80.dp)) }
    }
    deleteDoc?.let { doc -> AlertDialog(onDismissRequest = { deleteDoc = null },title = { Text("删除这份本地记录？") },text = { Text("不会删除原卡片，也不会删除 Notion 中的副本。") },confirmButton = { TextButton({ vm.learning.deleteDocument(doc.id); deleteDoc = null }) { Text("删除") } },dismissButton = { TextButton({ deleteDoc = null }) { Text("取消") } }) }
    deleteDraft?.let { key -> AlertDialog(onDismissRequest = { deleteDraft = null }, title = { Text("删除未完成草稿？") }, text = { Text("只删除本机这份草稿，不影响原文或已保存的想法。") }, confirmButton = { TextButton({ vm.learning.draftText(key, ""); deleteDraft = null }) { Text("删除") } }, dismissButton = { TextButton({ deleteDraft = null }) { Text("取消") } }) }
}

fun valueLabel(value: String?) = when(value) { "high" -> "高"; "low" -> "低"; "medium" -> "中"; else -> "尚未评估" }

@Composable
fun LearningSettingsView(state: MainUiState, vm: MainViewModel, section: String = "提醒") {
    var proposed by remember { mutableStateOf<LearningPreferences?>(null) }
    var key by remember { mutableStateOf("") }
    var page by remember(state.learningPreferences.notionPage) { mutableStateOf(state.learningPreferences.notionPage) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (!granted) vm.showMessage("通知权限未授权，仍可在复习页或小组件查看") }
    val p = state.learningPreferences
    FlatCard(Modifier.fillMaxWidth()) {
        if (section == "提醒") {
        Text("回顾节奏",style = MaterialTheme.typography.titleLarge)
        Row { Text("每日数量",Modifier.weight(1f)); (3..5).forEach { n -> FilterChip(p.dailyLimit == n,{ vm.setLearningPreferences(p.copy(dailyLimit=n)) },{ Text("$n") }) } }
        SettingToggle("每日复习提醒",p.reminders) { enabled -> vm.setLearningPreferences(p.copy(reminders=enabled)); if(enabled && Build.VERSION.SDK_INT>=33) permission.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
        if (section == "AI") {
        Text("自动任务（高级）",style = MaterialTheme.typography.titleLarge)
        SettingToggle("自动价值评级、标签与思考题",p.automaticAnalysis) { if(it) proposed=p.copy(automaticAnalysis=true) else vm.setLearningPreferences(p.copy(automaticAnalysis=false)) }
        SettingToggle("自动向量关联（全库范围）",p.automaticRelations) { if(it) proposed=p.copy(automaticRelations=true) else vm.setLearningPreferences(p.copy(automaticRelations=false)) }
        SettingToggle("自动生成上周简报",p.weeklyReports) { if(it) proposed=p.copy(weeklyReports=true) else vm.setLearningPreferences(p.copy(weeklyReports=false)) }
        Text("自动任务每12小时尽力运行，受系统省电限制；不保证精确时刻。每轮最多分析3张、关联1张，失败不自动重试。关闭AI总开关会暂停所有模型任务。",style = MaterialTheme.typography.bodySmall)
        }
        if (section == "数据") {
        Text("Notion（仅在预览确认后发送）",style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(page,{ page=it },Modifier.fillMaxWidth(),label={ Text("父页面 UUID，含连字符") })
        OutlinedTextField(key,{ key=it },Modifier.fillMaxWidth(),label={ Text("Integration Token（加密保存，不回显）") },visualTransformation=PasswordVisualTransformation())
        Row {
            TextButton({ runCatching { java.util.UUID.fromString(page) }.onSuccess { vm.setLearningPreferences(p.copy(notionPage=page)); if(key.isNotBlank()) { vm.saveNotionKey(key); key="" } }.onFailure { vm.showMessage("请输入有效页面 UUID") } }) { Text("保存配置") }
            TextButton({ vm.clearNotionKey(); key="" }) { Text("清除 Token") }
        }
        }
    }
    proposed?.let { value -> AlertDialog(onDismissRequest={ proposed=null },title={ Text("授权自动处理内容？") },text={ Text("开启后，已存及未来采集的卡片可能在后台发送：完整分析向后端模型发送原文、已确认标签和已读书名；向量关联经后端向 OpenAI 发送全库原文建立索引，并将 Top-5 配对交给所选 LLM；周报向后端模型发送上周摘抄。会产生外部模型费用，不会自动确认关系或发送 Notion。可随时关闭；已被服务商接受的请求无法保证撤回。") },confirmButton={ TextButton({ vm.setLearningPreferences(value); proposed=null }) { Text("同意并开启") } },dismissButton={ TextButton({ proposed=null }) { Text("取消") } }) }
}

@Composable private fun SettingToggle(label: String, checked: Boolean, change: (Boolean)->Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween) { Text(label,Modifier.weight(1f).padding(top=12.dp)); Switch(checked,change) }
}
