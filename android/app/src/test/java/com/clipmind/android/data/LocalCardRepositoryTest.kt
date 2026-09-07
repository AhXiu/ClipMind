package com.clipmind.android.data

import com.clipmind.android.security.FakeTextCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCardRepositoryTest {
    private val cipher = FakeTextCipher()

    @Test fun encryptedSearchDecryptsOnlyInMemoryAndMatchesIgnoringCase() {
        val cards = listOf(card(1, "Room is authoritative"), card(2, "other content"))

        val result = searchDecryptedCards(cards, "AUTHORITATIVE", cipher)

        assertEquals(listOf(1L), result.map { it.id })
        assertEquals("Room is authoritative", result.single().content)
        assertTrue(cards.all { it.encryptedContent != result.single().content })
    }

    @Test fun encryptedSearchSkipsUndecryptableRows() {
        val valid = card(1, "search target")
        val broken = card(2, "search target").copy(encryptedContent = "corrupt")

        assertEquals(listOf(1L), searchDecryptedCards(listOf(broken, valid), "target", cipher).map { it.id })
    }

    private fun card(id: Long, text: String) = LocalCardEntity(
        id = id,
        clientCaptureId = "client-$id",
        encryptedContent = cipher.encrypt(text),
        hash = "hash-$id",
        sourceApp = null,
        sourceUrl = null,
        mode = CaptureMode.AUTO,
        capturedAt = id,
        updatedAt = id,
    )
}
