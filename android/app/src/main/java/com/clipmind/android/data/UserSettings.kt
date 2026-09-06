package com.clipmind.android.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class UserSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val modeFlow = MutableStateFlow(CaptureMode.valueOf(prefs.getString("mode", CaptureMode.CONFIRM.name)!!))
    private val captureFlow = MutableStateFlow(prefs.getBoolean("capture_requested", false))
    private val aiModeFlow = MutableStateFlow(readAiMode())
    private val arkModelFlow = MutableStateFlow(prefs.getString("ark_model", AiDefaults.ARK_MODEL) ?: AiDefaults.ARK_MODEL)
    private val openRouterModelFlow = MutableStateFlow(prefs.getString("openrouter_model", "").orEmpty())

    val mode: StateFlow<CaptureMode> = modeFlow.asStateFlow()
    val captureRequested: StateFlow<Boolean> = captureFlow.asStateFlow()
    val aiMode: StateFlow<AiMode> = aiModeFlow.asStateFlow()
    val arkModel: StateFlow<String> = arkModelFlow.asStateFlow()
    val openRouterModel: StateFlow<String> = openRouterModelFlow.asStateFlow()

    fun setMode(mode: CaptureMode) {
        prefs.edit().putString("mode", mode.name).apply()
        modeFlow.value = mode
    }

    fun setCaptureRequested(value: Boolean) {
        prefs.edit().putBoolean("capture_requested", value).apply()
        captureFlow.value = value
    }

    fun setAiMode(mode: AiMode) {
        prefs.edit().putString("ai_mode", mode.name).apply()
        aiModeFlow.value = mode
    }

    fun setArkModel(model: String) {
        val normalized = model.trim().ifEmpty { AiDefaults.ARK_MODEL }
        prefs.edit().putString("ark_model", normalized).apply()
        arkModelFlow.value = normalized
    }

    fun setOpenRouterModel(model: String) {
        val normalized = model.trim()
        prefs.edit().putString("openrouter_model", normalized).apply()
        openRouterModelFlow.value = normalized
    }

    fun captureAiConfiguration(): AiCaptureConfiguration {
        val selected = aiModeFlow.value
        return AiCaptureConfiguration(
            mode = selected,
            model = AiDefaults.modelFor(selected, arkModelFlow.value, openRouterModelFlow.value),
        )
    }

    private fun readAiMode(): AiMode = runCatching {
        AiMode.valueOf(prefs.getString("ai_mode", AiMode.SERVER_ARK.name)!!)
    }.getOrDefault(AiMode.SERVER_ARK)
}
