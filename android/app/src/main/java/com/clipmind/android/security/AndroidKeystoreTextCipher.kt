package com.clipmind.android.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidKeystoreTextCipher(
    private val alias: String = "clipmind_capture_text_v1",
) : TextCipher {
    companion object {
        private const val TAG_BITS = GCM_TAG_BYTES * 8
        private val AAD = "ClipMind:capture-text:v1".toByteArray(Charsets.UTF_8)
    }

    override fun encrypt(plainText: String): String = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = requireValidGeneratedIv(cipher.iv)
        cipher.updateAAD(AAD)
        encodeEnvelope(iv, cipher.doFinal(plainText.toByteArray(Charsets.UTF_8)))
    } catch (error: KeyPermanentlyInvalidatedException) {
        throw TextCipherException(TextCipherError.KEY_INVALIDATED, error)
    } catch (error: TextCipherException) {
        throw error
    } catch (error: Exception) {
        throw TextCipherException(
            classifyCipherFailure(error, TextCipherError.ENCRYPTION_FAILED),
            error,
        )
    }

    override fun decrypt(encryptedText: String): String {
        val envelope = decodeEnvelope(encryptedText)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, existingKey(), GCMParameterSpec(TAG_BITS, envelope.iv))
            cipher.updateAAD(AAD)
            val plain = cipher.doFinal(envelope.ciphertext + envelope.tag)
            plain.toString(Charsets.UTF_8)
        } catch (error: AEADBadTagException) {
            throw TextCipherException(TextCipherError.AUTHENTICATION_FAILED, error)
        } catch (error: KeyPermanentlyInvalidatedException) {
            throw TextCipherException(TextCipherError.KEY_INVALIDATED, error)
        } catch (error: TextCipherException) {
            throw error
        } catch (error: Exception) {
            throw TextCipherException(
                classifyCipherFailure(error, TextCipherError.DECRYPTION_FAILED),
                error,
            )
        }
    }

    @Synchronized
    private fun getOrCreateKey(): SecretKey {
        val keyStore = keyStore()
        try {
            (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            return generator.generateKey()
        } catch (error: KeyPermanentlyInvalidatedException) {
            throw TextCipherException(TextCipherError.KEY_INVALIDATED, error)
        } catch (error: Exception) {
            throw TextCipherException(
                classifyCipherFailure(error, TextCipherError.KEYSTORE_UNAVAILABLE),
                error,
            )
        }
    }

    private fun existingKey(): SecretKey = try {
        keyStore().getKey(alias, null) as? SecretKey
            ?: throw TextCipherException(TextCipherError.KEYSTORE_UNAVAILABLE)
    } catch (error: TextCipherException) {
        throw error
    } catch (error: KeyPermanentlyInvalidatedException) {
        throw TextCipherException(TextCipherError.KEY_INVALIDATED, error)
    } catch (error: Exception) {
        throw TextCipherException(
            classifyCipherFailure(error, TextCipherError.KEYSTORE_UNAVAILABLE),
            error,
        )
    }

    private fun keyStore(): KeyStore = try {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    } catch (error: Exception) {
        throw TextCipherException(TextCipherError.KEYSTORE_UNAVAILABLE, error)
    }
}
