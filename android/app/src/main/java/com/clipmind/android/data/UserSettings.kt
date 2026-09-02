package com.clipmind.android.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class UserSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val modeFlow = MutableStateFlow(CaptureMode.valueOf(prefs.getString("mode", CaptureMode.CONFIRM.name)!!))
    private val captureFlow = MutableStateFlow(prefs.getBoolean("capture_requested", false))

    val mode: StateFlow<CaptureMode> = modeFlow.asStateFlow()
    val captureRequested: StateFlow<Boolean> = captureFlow.asStateFlow()

    fun setMode(mode: CaptureMode) {
        prefs.edit().putString("mode", mode.name).apply()
        modeFlow.value = mode
    }
    fun setCaptureRequested(value: Boolean) {
        prefs.edit().putBoolean("capture_requested", value).apply()
        captureFlow.value = value
    }
}
