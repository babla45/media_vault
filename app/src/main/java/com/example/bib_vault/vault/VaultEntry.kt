package com.example.bib_vault.vault

import com.example.bib_vault.crypto.CryptoManager
import org.json.JSONObject

/**
 * Represents a single file entry stored inside a vault container.
 *
 * Each entry tracks the file's metadata and its encryption parameters,
 * enabling random access to the encrypted data without decrypting
 * the entire container.
 *
 * @property id           Unique identifier (UUID) for this entry
 * @property fileName     Original filename (e.g., "vacation.mp4")
 * @property mimeType     MIME type (e.g., "video/mp4")
 * @property originalSize Size of the original plaintext file in bytes
 * @property offset       Byte offset from the start of the data section
 * @property encryptedSize Total encrypted size (equals originalSize for CTR mode)
 * @property chunkSize    Plaintext chunk size used during encryption
 * @property chunkCount   Number of chunks the file was split into
 * @property baseIvHex    Hex-encoded 16-byte base IV for CTR chunk decryption
 * @property hmacHex      Hex-encoded HMAC-SHA256 of all ciphertext chunks
 * @property addedTimestamp Epoch millis when the file was added
 */
data class VaultEntry(
    val id: String,
    val fileName: String,
    val mimeType: String,
    val originalSize: Long,
    val offset: Long,
    val encryptedSize: Long,
    val chunkSize: Int,
    val chunkCount: Int,
    val baseIvHex: String,
    val hmacHex: String,
    val addedTimestamp: Long
) {
    /** Decoded base IV bytes for crypto operations */
    val baseIvBytes: ByteArray get() = CryptoManager.hexToBytes(baseIvHex)

    /** Whether this entry is a video file */
    val isVideo: Boolean get() = mimeType.startsWith("video/")

    /** Whether this entry is an audio file */
    val isAudio: Boolean get() = mimeType.startsWith("audio/")

    /** Whether this entry is an image file */
    val isImage: Boolean get() = mimeType.startsWith("image/")

    /** Documents, archives, and any type that is not video, audio, or image */
    val isOther: Boolean get() = !isVideo && !isAudio && !isImage

    /** Serialize this entry to a JSON object */
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("fileName", fileName)
        put("mimeType", mimeType)
        put("originalSize", originalSize)
        put("offset", offset)
        put("encryptedSize", encryptedSize)
        put("chunkSize", chunkSize)
        put("chunkCount", chunkCount)
        put("baseIvHex", baseIvHex)
        put("hmacHex", hmacHex)
        put("addedTimestamp", addedTimestamp)
    }

    companion object {
        /** Deserialize a VaultEntry from a JSON object */
        fun fromJson(json: JSONObject): VaultEntry = VaultEntry(
            id = json.getString("id"),
            fileName = json.getString("fileName"),
            mimeType = json.getString("mimeType"),
            originalSize = json.getLong("originalSize"),
            offset = json.getLong("offset"),
            encryptedSize = json.getLong("encryptedSize"),
            chunkSize = json.getInt("chunkSize"),
            chunkCount = json.getInt("chunkCount"),
            baseIvHex = json.getString("baseIvHex"),
            hmacHex = json.getString("hmacHex"),
            addedTimestamp = json.getLong("addedTimestamp")
        )
    }
}
