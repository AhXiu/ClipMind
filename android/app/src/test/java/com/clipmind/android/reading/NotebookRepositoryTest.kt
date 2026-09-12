package com.clipmind.android.reading

import com.clipmind.android.security.FakeTextCipher
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NotebookRepositoryTest {
    private val storage = MemoryStorage()
    private val cipher = FakeTextCipher()
    private val repo = NotebookRepository(storage, cipher)

    @Test fun topicsAreEncryptedAndMembershipIsDistinct() = runBlocking {
        val id = repo.saveTopic(null, Topic("  Private topic  ", "Private reflection", listOf(1, 1, 2)))
        val row = storage.rows.getValue(id)
        assertFalse(row.encryptedPayload.contains("Private"))
        assertEquals("topic", row.kind)
        assertEquals(Topic("Private topic", "Private reflection", listOf(1, 2)), decode(id))
    }

    @Test fun outdatedEditorsCannotOverwriteNewerTopic() = runBlocking {
        val id = repo.saveTopic(null, Topic("First"))
        repo.saveTopic(id, Topic("Second", version = 1))
        assertTrue(runCatching { repo.saveTopic(id, Topic("Stale", version = 1)) }.isFailure)
        assertEquals("Second", decode(id).title)
        assertEquals(2L, decode(id).version)
    }

    @Test fun newMembershipRequiresAnActiveCardButHistoricalReferencesCanBeRemoved() = runBlocking {
        val id = repo.saveTopic(null, Topic("Topic", cardIds = listOf(1)))
        storage.active.remove(1)
        assertTrue(runCatching { repo.saveTopic(null, Topic("Invalid", cardIds = listOf(1))) }.isFailure)
        repo.saveTopic(id, decode(id).copy(note = "Keep historical reference"))
        repo.saveTopic(id, decode(id).copy(cardIds = emptyList()))
        assertTrue(decode(id).cardIds.isEmpty())
        assertEquals(1, storage.rows.size)
    }

    @Test fun deletingTopicNeverDeletesCardsOrOtherDocuments() = runBlocking {
        val first = repo.saveTopic(null, Topic("First", cardIds = listOf(1)))
        val second = repo.saveTopic(null, Topic("Second", cardIds = listOf(1)))
        repo.saveDraft("manual", "Private unfinished text")
        repo.deleteTopic(first)
        assertEquals(setOf(1L, 2L, 3L), storage.active)
        assertNotNull(storage.rows[second])
        assertTrue(runCatching { repo.deleteTopic("draft:manual") }.isFailure)
        assertEquals("Private unfinished text", repo.drafts().values["manual"])
    }

    @Test fun annotationAndManualDraftsSurviveRecreationWithoutPlaintextStorage() = runBlocking {
        repo.saveDraft("manual", "Private manual draft")
        repo.saveDraft("1:2", "Private annotation without any AI")
        assertTrue(storage.rows.values.all { !it.encryptedPayload.contains("Private") })
        val restored = NotebookRepository(storage, cipher).drafts().values
        assertEquals("Private manual draft", restored["manual"])
        assertEquals("Private annotation without any AI", restored["1:2"])
    }

    @Test fun clearingSavedDraftCannotEraseNewerInput() = runBlocking {
        repo.saveDraft("1:1", "Old note")
        repo.saveDraft("1:1", "New note")
        repo.clearDraft("1:1", "Old note")
        assertEquals("New note", repo.drafts().values["1:1"])
        repo.clearDraft("1:1", "New note")
        assertFalse(repo.drafts().values.containsKey("1:1"))
    }

    @Test fun invalidDraftAndTopicInputsCannotModifyPersistedContent() = runBlocking {
        repo.saveDraft("manual", "Keep")
        assertTrue(runCatching { repo.saveDraft("manual", "x".repeat(10001)) }.isFailure)
        assertTrue(runCatching { repo.saveDraft("../unsafe", "text") }.isFailure)
        assertTrue(runCatching { repo.saveTopic(null, Topic(" ")) }.isFailure)
        assertTrue(runCatching { repo.saveTopic(null, Topic("Title", cardIds = listOf(-1))) }.isFailure)
        assertEquals("Keep", repo.drafts().values["manual"])
    }

    @Test fun corruptedDraftFailsVisiblyWithoutBeingDeleted() = runBlocking {
        repo.saveDraft("manual", "Original")
        repo.saveDraft("1:1", "Healthy draft")
        storage.rows["draft:manual"] = storage.rows.getValue("draft:manual").copy(encryptedPayload = "corrupt")
        assertEquals(setOf("manual"), repo.drafts().unreadableKeys)
        assertEquals("Healthy draft", repo.drafts().values["1:1"])
        assertNotNull(storage.rows["draft:manual"])
    }

    private fun decode(id: String) = Gson().fromJson(cipher.decrypt(storage.rows.getValue(id).encryptedPayload), Topic::class.java)

    private class MemoryStorage : NotebookStorage {
        val rows = mutableMapOf<String, ReaderDocument>()
        val active = mutableSetOf(1L, 2L, 3L)
        override suspend fun document(id: String) = rows[id]
        override suspend fun documents(kind: String) = rows.values.filter { it.kind == kind }
        override suspend fun save(document: ReaderDocument) { rows[document.id] = document }
        override suspend fun delete(id: String) { rows.remove(id) }
        override suspend fun activeCard(id: Long) = id in active
        override suspend fun <T> transaction(block: suspend () -> T): T {
            val previous = rows.toMap()
            return try { block() } catch (e: Exception) { rows.clear(); rows.putAll(previous); throw e }
        }
    }
}
