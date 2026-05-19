package com.hop.mesh.encryption

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Phase 5: AES-256-GCM for message payload encryption.
 * Pre-shared key stored in preferences (Phase 5); ECDH optional later.
 */
class MessageCrypto(private val keyProvider: () -> ByteArray) {

    companion object {
        private const val ALG = "AES/GCM/NoPadding"
        private const val KEY_SIZE_BITS = 256
        private const val GCM_TAG_LEN_BITS = 128
        private const val GCM_IV_LEN_BYTES = 12
    }

    fun encrypt(plaintext: ByteArray, customKey: ByteArray? = null): ByteArray {
        val rawKey = customKey ?: keyProvider()
        val key = rawKey.take(32).toByteArray().let { SecretKeySpec(it, "AES") }
        val iv = ByteArray(GCM_IV_LEN_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(ALG)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LEN_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext)
        return iv + ciphertext
    }

    fun decrypt(encrypted: ByteArray, customKey: ByteArray? = null): ByteArray {
        if (encrypted.size < GCM_IV_LEN_BYTES + 16) throw IllegalArgumentException("Too short")
        val rawKey = customKey ?: keyProvider()
        val key = rawKey.take(32).toByteArray().let { SecretKeySpec(it, "AES") }
        val iv = encrypted.copyOfRange(0, GCM_IV_LEN_BYTES)
        val cipher = Cipher.getInstance(ALG)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LEN_BITS, iv))
        return cipher.doFinal(encrypted, GCM_IV_LEN_BYTES, encrypted.size - GCM_IV_LEN_BYTES)
    }

    fun encryptUtf8(text: String, customKey: ByteArray? = null): ByteArray = encrypt(text.toByteArray(Charsets.UTF_8), customKey)
    fun decryptUtf8(encrypted: ByteArray, customKey: ByteArray? = null): String = String(decrypt(encrypted, customKey), Charsets.UTF_8)
}
