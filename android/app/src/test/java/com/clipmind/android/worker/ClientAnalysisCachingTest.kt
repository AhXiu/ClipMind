package com.clipmind.android.worker

import com.clipmind.android.data.CaptureMode
import com.clipmind.android.data.CaptureOutboxEntity
import com.clipmind.android.data.OutboxState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientAnalysisCachingTest {
    @Test fun cachedAnalysisIsNotInvokedAgain() {
        assertTrue(shouldInvokeClientAnalysis(entity("ark", null)))
        assertFalse(shouldInvokeClientAnalysis(entity("ark", "encrypted-analysis")))
    }

    @Test fun serverArkNeverInvokesPhoneProvider() {
        assertFalse(shouldInvokeClientAnalysis(entity(null, null)))
    }

    private fun entity(provider: String?, analysis: String?) = CaptureOutboxEntity(
        clientCaptureId = "id", encryptedRawText = "encrypted", hash = "hash",
        sourceApp = null, sourceUrl = null, mode = CaptureMode.AUTO,
        state = OutboxState.READY, capturedAt = 0, updatedAt = 0,
        aiProvider = provider, aiModel = "model", encryptedClientAnalysis = analysis,
    )
}
