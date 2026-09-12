package com.clipmind.android.knowledge

import org.junit.Assert.*
import org.junit.Test

class KnowledgeContractTest {
    private val input = KnowledgeRequest(listOf(KnowledgeCard("1", 1, "Transactions ensure consistency."), KnowledgeCard("2", 2, "Logs enable recovery.")))
    private fun result() = KnowledgeResult("Database design", listOf(KnowledgePoint("summary", "Two database mechanisms.", listOf(Evidence("1", "ensure consistency"), Evidence("2", "enable recovery")))), emptyList())

    @Test fun validatesGroundedResultAndProvenance() {
        assertTrue(KnowledgeContract.validResult(input, result()))
        assertTrue(KnowledgeContract.validResponse(input, KnowledgeResponse(result(), "ark", "test-model", "knowledge-v1")))
        assertFalse(KnowledgeContract.validResponse(input, KnowledgeResponse(result(), "ark", "test-model", "unknown")))
    }
    @Test fun rejectsInventedIdsQuotesAndOmittedSources() {
        listOf(Evidence("999", "ensure consistency"), Evidence("1", "invented quote"), Evidence("1", " ")).forEach { evidence ->
            assertFalse(KnowledgeContract.validResult(input, result().copy(points = listOf(result().points[0].copy(evidence = listOf(evidence, Evidence("2", "enable recovery")))))))
        }
        assertFalse(KnowledgeContract.validResult(input, result().copy(points = listOf(result().points[0].copy(evidence = listOf(Evidence("1", "ensure consistency")))))))
    }
    @Test fun comparisonRequiresDistinctSourcesAndRelationsRequireActualEvidence() {
        val point = KnowledgePoint("agreement", "Agree", listOf(Evidence("1", "ensure consistency"), Evidence("1", "ensure consistency")))
        assertFalse(KnowledgeContract.validResult(input, result().copy(points = listOf(point))))
        val relation = KnowledgeRelation("1", "2", "same_topic", "Database mechanisms", "Transactions", "Logs")
        assertTrue(KnowledgeContract.validResult(input, result().copy(relations = listOf(relation))))
        assertFalse(KnowledgeContract.validResult(input, result().copy(relations = listOf(relation.copy(targetId = "1")))) )
        assertFalse(KnowledgeContract.validResult(input, result().copy(relations = listOf(relation.copy(type = "causes")))))
    }
    @Test fun boundsInputAndBlocksSensitiveText() {
        assertFalse(KnowledgeContract.validInput(input.copy(cards = input.cards.take(1))))
        assertFalse(KnowledgeContract.validInput(input.copy(cards = listOf(input.cards[0], input.cards[0]))))
        assertFalse(KnowledgeContract.validInput(input.copy(cards = listOf(input.cards[0].copy(text = "a".repeat(6001)), input.cards[1]))))
        assertFalse(KnowledgeContract.safeText("联系电话 13800138000"))
        assertFalse(KnowledgeContract.safeText("password=never-store-this"))
    }
    @Test fun stanceEvidenceAndPairUniquenessAreEnforcedForV2ButLegacyRemainsReadable() {
        val relation = KnowledgeRelation("1", "2", "extends", "Different database mechanisms", input.cards[0].text, input.cards[1].text)
        val valid = result().copy(relations = listOf(relation))
        assertTrue(KnowledgeContract.validResult(input, valid))
        assertFalse(KnowledgeContract.validResult(input, valid.copy(relations = listOf(relation, relation.copy(type = "supports")))))
        assertFalse(KnowledgeContract.validResult(input, valid.copy(relations = listOf(relation, relation.copy(sourceId = "2", targetId = "1", sourceQuote = relation.targetQuote, targetQuote = relation.sourceQuote)))))
        val fragment = valid.copy(relations = listOf(relation.copy(sourceQuote = "ensure")))
        assertFalse(KnowledgeContract.validResponse(input, KnowledgeResponse(fragment, "ark", "test", "knowledge-v2")))
        assertTrue(KnowledgeContract.validResponse(input, KnowledgeResponse(fragment, "ark", "test", "knowledge-v1")))
    }
    @Test fun markdownIncludesEvidenceAndEscapesModelLinks() {
        val payload = KnowledgeNotePayload(input, KnowledgeResponse(result().copy(title = "[click](bad)"), "ark", "test", "knowledge-v1"))
        val markdown = renderKnowledgeMarkdown(payload, true)
        assertTrue(markdown.contains("历史快照"))
        assertTrue(markdown.contains("卡片 1"))
        assertTrue(markdown.contains("ensure consistency"))
        assertTrue(markdown.contains("\\[click\\]"))
    }
}
