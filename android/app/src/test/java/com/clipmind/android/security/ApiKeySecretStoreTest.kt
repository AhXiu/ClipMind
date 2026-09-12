package com.clipmind.android.security

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiKeySecretStoreTest {
    @Test fun providerKeysAreIsolatedAndUnknownProviderCannotReceiveCredentials() {
        val prefs = MemoryPreferences()
        val store = ProviderApiKeyStore(prefs, FakeTextCipher())
        assertTrue(store.overwrite("kimi", " kimi-test-key "))
        assertTrue(store.overwrite("glm", "glm-test-key"))
        assertEquals("kimi-test-key", store.readForAuthorization("kimi"))
        assertEquals("glm-test-key", store.readForAuthorization("glm"))
        assertNull(store.readForAuthorization("openai"))
        assertNull(store.readForAuthorization("https://attacker.invalid"))
        assertFalse(store.overwrite("unknown", "key"))
        assertFalse(store.overwrite("openai", "key\r\nheader"))
        assertFalse(store.overwrite("openai", "Bearer key"))
        assertTrue(prefs.all.values.none { it == "kimi-test-key" || it == "glm-test-key" })
        val restored = ProviderApiKeyStore(prefs, FakeTextCipher())
        assertEquals(setOf("kimi", "glm"), restored.configured.value)
        assertTrue(restored.clear("kimi"))
        assertEquals("glm-test-key", restored.readForAuthorization("glm"))
        assertNull(restored.readForAuthorization("kimi"))
    }

    @Test fun legacyKeyNeverLeaksBeforeExplicitProviderAssignment() {
        val prefs = MemoryPreferences()
        KeystoreApiKeySecretStore(prefs, FakeTextCipher()).overwrite("legacy-key")
        val store = ProviderApiKeyStore(prefs, FakeTextCipher())
        assertTrue(store.legacyConfigured.value)
        listOf("ark", "openrouter", "kimi", "glm", "openai").forEach { assertNull(store.readForAuthorization(it)) }
        assertFalse(store.migrateLegacy("kimi"))
        assertTrue(store.migrateLegacy("openrouter"))
        assertFalse(store.legacyConfigured.value)
        assertFalse(prefs.contains("encrypted_provider_api_key"))
        assertEquals("legacy-key", store.readForAuthorization("openrouter"))
        assertNull(store.readForAuthorization("ark"))
        assertFalse(store.migrateLegacy("ark"))
    }

    @Test fun migrationDoesNotOverwriteAnExistingProviderKey() {
        val prefs = MemoryPreferences()
        KeystoreApiKeySecretStore(prefs, FakeTextCipher()).overwrite("legacy-key")
        val store = ProviderApiKeyStore(prefs, FakeTextCipher())
        store.overwrite("ark", "new-key")
        assertFalse(store.migrateLegacy("ark"))
        assertEquals("new-key", store.readForAuthorization("ark"))
        assertTrue(store.legacyConfigured.value)
    }

    @Test fun storesOnlyCiphertextAndExposesOnlyConfiguredStateForUi() {
        val prefs = MemoryPreferences()
        val store: ApiKeySecretStore = KeystoreApiKeySecretStore(prefs, FakeTextCipher())
        assertTrue(store.overwrite(" secret-key "))
        assertTrue(store.configured.value)
        assertNotEquals("secret-key", prefs.all.values.single())
        assertEquals("secret-key", store.readForAuthorization())
        assertFalse(ApiKeySecretStore::class.java.methods.any { it.name.contains("display", true) })
        store.clear()
        assertFalse(store.configured.value)
        assertNull(store.readForAuthorization())
    }
}

private class MemoryPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()
    override fun getAll(): MutableMap<String, *> = values
    override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        @Suppress("UNCHECKED_CAST") ((values[key] as? Set<String>)?.toMutableSet() ?: defValues)
    override fun getInt(key: String?, defValue: Int) = values[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long) = values[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float) = values[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean) = values[key] as? Boolean ?: defValue
    override fun contains(key: String?) = values.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun edit(): SharedPreferences.Editor = Editor()

    private inner class Editor : SharedPreferences.Editor {
        private val updates = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var clear = false
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
        private fun applyChanges() {
            if (clear) values.clear()
            removals.forEach(values::remove)
            updates.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
        }
    }
}
