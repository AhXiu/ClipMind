package com.clipmind.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.clipmind.android.data.LocalCard

@Composable
fun ReadingResults(card: LocalCard, state: MainUiState, vm: MainViewModel) {
    val analysis = card.analysis?.takeIf { it.schemaVersion >= 2 } ?: return
    val uri = LocalUriHandler.current
    var sourceBook by remember { mutableStateOf<com.clipmind.android.network.dto.AnalysisBook?>(null) }
    var quote by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    FlatCard(Modifier.fillMaxWidth()) {
        Text("AI 延伸阅读",style = MaterialTheme.typography.titleLarge)
        analysis.questions.forEachIndexed { index,q -> Text("${index+1}. $q") }
        Text("推荐书籍",style = MaterialTheme.typography.titleLarge)
        if (analysis.books.isEmpty()) Text("暂无通过元数据校验的未读书籍；不会展示未经验证的候选。")
        analysis.books.filter { it.verified }.forEach { book ->
            val proof = state.readerDocuments.mapNotNull { it.attribution }.firstOrNull { it.cardId == card.id && it.revision == card.contentRevision && it.workKey == book.openLibraryKey && card.content.contains(it.quote) }
            Text("《${book.title}》 · ${book.author} · ${if(proof != null) "高确定（人工出处核对）" else if(book.confidence=="semantic") "语义匹配" else "仅推测"}")
            Text(book.reason.orEmpty())
            Row {
                TextButton({ book.openLibraryKey?.takeIf { it.matches(Regex("/works/OL[0-9]+W")) }?.let { runCatching { uri.openUri("https://openlibrary.org$it") } } }) { Text("核对书籍") }
                TextButton({ vm.learning.readBook(book.title) }) { Text("标记已读") }
                TextButton({ sourceBook=book; quote=""; location="" }) { Text("核对出处") }
            }
        }
        Text("书籍存在不证明摘抄出处。无出处证据时不标为高确定。",style=MaterialTheme.typography.bodySmall)
        Text("延伸阅读",style=MaterialTheme.typography.titleLarge)
        if (analysis.articles.isEmpty()) Text("暂无经过检索和访问校验的文章。")
        analysis.articles.forEach { article ->
            Text(article.title,style=MaterialTheme.typography.titleMedium)
            Text(article.summary)
            if (!article.reason.isNullOrBlank()) {
                Text("${com.clipmind.android.knowledge.relationLabel(article.relation.orEmpty())}视角 · 为什么读", style = MaterialTheme.typography.labelLarge)
                Text(article.reason)
                Text("摘抄依据：${article.sourceQuote.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                Text("文章依据：${article.quote.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                Text("关联为 AI 推论，请结合全文核对适用条件。", style = MaterialTheme.typography.bodySmall)
            }
            Text("检查时间：${article.checkedAt}；摘要基于获取到的正文片段，链接以后可能失效。",style=MaterialTheme.typography.bodySmall)
            TextButton({ runCatching { uri.openUri(article.url) } }) { Text("阅读原文") }
        }
        analysis.warnings.forEach { Text(readingWarning(it),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error) }
    }
    sourceBook?.let { book -> AlertDialog(onDismissRequest={ sourceBook=null },title={ Text("核对《${book.title}》出处") },text={ Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("只有你已实际核对原书，才能确认。记录页码／章节和与摘抄逐字一致的引文；系统不将模型猜测当作出处。")
        OutlinedTextField(location,{location=it},label={Text("版本、页码或章节")})
        OutlinedTextField(quote,{quote=it},label={Text("已核对原文（20–800字符）")})
    } },confirmButton={ TextButton({ vm.learning.bookSource(card,book.openLibraryKey!!,quote,location); sourceBook=null },enabled=location.isNotBlank() && quote.length in 20..800 && card.content.contains(quote)) { Text("我已核对原书并确认") } },dismissButton={TextButton({sourceBook=null}){Text("取消")}}) }
}

internal fun readingWarning(code: String): String = when (code) {
    "NO_RELEVANT_VERIFIED_ARTICLES" -> "未找到有充分原文依据且能拓展当前摘抄的文章，不凑推荐数量。"
    "ARTICLE_SEARCH_NOT_CONFIGURED" -> "尚未配置文章搜索，未进行外部检索。"
    "ARTICLE_SEARCH_FAILED" -> "文章检索失败，本次未返回文章。"
    "NO_VERIFIED_UNREAD_BOOKS" -> "候选书籍未通过真实性或未读校验。"
    else -> code
}
