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
            Text("检查时间：${article.checkedAt}；摘要基于获取到的正文片段，链接以后可能失效。",style=MaterialTheme.typography.bodySmall)
            TextButton({ runCatching { uri.openUri(article.url) } }) { Text("阅读原文") }
        }
        analysis.warnings.forEach { Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error) }
    }
    sourceBook?.let { book -> AlertDialog(onDismissRequest={ sourceBook=null },title={ Text("核对《${book.title}》出处") },text={ Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("只有你已实际核对原书，才能确认。记录页码／章节和与摘抄逐字一致的引文；系统不将模型猜测当作出处。")
        OutlinedTextField(location,{location=it},label={Text("版本、页码或章节")})
        OutlinedTextField(quote,{quote=it},label={Text("已核对原文（20–800字符）")})
    } },confirmButton={ TextButton({ vm.learning.bookSource(card,book.openLibraryKey!!,quote,location); sourceBook=null },enabled=location.isNotBlank() && quote.length in 20..800 && card.content.contains(quote)) { Text("我已核对原书并确认") } },dismissButton={TextButton({sourceBook=null}){Text("取消")}}) }
}
