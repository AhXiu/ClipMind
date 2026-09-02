package com.clipmind.android.service

import com.clipmind.android.shizuku.ShizukuState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureServicePolicyTest {
    @Test fun startIntentBeforeShizukuReadyKeepsServiceAndUserIntent() {
        listOf(
            ShizukuState.UNAVAILABLE,
            ShizukuState.BINDER_READY,
            ShizukuState.PERMISSION_REQUIRED,
            ShizukuState.DEAD,
        ).forEach { state ->
            val action = CaptureServicePolicy.evaluate(captureRequested = true, state)
            assertTrue(action.keepService)
            assertFalse(action.runPolling)
        }
    }

    @Test fun activeStartsExactlyThePollingMode() {
        val action = CaptureServicePolicy.evaluate(captureRequested = true, ShizukuState.ACTIVE)
        assertTrue(action.keepService)
        assertTrue(action.runPolling)
    }

    @Test fun noUserIntentNeverPollsEvenIfActive() {
        val action = CaptureServicePolicy.evaluate(captureRequested = false, ShizukuState.ACTIVE)
        assertFalse(action.keepService)
        assertFalse(action.runPolling)
    }
}
