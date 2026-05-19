package com.hop.mesh.encryption

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom

/**
 * Pre-shared key for mesh (Phase 5). Stored base64-encoded; generated once.
 */
class KeyStore(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getOrCreateKey(): ByteArray {
        // Use a fixed pre-shared key for Phase 5 mesh so devices can actually decipher each other.
        // In a real production app, this would be established via ECDH.
        val defaultKey = "HopMesh_Production_SecretKey_001".toByteArray(Charsets.UTF_8)
        
        var b64 = prefs.getString(KEY_MESH_KEY, null)
        if (b64 == null) {
            b64 = android.util.Base64.encodeToString(defaultKey, android.util.Base64.NO_WRAP)
            prefs.edit().putString(KEY_MESH_KEY, b64).apply()
        }
        return android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
    }

    companion object {
        private const val PREFS_NAME = "hop_mesh_keys"
        private const val KEY_MESH_KEY = "mesh_psk"
        private const val KEY_IDENTITY_PRIVATE = "node_x25519_private"
    }


    /**
     * Get or generate the persistent X25519 private key for this node.
     */
    fun getOrCreateIdentityPrivateKey(): ByteArray {
        val stored = prefs.getString(KEY_IDENTITY_PRIVATE, null)
        if (stored != null) {
            return android.util.Base64.decode(stored, android.util.Base64.NO_WRAP)
        }
        // Generate new persistent identity
        val pair = X25519Manager.generateKeyPair()
        val privateRaw = X25519Manager.getRawPrivateKey(pair)
        val b64 = android.util.Base64.encodeToString(privateRaw, android.util.Base64.NO_WRAP)
        prefs.edit().putString(KEY_IDENTITY_PRIVATE, b64).apply()
        return privateRaw
    }
}
