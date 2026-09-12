package com.clipmind.android.data

import com.clipmind.android.security.FakeTextCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiConfigurationSnapshotTest {
    private val cipher = FakeTextCipher()

    @Test fun allDirectProvidersRemainBoundToTheCapturedTask() {
        AiMode.entries.filter { it.isByok }.forEach { mode ->
            val entity = createEncryptedCaptureEntity("text", "hash", null, null, CaptureMode.AUTO, OutboxState.READY, 1, cipher,
                AiCaptureConfiguration(mode, "saved-model"))
            assertEquals(mode.providerId, entity.aiProvider)
            assertEquals("saved-model", entity.aiModel)
        }
    }

    @Test fun byokProviderAndModelAreCapturedAndDoNotFollowLaterSettings() {
        val snapshot = AiCaptureConfiguration(AiMode.BYOK_OPENROUTER, "vendor/model-v1")
        val entity = createEncryptedCaptureEntity(
            "text", "hash", null, null, CaptureMode.AUTO, OutboxState.READY, 1, cipher, snapshot,
        )
        val changedLater = AiCaptureConfiguration(AiMode.BYOK_ARK, "new-model")
        assertEquals("openrouter", entity.aiProvider)
        assertEquals("vendor/model-v1", entity.aiModel)
        assertEquals("new-model", changedLater.model)
    }

    @Test fun serverArkSnapshotNeverRequestsPhoneProvider() {
        val entity = createEncryptedCaptureEntity(
            "text", "hash", null, null, CaptureMode.AUTO, OutboxState.READY, 1, cipher,
        )
        assertNull(entity.aiProvider)
        assertEquals(AiDefaults.ARK_MODEL, entity.aiModel)
    }
}
