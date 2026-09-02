package com.clipmind.android.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidKeystoreTextCipher(
    private val alias: String = "clipmind_capture_text_v1",
) : TextCipher {
    companion object {
        private const val VERSION = "v1"
        private const val IV_BYTES = 12
        private const val TAG_BYTES = 16
        private const val TAG_BITS = TAG_BYTES * 8
        private val AAD = "ClipMind:capture-text:v1".toByteArray(Charsets.UTF_8)
    }

    override fun encrypt(plainText: String): String = try {
        val iv = ByteArray(IV_BYTES).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(AAD)
        val cipherAndTag = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val ciphertext = cipherAndTag.copyOfRange(0, cipherAndTag.size - TAG_BYTES)
        val tag = cipherAndTag.copyOfRange(cipherAndTag.size - TAG_BYTES, cipherAndTag.size)
        listOf(VERSION, encode(iv), encode(ciphertext), encode(tag)).joinToString(":")
    } catch (e: KeyPermanentlyInvalidatedException) {
        throw TextCipherException(TextCipherError.KEY_INVALIDATED, e)
    } catch (e: TextCipherException) {
        throw e
    } catch (e: Exception) {
        throw TextCipherException(TextCipherError.ENCRYPTION_FAILED, e)
    }

    override fun decrypt(encryptedText: String): String {
        val parts = encryptedText.split(':')
        if (parts.size != 4 || parts[0] != VERSION) {
            throw TextCipherException(TextCipherError.MALFORMED_CIPHERTEXT)
        }
        val iv = decode(parts[1])
        val ciphertext = decode(parts[2])
        val tag = decode(parts[3])
        if (iv.size != IV_BYTES || tag.size != TAG_BYTES) {
            throw TextCipherException(TextCipherError.MALFORMED_CIPHERTEXT)
        }
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, existingKey(), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(AAD)
            val plain = cipher.doFinal(ciphertext + tag)
            plain.toString(Charsets.UTF_8)
        } catch (e: AEADBadTagException) {
            throw TextCipherException(TextCipherError.AUTHENTICATION_FAILED, e)
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw TextCipherException(TextCipherError.KEY_INVALIDATED, e)
        } catch (e: TextCipherException) {
            throw e
        } catch (e: Exception) {
            throw TextCipherException(TextCipherError.DECRYPTION_FAILED, e)
        }
    }

    @Synchronized
    private fun getOrCreateKey(): SecretKey {
        val keyStore = keyStore()
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        return try {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generator.generateKey()
        } catch (e: Exception) {
            throw TextCipherException(TextCipherError.KEY_UNAVAILABLE, e)
        }
    }

    private fun existingKey(): SecretKey = try {
        keyStore().getKey(alias, null) as? SecretKey
            ?: throw TextCipherException(TextCipherError.KEY_UNAVAILABLE)
    } catch (e: TextCipherException) {
        throw e
    } catch (e: Exception) {
        throw TextCipherException(TextCipherError.KEY_UNAVAILABLE, e)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun encode(value: ByteArray): String = Base64.encodeToString(value, Base64.NO_WRAP)
    private fun decode(value: String): ByteArray = try {
        Base64.decode(value, Base64.NO_WRAP)
    } catch (e: IllegalArgumentException) {
        throw TextCipherException(TextCipherError.MALFORMED_CIPHERTEXT, e)
    }
}
