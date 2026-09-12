package com.clipmind.android.data

import com.clipmind.android.network.dto.AnalysisInterpretation
import com.clipmind.android.network.dto.ClientAnalysis
import com.clipmind.android.security.FakeTextCipher
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class CardAnalysisStateTest {
    private fun metadata(state: OutboxState = OutboxState.SUCCEEDED) = SyncMetadataEntity(1, state, updatedAt = 1)

    @Test fun serverCompletionDoesNotRequireByokCache() {
        listOf("ai_succeeded", "awaiting_confirm", "published", "syncing", "synced").forEach {
            assertEquals(CardAnalysisState.COMPLETE, metadata().copy(serverCardStatus = it).analysisState())
        }
        assertEquals(CardAnalysisState.RUNNING, metadata().copy(serverCardStatus = "ai_running").analysisState())
        assertEquals(CardAnalysisState.FAILED, metadata().copy(serverCardStatus = "ai_failed").analysisState())
    }

    @Test fun uploadFailureDoesNotEraseCompletedByokAnalysis() {
        val sync = metadata(OutboxState.RETRYABLE_ERROR).copy(encryptedClientAnalysis = "cipher", lastErrorCode = "NETWORK_IO")
        assertEquals(CardAnalysisState.COMPLETE, sync.analysisState())
        assertEquals("上传待重试", sync.syncLabel())
    }

    @Test fun oldValidationRejectionsDoNotAppearAsCompletedOrEnableSameTaskRetry() {
        val sync = metadata(OutboxState.RETRYABLE_ERROR).copy(encryptedClientAnalysis = "cipher", lastErrorCode = "REJECTED_invalid_client_analysis")
        assertEquals(CardAnalysisState.FAILED, sync.analysisState())
        assertEquals("分析结果被后端拒绝，需处理", sync.syncLabel())
        assertTrue(canQueueAnalysis(sync.uploadState, sync.lastErrorCode))
        assertFalse(canQueueAnalysis(OutboxState.RETRYABLE_ERROR, "NETWORK_IO"))
        assertFalse(canQueueAnalysis(OutboxState.UPLOADING, sync.lastErrorCode))
    }

    @Test fun staleCacheCannotMakeEditedLocalContentComplete() {
        assertEquals(CardAnalysisState.LOCAL, metadata(OutboxState.LOCAL_ONLY).copy(encryptedClientAnalysis = "old").analysisState())
        assertEquals(CardAnalysisState.UNREADABLE, metadata(OutboxState.DECRYPTION_FAILED).analysisState())
    }

    @Test fun activeAndUnsettledTasksCannotBeEditedOrRegenerated() {
        listOf(OutboxState.READY, OutboxState.UPLOADING, OutboxState.RETRYABLE_ERROR, OutboxState.DISCARDED, OutboxState.DECRYPTION_FAILED).forEach {
            assertFalse(canEditCard(it))
            assertFalse(canQueueAnalysis(it))
        }
        assertTrue(canQueueAnalysis(OutboxState.SUCCEEDED))
        assertTrue(canEditCard(OutboxState.LOCAL_ONLY))
    }

    @Test fun analysisIsOnlyDecodedInMemoryAndCorruptionIsContained() {
        val cipher = FakeTextCipher()
        val analysis = ClientAnalysis("ark", "test-model", "技术", AnalysisInterpretation("summary", "insight", "action"), emptyList())
        val sync = metadata().copy(encryptedServerAnalysis = cipher.encrypt(Gson().toJson(analysis)))
        assertEquals(analysis, decryptAnalysis(sync, cipher))
        assertNull(decryptAnalysis(sync.copy(encryptedServerAnalysis = "broken"), cipher))
    }
}
