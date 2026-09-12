package com.clipmind.android.reading

import androidx.room.withTransaction
import com.clipmind.android.data.ClipMindDatabase
import com.clipmind.android.knowledge.KnowledgeFailure
import com.clipmind.android.security.TextCipher
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.util.UUID

internal interface NotebookStorage {
    suspend fun document(id: String): ReaderDocument?
    suspend fun documents(kind: String): List<ReaderDocument>
    suspend fun save(document: ReaderDocument)
    suspend fun delete(id: String)
    suspend fun activeCard(id: Long): Boolean
    suspend fun <T> transaction(block: suspend () -> T): T
}

internal class RoomNotebookStorage(private val db: ClipMindDatabase) : NotebookStorage {
    override suspend fun document(id: String) = db.readingDao().document(id)
    override suspend fun documents(kind: String) = db.readingDao().documents(kind)
    override suspend fun save(document: ReaderDocument) = db.readingDao().saveDocument(document)
    override suspend fun delete(id: String) = db.readingDao().deleteDocument(id)
    override suspend fun activeCard(id: Long) = db.localCardDao().card(id) != null
    override suspend fun <T> transaction(block: suspend () -> T): T = db.withTransaction(block)
}

internal class NotebookRepository(private val storage: NotebookStorage, private val cipher: TextCipher) {
    private val gson = Gson()

    suspend fun drafts(): DraftRecovery = withContext(Dispatchers.IO) {
        val values = mutableMapOf<String, String>()
        val unreadable = mutableSetOf<String>()
        for (row in storage.documents("private_draft")) {
            try {
                val draft = gson.fromJson(cipher.decrypt(row.encryptedPayload), PrivateDraft::class.java)
                require(NotebookPolicy.validDraft(draft.key, draft.text) && row.id == "draft:${draft.key}")
                values[draft.key] = draft.text
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { unreadable += row.id.removePrefix("draft:") }
        }
        DraftRecovery(values, unreadable)
    }

    suspend fun saveDraft(key: String, text: String) = withContext(Dispatchers.IO) {
        require(NotebookPolicy.validDraft(key, text))
        if (text.isEmpty()) storage.delete("draft:$key")
        else save("draft:$key", "private_draft", PrivateDraft(key, text))
    }

    suspend fun clearDraft(key: String, expected: String) = storage.transaction {
        val row = storage.document("draft:$key") ?: return@transaction
        require(row.kind == "private_draft")
        if (gson.fromJson(cipher.decrypt(row.encryptedPayload), PrivateDraft::class.java).text == expected) storage.delete(row.id)
    }

    suspend fun saveTopic(id: String?, value: Topic): String = storage.transaction {
        val normalized = NotebookPolicy.topic(value)
        val previous = id?.let { topicId ->
            val row = storage.document(topicId) ?: throw KnowledgeFailure("TOPIC_CHANGED")
            require(row.kind == "topic")
            gson.fromJson(cipher.decrypt(row.encryptedPayload), Topic::class.java).also {
                if (it.version != normalized.version) throw KnowledgeFailure("TOPIC_CHANGED")
            }
        }
        for (cardId in normalized.cardIds - previous?.cardIds.orEmpty().toSet()) {
            if (!storage.activeCard(cardId)) throw KnowledgeFailure("SOURCE_CHANGED")
        }
        val topicId = id ?: UUID.randomUUID().toString()
        save(topicId, "topic", normalized.copy(version = (previous?.version ?: 0) + 1))
        topicId
    }

    suspend fun deleteTopic(id: String) = storage.transaction {
        require(storage.document(id)?.kind == "topic")
        storage.delete(id)
    }

    private suspend fun save(id: String, kind: String, value: Any) {
        storage.save(ReaderDocument(id, kind, cipher.encrypt(gson.toJson(value)), System.currentTimeMillis()))
    }
}
