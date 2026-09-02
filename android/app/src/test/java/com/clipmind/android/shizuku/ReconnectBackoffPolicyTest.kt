package com.clipmind.android.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReconnectBackoffPolicyTest {
    @Test fun backoffIsFiniteExponentialAndCapped() {
        val policy = ReconnectBackoffPolicy(maxAttempts = 6, initialDelayMillis = 100, maxDelayMillis = 500)
        assertEquals(listOf(100L, 200L, 400L, 500L, 500L, 500L), (0..5).map(policy::delayForAttempt))
        assertNull(policy.delayForAttempt(6))
    }

    @Test fun binderDeathCanRecoverThroughReadyToActive() {
        var state = ShizukuStateReducer.reduce(ShizukuState.ACTIVE, ShizukuEvent.BinderDead)
        assertEquals(ShizukuState.DEAD, state)
        state = ShizukuStateReducer.reduce(state, ShizukuEvent.BinderReceived(true))
        assertEquals(ShizukuState.BINDER_READY, state)
        state = ShizukuStateReducer.reduce(state, ShizukuEvent.ServiceConnected)
        assertEquals(ShizukuState.ACTIVE, state)
    }
}
