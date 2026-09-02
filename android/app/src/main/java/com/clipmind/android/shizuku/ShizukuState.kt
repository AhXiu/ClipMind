package com.clipmind.android.shizuku

enum class ShizukuState { UNAVAILABLE, BINDER_READY, PERMISSION_REQUIRED, ACTIVE, DEAD }

sealed interface ShizukuEvent {
    data class BinderReceived(val permissionGranted: Boolean) : ShizukuEvent
    data object BinderDead : ShizukuEvent
    data class PermissionResult(val granted: Boolean) : ShizukuEvent
    data object ServiceConnected : ShizukuEvent
    data object ServiceDisconnected : ShizukuEvent
    data object RuntimeUnavailable : ShizukuEvent
}

object ShizukuStateReducer {
    fun reduce(state: ShizukuState, event: ShizukuEvent): ShizukuState = when (event) {
        ShizukuEvent.RuntimeUnavailable -> ShizukuState.UNAVAILABLE
        ShizukuEvent.BinderDead -> ShizukuState.DEAD
        is ShizukuEvent.BinderReceived -> if (event.permissionGranted) ShizukuState.BINDER_READY else ShizukuState.PERMISSION_REQUIRED
        is ShizukuEvent.PermissionResult -> if (event.granted) ShizukuState.BINDER_READY else ShizukuState.PERMISSION_REQUIRED
        ShizukuEvent.ServiceConnected -> if (state == ShizukuState.BINDER_READY || state == ShizukuState.ACTIVE) ShizukuState.ACTIVE else state
        ShizukuEvent.ServiceDisconnected -> if (state == ShizukuState.UNAVAILABLE) state else ShizukuState.DEAD
    }
}
