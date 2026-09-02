package com.clipmind.android.service

import com.clipmind.android.shizuku.ShizukuState

data class CaptureServiceAction(
    val keepService: Boolean,
    val runPolling: Boolean,
)

object CaptureServicePolicy {
    fun evaluate(captureRequested: Boolean, state: ShizukuState): CaptureServiceAction =
        CaptureServiceAction(
            keepService = captureRequested,
            runPolling = captureRequested && state == ShizukuState.ACTIVE,
        )
}
