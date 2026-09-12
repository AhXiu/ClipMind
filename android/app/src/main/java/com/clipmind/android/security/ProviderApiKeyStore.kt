package com.clipmind.android.security

import android.content.SharedPreferences
import com.clipmind.android.data.AiDefaults
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Credentials are bound to the task's provider, never the currently selected UI provider. */
class ProviderApiKeyStore(private val preferences: SharedPreferences, private val cipher: TextCipher) {
    private val configuredState = MutableStateFlow(AiDefaults.providerIds.filter { preferences.contains(keyName(it)) }.toSet())
    val configured = configuredState.asStateFlow()
    private val legacyState = MutableStateFlow(preferences.contains(LEGACY_KEY))
    val legacyConfigured = legacyState.asStateFlow()

    @Synchronized fun readForAuthorization(provider: String): String? {
        if (provider !in AiDefaults.providerIds) return null
        val encrypted = preferences.getString(keyName(provider), null) ?: return null
        return try {
            cipher.decrypt(encrypted).takeIf(::validApiKey)
        } catch (_: TextCipherException) {
            clear(provider)
            null
        }
    }

    @Synchronized fun overwrite(provider: String, key: String): Boolean {
        val normalized = key.trim()
        if (provider !in AiDefaults.providerIds || !validApiKey(normalized)) return false
        return try {
            val saved = preferences.edit().putString(keyName(provider), cipher.encrypt(normalized)).commit()
            if (saved) configuredState.value = configuredState.value + provider
            saved
        } catch (_: TextCipherException) { false }
    }

    @Synchronized fun clear(provider: String): Boolean {
        if (provider !in AiDefaults.providerIds) return false
        val saved = preferences.edit().remove(keyName(provider)).commit()
        if (saved) configuredState.value = configuredState.value - provider
        return saved
    }

    /** Explicit user confirmation is required because old ciphertext has no provider provenance. */
    @Synchronized fun migrateLegacy(provider: String): Boolean {
        if (provider !in setOf("ark", "openrouter") || preferences.contains(keyName(provider))) return false
        val encrypted = preferences.getString(LEGACY_KEY, null) ?: return false
        val valid = try { validApiKey(cipher.decrypt(encrypted)) } catch (_: TextCipherException) { false }
        if (!valid) return false
        val saved = preferences.edit().putString(keyName(provider), encrypted).remove(LEGACY_KEY).commit()
        if (saved) {
            configuredState.value = configuredState.value + provider
            legacyState.value = false
        }
        return saved
    }

    companion object {
        private const val LEGACY_KEY = "encrypted_provider_api_key"
        private fun keyName(provider: String) = "encrypted_provider_api_key_$provider"
        fun validApiKey(value: String): Boolean = value.length in 1..4096 && value.all { it.code in 33..126 }
    }
}
