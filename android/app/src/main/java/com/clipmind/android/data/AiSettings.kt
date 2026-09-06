package com.clipmind.android.data

enum class AiMode {
    SERVER_ARK,
    BYOK_ARK,
    BYOK_OPENROUTER;

    val providerId: String
        get() = if (this == BYOK_OPENROUTER) "openrouter" else "ark"

    val isByok: Boolean
        get() = this != SERVER_ARK
}

object AiDefaults {
    const val ARK_BASE_URL = "https://ark.cn-beijing.volces.com/api/v3"
    const val OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1"
    const val ARK_MODEL = "ep-20260306164116-j9fgc"

    fun modelFor(mode: AiMode, arkModel: String, openRouterModel: String): String = when (mode) {
        AiMode.SERVER_ARK -> ARK_MODEL
        AiMode.BYOK_ARK -> arkModel.trim().ifEmpty { ARK_MODEL }
        AiMode.BYOK_OPENROUTER -> openRouterModel.trim()
    }
}

data class AiCaptureConfiguration(val mode: AiMode, val model: String)
