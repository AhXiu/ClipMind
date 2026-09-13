package com.clipmind.android.worker

import com.clipmind.android.data.*
import com.clipmind.android.network.ClientAnalysisRejection
import com.clipmind.android.network.dto.AcceptedCapture
import com.clipmind.android.network.dto.CaptureBatchResponse
import com.clipmind.android.network.dto.RejectedCapture
import org.junit.Assert.*
import org.junit.Test

class ClientAnalysisRejectionTest {
    @Test fun providerRejectionIsTerminalAndKeepsOnlyTheKnownReason() {
        val response = CaptureBatchResponse(rejected = listOf(
            RejectedCapture("task", "invalid_client_analysis", "client_analysis.provider is unsupported"),
        ))
        assertEquals(mapOf("task" to ClientAnalysisRejection.PROVIDER.errorCode), terminalCaptureRejections(listOf(entity()), response))
    }

    @Test fun legacyProviderRejectionKeepsTheKnownReason() {
        val response = CaptureBatchResponse(rejected = listOf(
            RejectedCapture("task", "invalid_client_analysis", "client_analysis.provider must be ark or openrouter"),
        ))
        assertEquals(mapOf("task" to ClientAnalysisRejection.PROVIDER.errorCode),
            terminalCaptureRejections(listOf(entity()), response))
    }

    @Test fun fixedFieldMessagesAreClassifiedWithoutAcceptingArbitraryText() {
        val messages = mapOf(
            "client_analysis.model must be non-empty and at most 200 characters" to ClientAnalysisRejection.MODEL,
            "client_analysis.primary_tag is invalid" to ClientAnalysisRejection.TAG,
            "client_analysis.interpretation.summary must be non-empty and at most 2000 characters" to ClientAnalysisRejection.SUMMARY,
            "client_analysis.interpretation.insight must be non-empty and at most 4000 characters" to ClientAnalysisRejection.INSIGHT,
            "client_analysis.interpretation.action must be non-empty and at most 2000 characters" to ClientAnalysisRejection.ACTION,
            "client_analysis.books must contain at most 10 items" to ClientAnalysisRejection.BOOKS,
            "client_analysis book title must be non-empty and at most 300 characters" to ClientAnalysisRejection.BOOKS,
            "client_analysis book author must be at most 200 characters" to ClientAnalysisRejection.BOOKS,
        )
        messages.forEach { (message, expected) ->
            assertEquals(expected, ClientAnalysisRejection.fromResponse(RejectedCapture("task", "INVALID_CLIENT_ANALYSIS", message)))
            assertEquals(ClientAnalysisRejection.UNKNOWN,
                ClientAnalysisRejection.fromResponse(RejectedCapture("task", "invalid_client_analysis", "$message: extra")))
        }
        assertNull(ClientAnalysisRejection.fromResponse(RejectedCapture("task", "temporary_failure", messages.keys.first())))
        assertEquals(ClientAnalysisRejection.UNKNOWN, ClientAnalysisRejection.fromResponse(
            RejectedCapture("task", "invalid_client_analysis", "client_analysis.provider must be ark or openrouter: extra")))
    }

    @Test fun freeFormMessagesNeverEnterStoredDiagnostics() {
        val secret = "fake-sensitive-payload"
        listOf(null, secret, "client_analysis.provider is unsupported: $secret").forEach { message ->
            val reason = ClientAnalysisRejection.fromResponse(RejectedCapture("task", "invalid_client_analysis", message))!!
            assertEquals(ClientAnalysisRejection.UNKNOWN, reason)
            assertFalse(reason.errorCode.contains(secret))
        }
        assertEquals(ClientAnalysisRejection.UNKNOWN, ClientAnalysisRejection.fromStored("Rejected_invalid_client_analysis"))
        assertNull(ClientAnalysisRejection.fromStored("HTTP_503"))
    }

    @Test fun unrelatedAcceptedAndFilteredItemsAreNotOverriddenByValidationRejections() {
        val body = CaptureBatchResponse(
            accepted = listOf(AcceptedCapture("accepted")),
            rejected = listOf(
                RejectedCapture("accepted", "invalid_client_analysis"),
                RejectedCapture("unknown", "invalid_client_analysis"),
                RejectedCapture("task", "filtered_reject"),
                RejectedCapture("task", "invalid_client_analysis"),
                RejectedCapture("retry", "temporary_failure"),
            ),
        )
        assertEquals(mapOf("task" to "filtered_reject"), terminalCaptureRejections(listOf(entity(), entity("accepted"), entity("retry")), body))
    }

    @Test fun recoveryRequiresARejectedTaskWithItsOriginalProviderAndCache() {
        val rejected = SyncMetadataEntity(1, OutboxState.REJECTED, updatedAt = 1,
            aiProvider = "anthropic", aiModel = "claude-test", encryptedClientAnalysis = "encrypted-result",
            lastErrorCode = ClientAnalysisRejection.PROVIDER.errorCode)
        assertTrue(canResubmitCachedAnalysis(rejected))
        assertTrue(canResubmitCachedAnalysis(rejected.copy(uploadState = OutboxState.RETRYABLE_ERROR, lastErrorCode = "REJECTED_invalid_client_analysis")))
        OutboxState.entries.filterNot { it in setOf(OutboxState.REJECTED, OutboxState.RETRYABLE_ERROR) }.forEach {
            assertFalse(canResubmitCachedAnalysis(rejected.copy(uploadState = it)))
        }
        assertFalse(canResubmitCachedAnalysis(rejected.copy(encryptedClientAnalysis = null)))
        assertFalse(canResubmitCachedAnalysis(rejected.copy(aiProvider = "unknown")))
        assertFalse(canResubmitCachedAnalysis(rejected.copy(aiModel = "")))
        assertFalse(canResubmitCachedAnalysis(rejected.copy(lastErrorCode = "filtered_reject")))
        assertFalse(canResubmitCachedAnalysis(rejected.copy(lastErrorCode = "HTTP_401")))
        assertFalse(canResubmitCachedAnalysis(null))
        assertFalse(shouldInvokeClientAnalysis(entity().copy(aiProvider = "anthropic", encryptedClientAnalysis = "encrypted-result")))
    }

    private fun entity(id: String = "task") = CaptureOutboxEntity(
        clientCaptureId = id, encryptedRawText = "encrypted-original", hash = "hash",
        sourceApp = null, sourceUrl = null, mode = CaptureMode.CONFIRM, state = OutboxState.UPLOADING,
        capturedAt = 0, updatedAt = 0,
    )
}
