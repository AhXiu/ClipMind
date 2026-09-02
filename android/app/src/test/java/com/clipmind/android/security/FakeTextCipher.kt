package com.clipmind.android.security

import java.security.MessageDigest
import java.util.Base64

class FakeTextCipher : TextCipher {
    override fun encrypt(plainText: String): String {
        val payload = Base64.getEncoder().encodeToString(plainText.toByteArray(Charsets.UTF_8))
        return "fake:v1:${digest(plainText)}:$payload"
    }

    override fun decrypt(encryptedText: String): String {
        val parts = encryptedText.split(':')
        if (parts.size != 4 || parts[0] != "fake" || parts[1] != "v1") {
            throw TextCipherException(TextCipherError.MALFORMED_CIPHERTEXT)
        }
        val plainText = try {
            Base64.getDecoder().decode(parts[3]).toString(Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            throw TextCipherException(TextCipherError.MALFORMED_CIPHERTEXT, e)
        }
        if (parts[2] != digest(plainText)) {
            throw TextCipherException(TextCipherError.AUTHENTICATION_FAILED)
        }
        return plainText
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
