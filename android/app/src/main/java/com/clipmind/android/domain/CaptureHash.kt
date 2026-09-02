package com.clipmind.android.domain

import java.security.MessageDigest

object CaptureHash {
    fun normalize(text: String): String = text.trim().replace(Regex("\\s+"), " ")
    fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(normalize(text).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

class RecentHashDeduplicator(private val windowMillis: Long = 30_000L) {
    private val seen = LinkedHashMap<String, Long>()

    @Synchronized fun isDuplicate(hash: String, now: Long): Boolean {
        seen.entries.removeAll { now - it.value > windowMillis }
        val duplicate = seen.containsKey(hash)
        seen[hash] = now
        return duplicate
    }
}
