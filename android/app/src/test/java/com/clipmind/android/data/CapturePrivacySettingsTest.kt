package com.clipmind.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CapturePrivacySettingsTest {
    @Test fun aiDisabledAlwaysCreatesLocalOnlyCard() {
        CaptureMode.entries.forEach { mode ->
            assertEquals(OutboxState.LOCAL_ONLY, initialCaptureState(mode, aiEnabled = false, aiAutoSubmit = true))
            assertFalse(shouldScheduleImmediateUpload(CaptureDecision.Stored(1, initialCaptureState(mode, false, true))))
        }
    }

    @Test fun disabledAutoSubmitRequiresConfirmationEvenInAutoMode() {
        assertEquals(OutboxState.PENDING_CONFIRMATION, initialCaptureState(CaptureMode.AUTO, aiEnabled = true, aiAutoSubmit = false))
        assertEquals(OutboxState.READY, initialCaptureState(CaptureMode.AUTO, aiEnabled = true, aiAutoSubmit = true))
    }
}
