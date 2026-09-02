package com.clipmind.android.security

interface TextCipher {
    @Throws(TextCipherException::class)
    fun encrypt(plainText: String): String

    @Throws(TextCipherException::class)
    fun decrypt(encryptedText: String): String
}

enum class TextCipherError {
    KEY_UNAVAILABLE,
    KEY_INVALIDATED,
    ENCRYPTION_FAILED,
    MALFORMED_CIPHERTEXT,
    AUTHENTICATION_FAILED,
    DECRYPTION_FAILED,
}

class TextCipherException(
    val error: TextCipherError,
    cause: Throwable? = null,
) : Exception(error.name, cause)
