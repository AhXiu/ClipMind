package com.clipmind.android.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DuplicateStrategy { SKIP_24_HOURS, ALLOW }
enum class WikiLinkFormat { FILE_NAME, FILE_NAME_WITH_TITLE }

data class ExportPreferences(
    val markdownTemplate: String,
    val wikiLinkFormat: WikiLinkFormat,
    val includeTags: Boolean,
    val includeTime: Boolean,
    val includeSource: Boolean,
)

object SettingsDefaults {
    const val MARKDOWN_TEMPLATE = "{{standard_card}}"
    const val MIN_CAPTURE_LENGTH = 8
}

class UserSettings(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("settings", Context.MODE_PRIVATE))

    private fun stringFlow(key: String, default: String) = MutableStateFlow(prefs.getString(key, default) ?: default)
    private fun booleanFlow(key: String, default: Boolean) = MutableStateFlow(prefs.getBoolean(key, default))
    private fun intFlow(key: String, default: Int) = MutableStateFlow(prefs.getInt(key, default))

    private val modeFlow = MutableStateFlow(runCatching { CaptureMode.valueOf(prefs.getString("mode", CaptureMode.CONFIRM.name)!!) }.getOrDefault(CaptureMode.CONFIRM))
    private val captureFlow = booleanFlow("capture_requested", false)
    private val aiModeFlow = MutableStateFlow(readEnum("ai_mode", AiMode.SERVER_ARK))
    private val arkModelFlow = stringFlow("ark_model", AiDefaults.ARK_MODEL)
    private val openRouterModelFlow = stringFlow("openrouter_model", "")
    private val markdownTemplateFlow = stringFlow("markdown_template", SettingsDefaults.MARKDOWN_TEMPLATE).also { if(it.value == "{{content}}\n\n{{wikilinks}}") it.value = SettingsDefaults.MARKDOWN_TEMPLATE }
    private val wikiLinkFormatFlow = MutableStateFlow(readEnum("wikilink_format", WikiLinkFormat.FILE_NAME))
    private val frontmatterTagsFlow = booleanFlow("frontmatter_tags", true)
    private val frontmatterTimeFlow = booleanFlow("frontmatter_time", true)
    private val frontmatterSourceFlow = booleanFlow("frontmatter_source", true)
    private val minimumLengthFlow = intFlow("minimum_capture_length", SettingsDefaults.MIN_CAPTURE_LENGTH)
    private val duplicateStrategyFlow = MutableStateFlow(readEnum("duplicate_strategy", DuplicateStrategy.SKIP_24_HOURS))
    private val vibrationFlow = booleanFlow("capture_vibration", false)
    private val aiEnabledFlow = booleanFlow("ai_enabled", true)
    private val aiAutoSubmitFlow = booleanFlow("ai_auto_submit", true)

    val mode = modeFlow.asStateFlow(); val captureRequested = captureFlow.asStateFlow()
    val aiMode = aiModeFlow.asStateFlow(); val arkModel = arkModelFlow.asStateFlow(); val openRouterModel = openRouterModelFlow.asStateFlow()
    val markdownTemplate = markdownTemplateFlow.asStateFlow(); val wikiLinkFormat = wikiLinkFormatFlow.asStateFlow()
    val frontmatterTags = frontmatterTagsFlow.asStateFlow(); val frontmatterTime = frontmatterTimeFlow.asStateFlow(); val frontmatterSource = frontmatterSourceFlow.asStateFlow()
    val minimumCaptureLength = minimumLengthFlow.asStateFlow(); val duplicateStrategy = duplicateStrategyFlow.asStateFlow()
    val vibrationEnabled = vibrationFlow.asStateFlow(); val aiEnabled = aiEnabledFlow.asStateFlow(); val aiAutoSubmit = aiAutoSubmitFlow.asStateFlow()

    fun setMode(value: CaptureMode) = putEnum("mode", value, modeFlow)
    fun setCaptureRequested(value: Boolean) = putBoolean("capture_requested", value, captureFlow)
    fun setAiMode(value: AiMode) = putEnum("ai_mode", value, aiModeFlow)
    fun setArkModel(value: String) = putString("ark_model", value.trim().ifEmpty { AiDefaults.ARK_MODEL }, arkModelFlow)
    fun setOpenRouterModel(value: String) = putString("openrouter_model", value.trim(), openRouterModelFlow)
    fun setMarkdownTemplate(value: String) = putString("markdown_template", value.ifBlank { SettingsDefaults.MARKDOWN_TEMPLATE }, markdownTemplateFlow)
    fun setWikiLinkFormat(value: WikiLinkFormat) = putEnum("wikilink_format", value, wikiLinkFormatFlow)
    fun setFrontmatterTags(value: Boolean) = putBoolean("frontmatter_tags", value, frontmatterTagsFlow)
    fun setFrontmatterTime(value: Boolean) = putBoolean("frontmatter_time", value, frontmatterTimeFlow)
    fun setFrontmatterSource(value: Boolean) = putBoolean("frontmatter_source", value, frontmatterSourceFlow)
    fun setMinimumCaptureLength(value: Int) { val safe = value.coerceIn(1, 10_000); prefs.edit().putInt("minimum_capture_length", safe).apply(); minimumLengthFlow.value = safe }
    fun setDuplicateStrategy(value: DuplicateStrategy) = putEnum("duplicate_strategy", value, duplicateStrategyFlow)
    fun setVibrationEnabled(value: Boolean) = putBoolean("capture_vibration", value, vibrationFlow)
    fun setAiEnabled(value: Boolean) = putBoolean("ai_enabled", value, aiEnabledFlow)
    fun setAiAutoSubmit(value: Boolean) = putBoolean("ai_auto_submit", value, aiAutoSubmitFlow)

    fun exportPreferences() = ExportPreferences(markdownTemplateFlow.value, wikiLinkFormatFlow.value, frontmatterTagsFlow.value, frontmatterTimeFlow.value, frontmatterSourceFlow.value)
    fun captureAiConfiguration(): AiCaptureConfiguration {
        val selected = aiModeFlow.value
        return AiCaptureConfiguration(selected, AiDefaults.modelFor(selected, arkModelFlow.value, openRouterModelFlow.value))
    }

    private inline fun <reified T : Enum<T>> readEnum(key: String, default: T): T = runCatching { enumValueOf<T>(prefs.getString(key, default.name)!!) }.getOrDefault(default)
    private fun putString(key: String, value: String, flow: MutableStateFlow<String>) { prefs.edit().putString(key, value).apply(); flow.value = value }
    private fun putBoolean(key: String, value: Boolean, flow: MutableStateFlow<Boolean>) { prefs.edit().putBoolean(key, value).apply(); flow.value = value }
    private fun <T : Enum<T>> putEnum(key: String, value: T, flow: MutableStateFlow<T>) { prefs.edit().putString(key, value.name).apply(); flow.value = value }
}
