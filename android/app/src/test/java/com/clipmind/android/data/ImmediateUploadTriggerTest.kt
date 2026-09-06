package com.clipmind.android.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImmediateUploadTriggerTest {
    @Test fun autoStoredAsReadyTriggersImmediateUpload() {
        assertTrue(shouldScheduleImmediateUpload(CaptureDecision.Stored(1, OutboxState.READY)))
    }

    @Test fun confirmModeInsertDoesNotTriggerUntilSuccessfulConfirm() {
        assertFalse(shouldScheduleImmediateUpload(CaptureDecision.Stored(1, OutboxState.PENDING_CONFIRMATION)))
        assertTrue(shouldScheduleImmediateUploadAfterConfirm(databaseUpdated = true))
    }

    @Test fun filteredAndFailedInsertDoNotTrigger() {
        assertFalse(shouldScheduleImmediateUpload(CaptureDecision.Filtered("TOO_SHORT")))
        assertFalse(shouldScheduleImmediateUpload(CaptureDecision.Duplicate))
        assertFalse(shouldScheduleImmediateUpload(CaptureDecision.EncryptionFailed("ENCRYPT_FAILED")))
        assertFalse(shouldScheduleImmediateUploadAfterConfirm(databaseUpdated = false))
    }
}
