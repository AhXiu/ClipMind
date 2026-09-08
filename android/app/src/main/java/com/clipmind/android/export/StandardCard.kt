package com.clipmind.android.export

import com.clipmind.android.data.LocalCard
import java.time.Instant
import java.time.ZoneId

internal fun standardCard(card: LocalCard, links: String): String = buildString {
    val analysis = card.analysis
    append("# 摘抄｜${Instant.ofEpochMilli(card.capturedAt).atZone(ZoneId.systemDefault()).toLocalDate()}\n")
    append("> 来源：${escapeMarkdownInline(card.sourceApp ?: "来源未知")}\n> 原文链接：${escapeMarkdownInline(card.sourceUrl ?: "来源未知")}\n\n## 原文摘抄\n\n")
    var fence = "```"; while (card.content.contains(fence)) fence += "`"
    append("${fence}text\n${card.content}\n$fence\n\n## AI解读\n")
    if (analysis == null) append("尚未生成。\n")
    else if (analysis.schemaVersion < 2) append("旧版解读（尚未迁移到标准三段）：\n${escapeMarkdownInline(analysis.interpretation.summary)}\n${escapeMarkdownInline(analysis.interpretation.insight)}\n${escapeMarkdownInline(analysis.interpretation.action)}\n")
    else append("1. 核心释义：${escapeMarkdownInline(analysis.interpretation.summary)}\n2. 场景应用：${escapeMarkdownInline(analysis.interpretation.insight)}\n3. 认知启发：${escapeMarkdownInline(analysis.interpretation.action)}\n")
    append("\n## 智能标签\n一级：#${escapeMarkdownInline(analysis?.primaryTag ?: card.tags.firstOrNull { it.level == 1 }?.name ?: "待分类")}\n二级：")
    card.tags.filter { it.level == 2 }.forEach { tag -> append(" #${escapeMarkdownInline(tag.name)}（${if(tag.status == "pending") "新增待确认" else "已确认标签"}）") }
    append("\n\n## 知识关联\n${links.ifBlank { "尚无已确认关联。" }}\n\n## 推荐书籍\n")
    val books = analysis?.books.orEmpty().filter { it.verified && it.openLibraryKey?.matches(Regex("/works/OL[0-9]+W")) == true }
    if (books.isEmpty()) append("暂无通过真实性验证的书籍。\n")
    books.forEachIndexed { i,b ->
        val proof = card.bookSources.firstOrNull { it.workKey == b.openLibraryKey && it.revision == card.contentRevision && card.content.contains(it.quote) }
        val level = if(proof != null) "高确定（人工出处核对）" else if(b.confidence == "semantic") "语义匹配" else "仅推测"
        append("${i+1}. 《${escapeMarkdownInline(b.title)}》｜$level｜匹配说明：${escapeMarkdownInline(b.reason.orEmpty())}｜<https://openlibrary.org${b.openLibraryKey}>\n")
        if(proof != null) append("> 出处：${escapeMarkdownInline(proof.location)}；核对引文：${escapeMarkdownInline(proof.quote)}\n")
    }
    append("\n## 延伸阅读\n")
    val articles = analysis?.articles.orEmpty()
    if (articles.isEmpty()) append("暂无经检索和访问校验的文章。\n")
    articles.forEachIndexed { i,a -> append("${i+1}. 《${escapeMarkdownInline(a.title)}》｜摘要：${escapeMarkdownInline(a.summary)}｜url：${escapeMarkdownInline(a.url)}\n") }
}
