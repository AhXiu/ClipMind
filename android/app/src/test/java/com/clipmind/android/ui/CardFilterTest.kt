package com.clipmind.android.ui

import com.clipmind.android.data.*
import org.junit.Assert.assertEquals
import org.junit.Test

class CardFilterTest {
    @Test fun searchIncludesTagsNotesSourcesAndUrlsWithoutMutatingSession() {
        val source = card(1, "Original", "Browser", 10, null).copy(
            sourceUrl = "https://example.invalid/source", tags = listOf(TagEntity(1, "Research", "research", 10)),
        )
        val notes = mapOf(1L to listOf("My personal INSIGHT"))
        listOf("research", "insight", "browser", "example.invalid", "original").forEach { query ->
            assertEquals(listOf(1L), filterCards(listOf(source), query, null, CardTimeFilter.ALL, CardAiFilter.ALL, false, now, notes).map { it.id })
        }
        val session = LibrarySession(query = "insight", source = "Browser", selected = setOf(1L))
        assertEquals("insight", session.query)
        assertEquals(setOf(1L), session.selected)
    }

    @Test fun resultSnippetShowsMatchDeepInsideLongText() {
        val text = "a".repeat(2000) + "needle" + "b".repeat(2000)
        val snippet = searchSnippet(text, "NEEDLE")
        org.junit.Assert.assertTrue(snippet.contains("needle"))
        org.junit.Assert.assertTrue(snippet.length < 200)
        assertEquals(text, searchSnippet(text, ""))
    }
    private val now = 2_000_000_000_000

    @Test fun combinesTextSourceTimeAndAiFilters() {
        val cards = listOf(
            card(1, "Kotlin Room", "notes", now - 1_000, SyncMetadataEntity(1, OutboxState.READY, updatedAt = now, aiProvider = "ark")),
            card(2, "Kotlin old", "browser", now - 9 * 86_400_000L, SyncMetadataEntity(2, OutboxState.SUCCEEDED, updatedAt = now, encryptedClientAnalysis = "cipher")),
        )
        val result = filterCards(cards, "room", "notes", CardTimeFilter.TODAY, CardAiFilter.PENDING, false, now)
        assertEquals(listOf(1L), result.map { it.id })
    }

    @Test fun supportsStableAscendingAndDescendingOrder() {
        val cards = listOf(card(1, "a", null, 10, null), card(2, "b", null, 20, null))
        assertEquals(listOf(1L, 2L), filterCards(cards, "", null, CardTimeFilter.ALL, CardAiFilter.ALL, true, now).map { it.id })
        assertEquals(listOf(2L, 1L), filterCards(cards, "", null, CardTimeFilter.ALL, CardAiFilter.ALL, false, now).map { it.id })
    }

    private fun card(id: Long, text: String, source: String?, time: Long, sync: SyncMetadataEntity?) = LocalCard(
        id, text, true, time, time, source, emptyList(), sync = sync,
    )
}
