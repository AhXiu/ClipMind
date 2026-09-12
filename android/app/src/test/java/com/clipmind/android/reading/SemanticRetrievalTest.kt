package com.clipmind.android.reading

import com.clipmind.android.knowledge.KnowledgeCard
import org.junit.Assert.*
import org.junit.Test

class SemanticRetrievalTest {
    private val vector = listOf(1.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0)
    @Test fun topFiveUsesVectorsNotLexicalOverlapAndExcludesRejectedPairs() {
        val cards = (1L..10).map { KnowledgeCard(it.toString(),1,if(it==1L) "中文原文" else "Completely different words $it") }
        val cache = cards.map { IndexedVector(it.id.toLong(),1,"m",vector) }
        val found = rankSemantic(cards,1,"m",cache,setOf(2,3))
        assertEquals(listOf("4","5","6","7","8"),found.map { it.card.id })
        assertTrue(found.all { it.score == 1.0 })
    }
    @Test fun staleAndDifferentModelVectorsNeverParticipate() {
        val cards = (1L..4).map { KnowledgeCard(it.toString(),2,"text") }
        val cache = listOf(IndexedVector(1,2,"new",vector),IndexedVector(2,1,"new",vector),IndexedVector(3,2,"old",vector),IndexedVector(4,2,"new",vector))
        assertEquals(listOf("4"),rankSemantic(cards,1,"new",cache,emptySet()).map { it.card.id })
        assertTrue(runCatching { rankSemantic(cards,1,"old",cache,emptySet()) }.isFailure)
    }
    @Test fun oppositeVectorsAreNotMistakenForOpposingViewsAndDimensionsAreGuarded() {
        val cards=(1L..3).map { KnowledgeCard(it.toString(),1,"same words") }
        val cache=listOf(IndexedVector(1,1,"m",vector),IndexedVector(2,1,"m",vector.map { -it }),IndexedVector(3,1,"m",vector))
        assertEquals(listOf("3"),rankSemantic(cards,1,"m",cache,emptySet()).map { it.card.id })
        assertTrue(runCatching { rankSemantic(cards,1,"m",cache.map { if(it.cardId==3L) it.copy(values=vector+0.0) else it },emptySet()) }.isFailure)
    }
    @Test fun diverseCandidateCanDisplaceNearDuplicatesWithoutExtraCalls() {
        val cards = (1L..8).map { KnowledgeCard(it.toString(), 1, "source $it") }
        val cache = cards.map {
            IndexedVector(it.id.toLong(), 1, "m", if (it.id == "8") listOf(0.9, -0.435889894, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
                else if (it.id == "1") vector else listOf(0.95, 0.3122499, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0))
        }
        val found = rankSemantic(cards, 1, "m", cache, emptySet())
        assertEquals(5, found.size)
        assertEquals(listOf("2", "3", "8"), found.take(3).map { it.card.id })
        assertEquals(found, rankSemantic(cards.reversed(), 1, "m", cache.reversed(), emptySet()))
    }
}
