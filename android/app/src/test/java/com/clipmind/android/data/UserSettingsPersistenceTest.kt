package com.clipmind.android.data

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test

class UserSettingsPersistenceTest {
    @Test fun exportCaptureAndAiPreferencesSurviveRecreation() {
        val prefs = TestPreferences()
        UserSettings(prefs).apply {
            setMarkdownTemplate("# {{title}}\n{{content}}")
            setWikiLinkFormat(WikiLinkFormat.FILE_NAME_WITH_TITLE)
            setFrontmatterTags(false); setFrontmatterTime(false); setFrontmatterSource(false)
            setMinimumCaptureLength(23); setDuplicateStrategy(DuplicateStrategy.ALLOW)
            setVibrationEnabled(true); setAiEnabled(false); setAiAutoSubmit(false)
        }
        val restored = UserSettings(prefs)
        assertEquals("# {{title}}\n{{content}}", restored.markdownTemplate.value)
        assertEquals(WikiLinkFormat.FILE_NAME_WITH_TITLE, restored.wikiLinkFormat.value)
        assertFalse(restored.frontmatterTags.value); assertFalse(restored.frontmatterTime.value); assertFalse(restored.frontmatterSource.value)
        assertEquals(23, restored.minimumCaptureLength.value)
        assertEquals(DuplicateStrategy.ALLOW, restored.duplicateStrategy.value)
        assertTrue(restored.vibrationEnabled.value); assertFalse(restored.aiEnabled.value); assertFalse(restored.aiAutoSubmit.value)
    }

    @Test fun minimumLengthIsClampedAndBlankTemplateUsesSafeDefault() {
        val settings = UserSettings(TestPreferences())
        settings.setMinimumCaptureLength(0); settings.setMarkdownTemplate("   ")
        assertEquals(1, settings.minimumCaptureLength.value)
        assertEquals(SettingsDefaults.MARKDOWN_TEMPLATE, settings.markdownTemplate.value)
    }
}

private class TestPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()
    override fun getAll(): MutableMap<String, *> = values
    override fun getString(key: String?, defValue: String?) = values[key] as? String ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?) = @Suppress("UNCHECKED_CAST") ((values[key] as? Set<String>)?.toMutableSet() ?: defValues)
    override fun getInt(key: String?, defValue: Int) = values[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long) = values[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float) = values[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean) = values[key] as? Boolean ?: defValue
    override fun contains(key: String?) = values.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun edit(): SharedPreferences.Editor = Editor()
    private inner class Editor : SharedPreferences.Editor {
        private val updates = mutableMapOf<String, Any?>(); private val removals = mutableSetOf<String>(); private var clear = false
        override fun putString(key: String?, value: String?) = apply { updates[key!!] = value }
        override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { updates[key!!] = values }
        override fun putInt(key: String?, value: Int) = apply { updates[key!!] = value }
        override fun putLong(key: String?, value: Long) = apply { updates[key!!] = value }
        override fun putFloat(key: String?, value: Float) = apply { updates[key!!] = value }
        override fun putBoolean(key: String?, value: Boolean) = apply { updates[key!!] = value }
        override fun remove(key: String?) = apply { removals += key!! }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean { applyChanges(); return true }
        override fun apply() = applyChanges()
        private fun applyChanges() { if (clear) values.clear(); removals.forEach(values::remove); updates.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value } }
    }
}
