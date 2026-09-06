package com.clipmind.android.ui

import com.clipmind.android.data.CaptureMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerCardActionPolicyTest {
    @Test fun onlyConfirmAwaitingConfirmShowsPublishAction() {
        assertTrue(shouldShowPublishAction(CaptureMode.CONFIRM, "awaiting_confirm"))
        assertFalse(shouldShowPublishAction(CaptureMode.AUTO, "awaiting_confirm"))
        assertFalse(shouldShowPublishAction(CaptureMode.CONFIRM, "persisted"))
        assertFalse(shouldShowPublishAction(CaptureMode.CONFIRM, "ai_running"))
        assertFalse(shouldShowPublishAction(CaptureMode.CONFIRM, "synced"))
        assertFalse(shouldShowPublishAction(CaptureMode.CONFIRM, null))
    }
}
