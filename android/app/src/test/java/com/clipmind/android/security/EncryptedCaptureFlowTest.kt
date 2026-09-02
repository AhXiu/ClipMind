package com.clipmind.android.security

import com.clipmind.android.data.CaptureMode
import com.clipmind.android.data.OutboxState
import com.clipmind.android.data.createEncryptedCaptureEntity
import com.clipmind.android.network.prepareUploadBatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EncryptedCaptureFlowTest {
    private val cipher = FakeTextCipher()

    @Test fun persistedEntityContainsOnlyCiphertext() {
        val entity = entityFor("private clipboard text")
        assertNotEquals("private clipboard text", entity.encryptedRawText)
        assertTrue(entity.encryptedRawText.startsWith("fake:v1:"))
        assertFalse(entity::class.java.declaredFields.any { it.name == "rawText" })
    }

    @Test fun uploadPreparationDecryptsBackToOriginalText() {
        val prepared = prepareUploadBatch(listOf(entityFor("original upload text")), cipher)
        assertTrue(prepared.failures.isEmpty())
        assertEquals("original upload text", prepared.uploads.single().item.rawText)
    }

    @Test fun tamperedCiphertextIsNeverPreparedForUpload() {
        val original = entityFor("must not leak garbage")
        val tampered = original.copy(encryptedRawText = original.encryptedRawText + "tampered")
        val prepared = prepareUploadBatch(listOf(tampered), cipher)
        assertTrue(prepared.uploads.isEmpty())
        assertEquals("DECRYPT_AUTHENTICATION_FAILED", prepared.failures.single().errorCode)
    }

    @Test fun keyFailureIsNeverPreparedForUpload() {
        val unavailable = object : TextCipher {
            override fun encrypt(plainText: String) = error("not used")
            override fun decrypt(encryptedText: String): String =
                throw TextCipherException(TextCipherError.KEY_INVALIDATED)
        }
        val prepared = prepareUploadBatch(listOf(entityFor("key invalidation")), unavailable)
        assertTrue(prepared.uploads.isEmpty())
        assertEquals("DECRYPT_KEY_INVALIDATED", prepared.failures.single().errorCode)
    }

    private fun entityFor(text: String) = createEncryptedCaptureEntity(
        plainText = text,
        hash = "hash",
        sourceApp = "notes.app",
        sourceUrl = "https://source.example",
        mode = CaptureMode.AUTO,
        state = OutboxState.READY,
        now = 0,
        cipher = cipher,
    )
}
