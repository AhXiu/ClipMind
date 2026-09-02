package com.clipmind.android.security

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface TokenStore {
    val configured: StateFlow<Boolean>
    fun readToken(): String?
    fun saveToken(token: String): Boolean
    fun clearToken()
}

class SecureTokenStore(
    private val preferences: SharedPreferences,
    private val cipher: TextCipher,
) : TokenStore {
    companion object { private const val KEY = "encrypted_auth_token" }

    private val mutableConfigured = MutableStateFlow(preferences.contains(KEY))
    override val configured: StateFlow<Boolean> = mutableConfigured.asStateFlow()

    override fun readToken(): String? {
        val encrypted = preferences.getString(KEY, null) ?: return null
        return try {
            cipher.decrypt(encrypted).takeIf { it.isNotBlank() }
        } catch (_: TextCipherException) {
            clearToken()
            null
        }
    }

    override fun saveToken(token: String): Boolean {
        val normalized = token.trim()
        if (normalized.isEmpty()) {
            clearToken()
            return true
        }
        return try {
            val encrypted = cipher.encrypt(normalized)
            val saved = preferences.edit().putString(KEY, encrypted).commit()
            if (saved) mutableConfigured.value = true
            saved
        } catch (_: TextCipherException) {
            false
        }
    }

    override fun clearToken() {
        preferences.edit().remove(KEY).commit()
        mutableConfigured.value = false
    }
}
