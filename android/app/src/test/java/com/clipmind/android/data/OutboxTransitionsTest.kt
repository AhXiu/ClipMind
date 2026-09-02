package com.clipmind.android.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutboxTransitionsTest {
    @Test fun confirmationCanBecomeReadyOrDiscarded() {
        assertTrue(OutboxState.PENDING_CONFIRMATION.canTransitionTo(OutboxState.READY))
        assertTrue(OutboxState.PENDING_CONFIRMATION.canTransitionTo(OutboxState.DISCARDED))
    }
    @Test fun uploadRequiresServerConfirmation() {
        assertFalse(OutboxState.READY.canTransitionTo(OutboxState.SUCCEEDED))
        assertTrue(OutboxState.READY.canTransitionTo(OutboxState.UPLOADING))
        assertTrue(OutboxState.READY.canTransitionTo(OutboxState.DECRYPTION_FAILED))
        assertTrue(OutboxState.UPLOADING.canTransitionTo(OutboxState.SUCCEEDED))
        assertTrue(OutboxState.UPLOADING.canTransitionTo(OutboxState.REJECTED))
        assertTrue(OutboxState.UPLOADING.canTransitionTo(OutboxState.RETRYABLE_ERROR))
    }
    @Test fun terminalStatesStayTerminal() {
        assertFalse(OutboxState.SUCCEEDED.canTransitionTo(OutboxState.READY))
        assertFalse(OutboxState.DISCARDED.canTransitionTo(OutboxState.READY))
        assertFalse(OutboxState.REJECTED.canTransitionTo(OutboxState.READY))
        assertFalse(OutboxState.DECRYPTION_FAILED.canTransitionTo(OutboxState.READY))
    }
}
