package com.clipmind.android.data

import com.clipmind.android.security.FakeTextCipher
import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureUiModelMappingTest {
    @Test fun mapsRetryDiagnostics() {
        val cipher = FakeTextCipher()
        val entity = CaptureOutboxEntity(
            id = 7,
            clientCaptureId = "capture-7",
            encryptedRawText = cipher.encrypt("visible text"),
            hash = "hash",
            sourceApp = null,
            sourceUrl = null,
            mode = CaptureMode.AUTO,
            state = OutboxState.RETRYABLE_ERROR,
            retryCount = 3,
            nextRetryAt = 1_725_000_000_000,
            capturedAt = 1_724_000_000_000,
            updatedAt = 1_724_000_000_001,
            lastErrorCode = "HTTP_503",
        )

        val ui = entity.toUiModel(cipher)

        assertEquals("visible text", ui.content)
        assertEquals("HTTP_503", ui.lastErrorCode)
        assertEquals(3, ui.retryCount)
        assertEquals(1_725_000_000_000, ui.nextRetryAt)
    }
}
