package com.clipmind.android.knowledge

import java.util.Locale
import kotlin.math.sqrt

data class LocalRelationCandidate(val source: KnowledgeCard, val target: KnowledgeCard, val score: Double, val evidence: RelationEvidence)

/** Lexical recall only: similarity is not a claim of logical agreement. No plaintext index is persisted. */
object LocalRelationFinder {
    private val words = Regex("[a-zA-Z][a-zA-Z0-9_]{2,}|[\\p{IsHan}]+")
    private val stop = setOf("the", "and", "that", "this", "with", "from", "have", "into", "我们", "一个", "可以", "进行", "因为", "所以", "这个", "以及", "通过", "需要", "不是", "就是")

    fun tokens(text: String): Set<String> = words.findAll(text.take(6000).lowercase(Locale.ROOT)).flatMap { match ->
        val value = match.value
        if (value.first() in 'a'..'z') sequenceOf(value)
        else value.windowed(2).asSequence()
    }.filter { it !in stop }.toSet()

    fun find(source: KnowledgeCard, cards: List<KnowledgeCard>, excluded: Set<String>, limit: Int = 5): List<LocalRelationCandidate> {
        val sourceTokens = tokens(source.text)
        if (sourceTokens.size < 2) return emptyList()
        return cards.asSequence().filter { it.id != source.id && it.id !in excluded }
            .mapNotNull { target ->
                val targetTokens = tokens(target.text)
                val shared = sourceTokens intersect targetTokens
                if (shared.size < 2 || targetTokens.isEmpty()) return@mapNotNull null
                val score = shared.size / sqrt((sourceTokens.size * targetTokens.size).toDouble())
                if (score < .18) return@mapNotNull null
                val terms = shared.sortedByDescending(String::length).take(5)
                LocalRelationCandidate(source, target, score, RelationEvidence(
                    "本机词语重合：${terms.joinToString("、")}。仅表示可能同主题，不代表观点一致。",
                    excerpt(source.text, terms), excerpt(target.text, terms),
                ))
            }.sortedWith(compareByDescending<LocalRelationCandidate> { it.score }.thenBy { it.target.id })
            .take(limit.coerceIn(0, 5)).toList()
    }

    private fun excerpt(text: String, terms: List<String>): String {
        val index = terms.map { text.indexOf(it, ignoreCase = true) }.filter { it >= 0 }.minOrNull() ?: 0
        val start = (index - 50).coerceAtLeast(0)
        return text.substring(start, (start + 240).coerceAtMost(text.length))
    }
}
