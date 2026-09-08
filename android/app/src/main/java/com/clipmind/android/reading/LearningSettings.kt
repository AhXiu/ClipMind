package com.clipmind.android.reading

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LearningPreferences(val dailyLimit: Int = 5, val reminders: Boolean = false, val automaticAnalysis: Boolean = false, val automaticRelations: Boolean = false, val weeklyReports: Boolean = false, val notionPage: String = "")
class LearningSettings(context: Context) {
    private val prefs = context.getSharedPreferences("learning_settings", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(LearningPreferences(prefs.getInt("daily",5),prefs.getBoolean("reminders",false),prefs.getBoolean("analysis",false),prefs.getBoolean("relations",false),prefs.getBoolean("weekly",false),prefs.getString("notion_page","").orEmpty()))
    val state = mutable.asStateFlow()
    fun update(value: LearningPreferences) {
        val safe = value.copy(dailyLimit = value.dailyLimit.coerceIn(3,5))
        prefs.edit().putInt("daily",safe.dailyLimit).putBoolean("reminders",safe.reminders).putBoolean("analysis",safe.automaticAnalysis).putBoolean("relations",safe.automaticRelations).putBoolean("weekly",safe.weeklyReports).putString("notion_page",safe.notionPage).apply()
        mutable.value = safe
    }
    fun marker(key: String) = prefs.getString(key,null)
    fun mark(key: String, value: String) { prefs.edit().putString(key,value).apply() }
}
