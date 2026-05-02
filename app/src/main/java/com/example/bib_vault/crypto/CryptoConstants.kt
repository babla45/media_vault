package com.example.bib_vault.crypto

/**
 * Constants used throughout the vault encryption system.
 *
 * Container format layout:
 * ┌──────────────────────────────────────────────┐
 * │ MAGIC (8B) | VERSION (4B) | SALT (32B)       │
 * │ INDEX_IV (12B) | INDEX_SIZE (4B)              │
 * ├──────────────────────────────────────────────┤
 * │ ENCRYPTED INDEX (INDEX_SIZE bytes, AES-GCM)   │
 * ├──────────────────────────────────────────────┤
 * │ FILE DATA BLOCKS (AES-CTR, chunked)           │
 * └──────────────────────────────────────────────┘
 */
object CryptoConstants {

    /** Magic bytes identifying a BibVault container file */
    val MAGIC_BYTES = "BIBVAULT".toByteArray(Charsets.US_ASCII)

    /** Supported container format versions */
    const val FORMAT_VERSION_V1 = 1
    const val FORMAT_VERSION_V2 = 2

    /** Current container format version for newly created vaults */
    const val FORMAT_VERSION_CURRENT = FORMAT_VERSION_V2

    /** Size of PBKDF2 salt in bytes */
    const val SALT_SIZE = 32

    /** Size of GCM nonce/IV for index encryption */
    const val GCM_IV_SIZE = 12

    /** Size of CTR IV for chunk encryption */
    const val CTR_IV_SIZE = 16

    /** AES key size in bits */
    const val KEY_SIZE_BITS = 256

    /** PBKDF2 iteration count — high enough for security, tolerable on mobile */
    const val PBKDF2_ITERATIONS = 100_000

    /** Plaintext chunk size for file encryption (64 KB) */
    const val CHUNK_SIZE = 65_536

    /**
     * Fixed header size before the encrypted index:
     * MAGIC(8) + VERSION(4) + SALT(32) + INDEX_IV(12) + INDEX_SIZE(4) = 60
     */
    const val HEADER_FIXED_SIZE = 60

    /** GCM authentication tag length in bits */
    const val GCM_TAG_LENGTH_BITS = 128
}
