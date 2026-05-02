package com.example.bib_vault.vault

import com.example.bib_vault.crypto.CryptoConstants

/**
 * Represents the fixed-size header at the beginning of a .vault file.
 *
 * Layout (60 bytes total):
 * ┌─────────────────────────────────┐
 * │ MAGIC     (8 bytes) "BIBVAULT"  │
 * │ VERSION   (4 bytes) uint32      │
 * │ SALT      (32 bytes)            │
 * │ INDEX_IV  (12 bytes) GCM nonce  │
 * │ INDEX_SIZE (4 bytes) uint32     │
 * └─────────────────────────────────┘
 *
 * @property magic     Magic bytes identifying the file format
 * @property version   Format version number
 * @property salt      PBKDF2 salt for key derivation
 * @property indexIv   GCM nonce used to encrypt the file index
 * @property indexSize Size of the encrypted index (including GCM tag)
 */
data class VaultHeader(
    val magic: ByteArray,
    val version: Int,
    val salt: ByteArray,
    val indexIv: ByteArray,
    val indexSize: Int
) {
    /** Byte offset where file data blocks begin */
    val dataSectionOffset: Long
        get() = CryptoConstants.HEADER_FIXED_SIZE.toLong() + indexSize.toLong()

    /** Validate that this header belongs to a valid vault file */
    fun isValid(): Boolean =
        magic.contentEquals(CryptoConstants.MAGIC_BYTES) &&
                (version == CryptoConstants.FORMAT_VERSION_V1 || version == CryptoConstants.FORMAT_VERSION_V2)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VaultHeader) return false
        return magic.contentEquals(other.magic) &&
                version == other.version &&
                salt.contentEquals(other.salt) &&
                indexIv.contentEquals(other.indexIv) &&
                indexSize == other.indexSize
    }

    override fun hashCode(): Int {
        var result = magic.contentHashCode()
        result = 31 * result + version
        result = 31 * result + salt.contentHashCode()
        result = 31 * result + indexIv.contentHashCode()
        result = 31 * result + indexSize
        return result
    }
}
