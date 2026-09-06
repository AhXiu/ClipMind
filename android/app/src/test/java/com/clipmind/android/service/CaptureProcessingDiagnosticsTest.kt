package com.clipmind.android.service

import com.clipmind.android.data.CaptureDecision
import com.clipmind.android.data.OutboxState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureProcessingDiagnosticsTest {
    @Test fun successfulAndTerminalDecisionsCommitRecentHash() {
        assertTrue(handlingFor(CaptureDecision.Stored(12, OutboxState.READY)).commitRecentHash)
        assertTrue(handlingFor(CaptureDecision.Filtered("TOO_SHORT")).commitRecentHash)
        assertTrue(handlingFor(CaptureDecision.Duplicate).commitRecentHash)
    }

    @Test fun retryableFailuresDoNotCommitRecentHash() {
        assertFalse(handlingFor(CaptureDecision.EncryptionFailed("ENCRYPT_KEY_INVALIDATED")).commitRecentHash)
        assertFalse(handlingForFailure(IllegalStateException("database unavailable")).commitRecentHash)
    }

    @Test fun mapsEveryCaptureOutcomeToDiagnosticUiText() {
        val cases = listOf(
            CaptureProcessingDiagnostic(CaptureProcessingResult.Stored(7, OutboxState.PENDING_CONFIRMATION), 1, 8) to
                "STORED（state=PENDING_CONFIRMATION，记录 id=7）",
            CaptureProcessingDiagnostic(CaptureProcessingResult.Filtered("TOO_SHORT"), 1, 3) to
                "FILTERED（reason=TOO_SHORT）",
            CaptureProcessingDiagnostic(CaptureProcessingResult.Duplicate24H, 1, 8) to
                "DUPLICATE_24H（24 小时内已存在）",
            CaptureProcessingDiagnostic(CaptureProcessingResult.EncryptionFailed("ENCRYPT_FAILED"), 1, 8) to
                "ENCRYPTION_FAILED（code=ENCRYPT_FAILED）",
            CaptureProcessingDiagnostic(CaptureProcessingResult.ProcessingFailed("SQLiteException"), 1, 8) to
                "PROCESSING_FAILED（type=SQLiteException）",
        )

        cases.forEach { (diagnostic, expected) -> assertEquals(expected, diagnostic.toUiDescription()) }
    }

    @Test fun stateFlowKeepsLastNonNoiseResultUntilAnotherResultIsRecorded() {
        val diagnostics = CaptureProcessingDiagnostics()
        val stored = CaptureProcessingResult.Stored(9, OutboxState.READY)
        diagnostics.record(stored, 100, 12)

        // A 30-second in-memory duplicate is deliberately not recorded by the service.
        assertEquals(stored, diagnostics.latest.value?.result)
    }
}
