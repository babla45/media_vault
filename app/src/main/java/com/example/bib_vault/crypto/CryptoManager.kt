package com.example.bib_vault.crypto

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Handles all cryptographic operations for the vault:
 * - Key derivation (PBKDF2)
 * - Index encryption/decryption (AES-256-GCM)
 * - Chunk encryption/decryption (AES-256-CTR)
 * - HMAC computation for file integrity
 *
 * AES-CTR is used for file data to enable random seek without
 * decrypting prior blocks. Each chunk gets a deterministic IV
 * derived from a per-file base IV XOR'd with the chunk index.
 */
object CryptoManager {

    private val secureRandom = SecureRandom()

    // ──────────────────────────────────────
    //  Random generation
    // ──────────────────────────────────────

    /** Generate a cryptographically secure random salt for PBKDF2 */
    fun generateSalt(): ByteArray {
        val salt = ByteArray(CryptoConstants.SALT_SIZE)
        secureRandom.nextBytes(salt)
        return salt
    }

    /** Generate a random GCM nonce (12 bytes) for index encryption */
    fun generateGcmIv(): ByteArray {
        val iv = ByteArray(CryptoConstants.GCM_IV_SIZE)
        secureRandom.nextBytes(iv)
        return iv
    }

    /** Generate a random CTR IV (16 bytes) as the base IV for a file's chunks */
    fun generateCtrBaseIv(): ByteArray {
        val iv = ByteArray(CryptoConstants.CTR_IV_SIZE)
        secureRandom.nextBytes(iv)
        return iv
    }

    // ──────────────────────────────────────
    //  Key derivation
    // ──────────────────────────────────────

    /**
     * Derive a 256-bit AES key from [password] and [salt] using PBKDF2-HMAC-SHA256.
     * The iteration count is intentionally high to resist brute-force attacks.
     */
    fun deriveKey(password: String, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(
            password.toCharArray(),
            salt,
            CryptoConstants.PBKDF2_ITERATIONS,
            CryptoConstants.KEY_SIZE_BITS
        )
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = factory.generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(keyBytes, "AES")
    }

    // ──────────────────────────────────────
    //  Index encryption (AES-256-GCM)
    // ──────────────────────────────────────

    /**
     * Encrypt the file index JSON using AES-256-GCM.
     * Returns the ciphertext (including the appended 16-byte GCM auth tag).
     */
    fun encryptIndex(key: SecretKey, iv: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val gcmSpec = GCMParameterSpec(CryptoConstants.GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, gcmSpec)
        return cipher.doFinal(plaintext)
    }

    /**
     * Decrypt the file index. Throws [javax.crypto.AEADBadTagException]
     * if the password (and thus key) is wrong — used for wrong-password detection.
     */
    fun decryptIndex(key: SecretKey, iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val gcmSpec = GCMParameterSpec(CryptoConstants.GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec)
        return cipher.doFinal(ciphertext)
    }

    // ──────────────────────────────────────
    //  Chunk encryption (AES-256-CTR)
    // ──────────────────────────────────────

    /**
     * Compute a deterministic IV for a specific chunk.
     *
     * Takes the 16-byte [baseIv] and XORs the last 8 bytes with [chunkIndex].
     * This ensures each chunk within a file gets a unique IV while allowing
     * random access — the IV for any chunk can be computed independently.
     */
    fun computeChunkIv(baseIv: ByteArray, chunkIndex: Long): ByteArray {
        val iv = baseIv.copyOf()
        val indexBytes = ByteBuffer.allocate(8).putLong(chunkIndex).array()
        // XOR the last 8 bytes of the IV with the chunk index
        for (i in 0 until 8) {
            iv[iv.size - 8 + i] = (iv[iv.size - 8 + i].toInt() xor indexBytes[i].toInt()).toByte()
        }
        return iv
    }

    /** Encrypt a single chunk of plaintext data using AES-256-CTR */
    fun encryptChunk(key: SecretKey, baseIv: ByteArray, chunkIndex: Long, plaintext: ByteArray): ByteArray {
        val iv = computeChunkIv(baseIv, chunkIndex)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(iv))
        return cipher.doFinal(plaintext)
    }

    /** Decrypt a single chunk of ciphertext data using AES-256-CTR */
    fun decryptChunk(key: SecretKey, baseIv: ByteArray, chunkIndex: Long, ciphertext: ByteArray): ByteArray {
        val iv = computeChunkIv(baseIv, chunkIndex)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(iv))
        return cipher.doFinal(ciphertext)
    }

    // ──────────────────────────────────────
    //  Integrity (HMAC-SHA256)
    // ──────────────────────────────────────

    /** Compute HMAC-SHA256 over [data] for integrity verification */
    fun computeHmac(key: SecretKey, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(key)
        return mac.doFinal(data)
    }

    // ──────────────────────────────────────
    //  Hex encoding utilities
    // ──────────────────────────────────────

    fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    fun hexToBytes(hex: String): ByteArray {
        val result = ByteArray(hex.length / 2)
        for (i in result.indices) {
            result[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return result
    }
}
