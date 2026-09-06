package com.clipmind.android.security

import java.security.InvalidAlgorithmParameterException
import java.security.InvalidKeyException
import java.security.KeyStoreException
import java.security.ProviderException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class CipherSafetyTest {
    @Test fun envelopeRoundTripPreservesIvCiphertextAndTag() {
        val iv = ByteArray(GCM_IV_BYTES) { it.toByte() }
        val ciphertext = byteArrayOf(1, 2, 3, 4)
        val tag = ByteArray(GCM_TAG_BYTES) { (it + 20).toByte() }

        val decoded = decodeEnvelope(encodeEnvelope(iv, ciphertext + tag))

        assertArrayEquals(iv, decoded.iv)
        assertArrayEquals(ciphertext, decoded.ciphertext)
        assertArrayEquals(tag, decoded.tag)
    }

    @Test fun generatedIvMustBePresentAndExactly96Bits() {
        assertCipherError(TextCipherError.INVALID_PARAMETERS) { requireValidGeneratedIv(null) }
        assertCipherError(TextCipherError.INVALID_PARAMETERS) {
            requireValidGeneratedIv(ByteArray(GCM_IV_BYTES - 1))
        }
        assertCipherError(TextCipherError.INVALID_PARAMETERS) {
            requireValidGeneratedIv(ByteArray(GCM_IV_BYTES + 1))
        }
        assertEquals(GCM_IV_BYTES, requireValidGeneratedIv(ByteArray(GCM_IV_BYTES)).size)
    }

    @Test fun malformedEnvelopeComponentsAreRejected() {
        assertCipherError(TextCipherError.MALFORMED_CIPHERTEXT) { decodeEnvelope("v2:a:b:c") }
        assertCipherError(TextCipherError.MALFORMED_CIPHERTEXT) { decodeEnvelope("v1:not-base64::") }
        val shortIv = java.util.Base64.getEncoder().encodeToString(ByteArray(GCM_IV_BYTES - 1))
        val tag = java.util.Base64.getEncoder().encodeToString(ByteArray(GCM_TAG_BYTES))
        assertCipherError(TextCipherError.MALFORMED_CIPHERTEXT) {
            decodeEnvelope("$ENVELOPE_VERSION:$shortIv::$tag")
        }
    }

    @Test fun failuresAreClassifiedWithoutIncludingSensitiveValues() {
        assertEquals(
            TextCipherError.INVALID_KEY,
            classifyCipherFailure(InvalidKeyException("do not surface"), TextCipherError.ENCRYPTION_FAILED),
        )
        assertEquals(
            TextCipherError.INVALID_PARAMETERS,
            classifyCipherFailure(
                InvalidAlgorithmParameterException("do not surface"),
                TextCipherError.ENCRYPTION_FAILED,
            ),
        )
        assertEquals(
            TextCipherError.KEYSTORE_UNAVAILABLE,
            classifyCipherFailure(KeyStoreException("do not surface"), TextCipherError.ENCRYPTION_FAILED),
        )
        assertEquals(
            TextCipherError.INVALID_KEY,
            classifyCipherFailure(
                ProviderException("wrapper", InvalidKeyException("nested")),
                TextCipherError.ENCRYPTION_FAILED,
            ),
        )
        assertEquals(
            TextCipherError.ENCRYPTION_FAILED,
            classifyCipherFailure(IllegalStateException("do not surface"), TextCipherError.ENCRYPTION_FAILED),
        )
    }

    private fun assertCipherError(expected: TextCipherError, block: () -> Unit) {
        try {
            block()
            fail("Expected TextCipherException")
        } catch (error: TextCipherException) {
            assertEquals(expected, error.error)
        }
    }
}
