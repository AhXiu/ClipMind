package com.clipmind.android.knowledge

import org.junit.Assert.*
import org.junit.Test

class LocalRelationFinderTest {
    private val source = KnowledgeCard("1", 1, "数据库事务通过日志保证原子性，恢复时重放日志。")
    private val related = KnowledgeCard("2", 1, "数据库事务依靠日志恢复数据，日志设计影响恢复效率。")
    @Test fun retrievesChineseRelatedCardWithVerbatimEvidence() {
        val result = LocalRelationFinder.find(source, listOf(source, related, KnowledgeCard("3", 1, "春日山野赏花旅行攻略")), emptySet())
        assertEquals(listOf("2"), result.map { it.target.id })
        assertTrue(source.text.contains(result.single().evidence.sourceQuote))
        assertTrue(related.text.contains(result.single().evidence.targetQuote))
    }
    @Test fun honorsRejectionsAndNeverRecommendsItself() {
        assertTrue(LocalRelationFinder.find(source, listOf(source, related), setOf("2")).isEmpty())
    }
    @Test fun capsAndOrdersDeterministically() {
        val cards = (2..12).map { related.copy(id = it.toString()) }
        val first = LocalRelationFinder.find(source, cards, emptySet())
        assertEquals(5, first.size)
        assertEquals(first, LocalRelationFinder.find(source, cards.reversed(), emptySet()))
    }
    @Test fun supportsEnglishButDoesNotForceUnrelatedMatches() {
        val a = KnowledgeCard("1", 1, "Kotlin database transaction atomicity")
        val b = KnowledgeCard("2", 1, "Kotlin database recovery")
        assertEquals(1, LocalRelationFinder.find(a, listOf(b), emptySet()).size)
        assertTrue(LocalRelationFinder.find(a, listOf(b.copy(text = "sunshine mountain walking")), emptySet()).isEmpty())
    }
}
