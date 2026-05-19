package com.hop.mesh.encryption

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.SecureRandom

/**
 * Manages X25519 (Curve25519) key operations for Diffie-Hellman key exchange.
 */
object X25519Manager {

    /**
     * Generate a new random X25519 key pair.
     */
    fun generateKeyPair(): AsymmetricCipherKeyPair {
        val generator = X25519KeyPairGenerator()
        generator.init(X25519KeyGenerationParameters(SecureRandom()))
        return generator.generateKeyPair()
    }

    /**
     * Extract the raw 32-byte public key from the key pair.
     */
    fun getRawPublicKey(keyPair: AsymmetricCipherKeyPair): ByteArray {
        val publicKey = keyPair.public as X25519PublicKeyParameters
        return publicKey.encoded
    }

    /**
     * Extract the raw 32-byte private key from the key pair.
     */
    fun getRawPrivateKey(keyPair: AsymmetricCipherKeyPair): ByteArray {
        val privateKey = keyPair.private as X25519PrivateKeyParameters
        return privateKey.encoded
    }

    /**
     * Reconstruct PrivateKeyParameters from raw bytes.
     */
    fun privateKeyFromBytes(raw: ByteArray): X25519PrivateKeyParameters {
        return X25519PrivateKeyParameters(raw, 0)
    }

    /**
     * Reconstruct PublicKeyParameters from raw bytes.
     */
    fun publicKeyFromBytes(raw: ByteArray): X25519PublicKeyParameters {
        return X25519PublicKeyParameters(raw, 0)
    }

    /**
     * Calculate the 32-byte shared secret using our private key and the remote public key.
     */
    fun calculateSharedSecret(privateKey: X25519PrivateKeyParameters, remotePublicKey: X25519PublicKeyParameters): ByteArray {
        val secret = ByteArray(32)
        privateKey.generateSecret(remotePublicKey, secret, 0)
        return secret
    }
}
