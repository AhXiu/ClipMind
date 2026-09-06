package com.clipmind.android.security

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Stores a provider API key as ciphertext only. The API deliberately exposes no display value. */
interface ApiKeySecretStore {
    val configured: StateFlow<Boolean>
    fun readForAuthorization(): String?
    fun overwrite(key: String): Boolean
    fun clear()
}

class KeystoreApiKeySecretStore(
    private val preferences: SharedPreferences,
    private val cipher: TextCipher,
) : ApiKeySecretStore {
    private companion object { const val ENCRYPTED_KEY = "encrypted_provider_api_key" }

    private val configuredState = MutableStateFlow(preferences.contains(ENCRYPTED_KEY))
    override val configured: StateFlow<Boolean> = configuredState.asStateFlow()

    override fun readForAuthorization(): String? {
        val encrypted = preferences.getString(ENCRYPTED_KEY, null) ?: return null
        return try {
            cipher.decrypt(encrypted).takeIf(String::isNotBlank)
        } catch (_: TextCipherException) {
            clear()
            null
        }
    }

    override fun overwrite(key: String): Boolean {
        val normalized = key.trim()
        if (normalized.isEmpty()) return false
        return try {
            val stored = preferences.edit().putString(ENCRYPTED_KEY, cipher.encrypt(normalized)).commit()
            if (stored) configuredState.value = true
            stored
        } catch (_: TextCipherException) {
            false
        }
    }

    override fun clear() {
        preferences.edit().remove(ENCRYPTED_KEY).commit()
        configuredState.value = false
    }
}
