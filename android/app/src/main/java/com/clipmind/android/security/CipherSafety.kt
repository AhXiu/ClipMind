package com.clipmind.android.security

import java.security.InvalidAlgorithmParameterException
import java.security.InvalidKeyException
import java.security.KeyStoreException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import java.util.Base64

internal const val ENVELOPE_VERSION = "v1"
internal const val GCM_IV_BYTES = 12
internal const val GCM_TAG_BYTES = 16

internal data class EncryptedEnvelope(
    val iv: ByteArray,
    val ciphertext: ByteArray,
    val tag: ByteArray,
)

/** Android Keystore AES-GCM is expected to generate the standard 96-bit nonce. */
internal fun requireValidGeneratedIv(iv: ByteArray?): ByteArray {
    if (iv == null || iv.size != GCM_IV_BYTES) {
        throw TextCipherException(TextCipherError.INVALID_PARAMETERS)
    }
    return iv
}

internal fun encodeEnvelope(iv: ByteArray?, cipherAndTag: ByteArray): String {
    val validIv = requireValidGeneratedIv(iv)
    if (cipherAndTag.size < GCM_TAG_BYTES) {
        throw TextCipherException(TextCipherError.ENCRYPTION_FAILED)
    }
    val ciphertextSize = cipherAndTag.size - GCM_TAG_BYTES
    return listOf(
        ENVELOPE_VERSION,
        encodeBase64(validIv),
        encodeBase64(cipherAndTag.copyOfRange(0, ciphertextSize)),
        encodeBase64(cipherAndTag.copyOfRange(ciphertextSize, cipherAndTag.size)),
    ).joinToString(":")
}

internal fun decodeEnvelope(encryptedText: String): EncryptedEnvelope {
    val parts = encryptedText.split(':')
    if (parts.size != 4 || parts[0] != ENVELOPE_VERSION) malformedEnvelope()

    val iv = decodeBase64(parts[1])
    val ciphertext = decodeBase64(parts[2])
    val tag = decodeBase64(parts[3])
    if (iv.size != GCM_IV_BYTES || tag.size != GCM_TAG_BYTES) malformedEnvelope()
    return EncryptedEnvelope(iv, ciphertext, tag)
}

internal fun classifyCipherFailure(
    error: Throwable,
    fallback: TextCipherError,
): TextCipherError {
    var current: Throwable? = error
    var keystoreUnavailable = false
    val visited = mutableSetOf<Throwable>()
    while (current != null && visited.add(current)) {
        when (current) {
            is InvalidAlgorithmParameterException, is IllegalArgumentException ->
                return TextCipherError.INVALID_PARAMETERS
            is InvalidKeyException -> return TextCipherError.INVALID_KEY
            is KeyStoreException, is UnrecoverableKeyException, is ProviderException ->
                keystoreUnavailable = true
        }
        if (current.javaClass.name == "android.security.KeyStoreException") {
            keystoreUnavailable = true
        }
        current = current.cause
    }
    return if (keystoreUnavailable) TextCipherError.KEYSTORE_UNAVAILABLE else fallback
}

private fun encodeBase64(value: ByteArray): String = Base64.getEncoder().encodeToString(value)

private fun decodeBase64(value: String): ByteArray = try {
    Base64.getDecoder().decode(value)
} catch (error: IllegalArgumentException) {
    throw TextCipherException(TextCipherError.MALFORMED_CIPHERTEXT, error)
}

private fun malformedEnvelope(): Nothing =
    throw TextCipherException(TextCipherError.MALFORMED_CIPHERTEXT)
