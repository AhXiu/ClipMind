package com.clipmind.android.reading

data class Topic(val title: String, val note: String = "", val cardIds: List<Long> = emptyList(), val version: Long = 1)
data class PrivateDraft(val key: String, val text: String)
data class DraftRecovery(val values: Map<String, String>, val unreadableKeys: Set<String>)

object NotebookPolicy {
    fun topic(value: Topic): Topic {
        require(value.title.trim().length in 1..80)
        require(value.note.length <= 20000 && value.cardIds.size <= 1000 && value.cardIds.all { it > 0 })
        require(value.version > 0)
        return value.copy(title = value.title.trim(), cardIds = value.cardIds.distinct())
    }
    fun draftKey(cardId: Long, revision: Long) = "$cardId:$revision"
    fun validDraft(key: String, text: String): Boolean =
        (key == "manual" || key.matches(Regex("[1-9][0-9]*:[1-9][0-9]*"))) && text.length <= 10000
}
