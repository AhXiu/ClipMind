package com.clipmind.android.data

enum class AiMode(val providerId: String, val label: String) {
    SERVER_ARK("ark", "后端托管"),
    BYOK_ARK("ark", "火山方舟 Ark"),
    BYOK_OPENROUTER("openrouter", "OpenRouter · 跨厂商"),
    BYOK_KIMI("kimi", "Kimi 官方"),
    BYOK_GLM("glm", "智谱 GLM 官方"),
    BYOK_OPENAI("openai", "OpenAI GPT 官方"),
    BYOK_ANTHROPIC("anthropic", "Claude / Anthropic 官方"),
    BYOK_GEMINI("gemini", "Google Gemini 官方"),
    BYOK_DEEPSEEK("deepseek", "DeepSeek 官方"),
    BYOK_QWEN("qwen", "通义千问 Qwen（新加坡）");

    val isByok: Boolean
        get() = this != SERVER_ARK
}

object AiDefaults {
    const val ARK_BASE_URL = "https://ark.cn-beijing.volces.com/api/v3"
    const val OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1"
    const val ARK_MODEL = "ep-20260306164116-j9fgc"
    const val KIMI_BASE_URL = "https://api.moonshot.cn/v1"
    const val GLM_BASE_URL = "https://open.bigmodel.cn/api/paas/v4"
    const val OPENAI_BASE_URL = "https://api.openai.com/v1"
    const val ANTHROPIC_BASE_URL = "https://api.anthropic.com/v1"
    const val GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
    const val DEEPSEEK_BASE_URL = "https://api.deepseek.com/v1"
    const val QWEN_BASE_URL = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1"

    val providerIds = AiMode.entries.filter { it.isByok }.map { it.providerId }.toSet()

    fun endpoint(provider: String): String? = when (provider) {
        "ark" -> ARK_BASE_URL
        "openrouter" -> OPENROUTER_BASE_URL
        "kimi" -> KIMI_BASE_URL
        "glm" -> GLM_BASE_URL
        "openai" -> OPENAI_BASE_URL
        "anthropic" -> ANTHROPIC_BASE_URL
        "gemini" -> GEMINI_BASE_URL
        "deepseek" -> DEEPSEEK_BASE_URL
        "qwen" -> QWEN_BASE_URL
        else -> null
    }

    fun modelsEndpoint(provider: String): String? = when (provider) {
        "openai", "openrouter", "kimi", "anthropic", "gemini", "deepseek" -> "${endpoint(provider)}/models"
        "qwen" -> "https://dashscope-intl.aliyuncs.com/api/v1/models"
        else -> null // Ark needs deployment IDs; GLM has no verified discovery contract here.
    }

    fun defaultModel(provider: String): String = when (provider) {
        "ark" -> ARK_MODEL
        "kimi" -> "kimi-k3"
        "glm" -> "glm-5.3"
        "openai" -> "gpt-4.1-mini"
        else -> ""
    }

    fun validModel(model: String): Boolean = model.isNotBlank() &&
        model.codePointCount(0, model.length) <= 200 && model.none { it.isISOControl() || it.isWhitespace() }

    fun modelFor(mode: AiMode, arkModel: String, openRouterModel: String): String = when (mode) {
        AiMode.SERVER_ARK -> ARK_MODEL
        AiMode.BYOK_ARK -> arkModel.trim().ifEmpty { ARK_MODEL }
        AiMode.BYOK_OPENROUTER -> openRouterModel.trim()
        else -> defaultModel(mode.providerId)
    }
}

data class AiCaptureConfiguration(val mode: AiMode, val model: String)
