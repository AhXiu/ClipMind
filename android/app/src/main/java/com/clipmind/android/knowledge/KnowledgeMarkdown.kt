package com.clipmind.android.knowledge

import com.clipmind.android.export.escapeMarkdownInline

fun renderKnowledgeMarkdown(payload: KnowledgeNotePayload, stale: Boolean, sourceLink: (String) -> String? = { null }): String = buildString {
    append("# ${escapeMarkdownInline(payload.response.result.title)}\n\n")
    append(if (stale) "> 历史快照：来源已变化，以下结论需要重新验证。\n\n" else "> AI 归纳：引用经原文匹配校验，推论仍需人工核对。\n\n")
    append("模型：${escapeMarkdownInline(payload.response.provider)} / ${escapeMarkdownInline(payload.response.model)}\n\n")
    payload.response.result.points.forEach { point ->
        val heading = when (point.kind) { "agreement" -> "共同观点"; "difference" -> "观点分歧"; "question" -> "待验证问题"; else -> "要点归纳" }
        append("## $heading\n\n${escapeMarkdownInline(point.text)}\n\n")
        point.evidence.forEach { evidence ->
            val source = payload.input.cards.first { it.id == evidence.cardId }
            append("> ${escapeMarkdownInline(evidence.quote)}\n\n")
            append("来源：${sourceLink(source.id) ?: "卡片 ${source.id}"}，内容版本 ${source.revision}\n\n")
        }
    }
}
