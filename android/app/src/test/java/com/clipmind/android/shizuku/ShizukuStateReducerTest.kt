package com.clipmind.android.shizuku

import org.junit.Assert.assertEquals
import org.junit.Test

class ShizukuStateReducerTest {
    @Test fun permissionAndConnectionReachActive() {
        var state = ShizukuStateReducer.reduce(ShizukuState.UNAVAILABLE, ShizukuEvent.BinderReceived(false))
        assertEquals(ShizukuState.PERMISSION_REQUIRED, state)
        state = ShizukuStateReducer.reduce(state, ShizukuEvent.PermissionResult(true))
        assertEquals(ShizukuState.BINDER_READY, state)
        state = ShizukuStateReducer.reduce(state, ShizukuEvent.ServiceConnected)
        assertEquals(ShizukuState.ACTIVE, state)
    }
    @Test fun binderDeathIsObservable() = assertEquals(
        ShizukuState.DEAD,
        ShizukuStateReducer.reduce(ShizukuState.ACTIVE, ShizukuEvent.BinderDead),
    )
    @Test fun runtimeUnavailableWins() = assertEquals(
        ShizukuState.UNAVAILABLE,
        ShizukuStateReducer.reduce(ShizukuState.DEAD, ShizukuEvent.RuntimeUnavailable),
    )
}
