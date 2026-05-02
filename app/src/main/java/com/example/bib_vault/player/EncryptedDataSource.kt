package com.example.bib_vault.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.example.bib_vault.crypto.CryptoManager
import com.example.bib_vault.vault.VaultEntry
import com.example.bib_vault.vault.VaultHeader
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import javax.crypto.SecretKey

/**
 * Custom DataSource for Media3/ExoPlayer that reads and decrypts
 * media data on-the-fly from an encrypted vault container.
 *
 * Streaming decryption logic:
 * 1. ExoPlayer requests bytes at a specific position (via DataSpec.position)
 * 2. We calculate which chunk that position falls into
 * 3. Read the encrypted chunk from the vault file using FileChannel (random access)
 * 4. Decrypt it using AES-256-CTR with a deterministic IV (baseIv XOR chunkIndex)
 * 5. Return the requested bytes from the decrypted chunk
 *
 * Random access implementation:
 * - Each chunk has a deterministic IV computed from base_iv XOR chunk_index
 * - AES-CTR mode allows decrypting any chunk independently
 * - No need to decrypt preceding chunks — true random access
 * - A decrypted chunk cache avoids re-decryption for sequential reads
 *
 * IMPORTANT: Decrypted data is NEVER written to disk — only held in memory.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class EncryptedDataSource(
    private val context: Context,
    private val vaultUri: Uri,
    private val key: SecretKey,
    private val entries: Map<String, VaultEntry>,
    private val header: VaultHeader
) : DataSource {

    private var dataSpec: DataSpec? = null
    private var entry: VaultEntry? = null
    private var channel: FileChannel? = null
    private var pfd: android.os.ParcelFileDescriptor? = null
    private var inputStream: java.io.InputStream? = null

    /** Current read position within the plaintext file */
    private var position: Long = 0

    /** Bytes remaining to read */
    private var bytesRemaining: Long = 0

    // Chunk cache: avoid re-decrypting the same chunk for sequential reads
    private var cachedChunkIndex: Long = -1
    private var cachedChunkData: ByteArray? = null

    override fun addTransferListener(transferListener: TransferListener) {
        // No transfer listener support needed for local encrypted files
    }

    /**
     * Open a data source for reading from the encrypted vault.
     *
     * The URI scheme is: bibvault://entry/{entryId}
     * DataSpec.position specifies the byte offset in the plaintext to start reading.
     *
     * @return Total number of bytes available from the requested position,
     *         or C.LENGTH_UNSET if unknown
     */
    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec

        // Parse entry ID from URI path
        val entryId = dataSpec.uri.lastPathSegment
            ?: throw IllegalArgumentException("Missing entry ID in URI: ${dataSpec.uri}")

        entry = entries[entryId]
            ?: throw IllegalArgumentException("Entry not found: $entryId")

        val currentEntry = entry!!

        // Set starting position (supports seek)
        position = dataSpec.position
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else {
            currentEntry.originalSize - position
        }

        // Open the vault file for random access
        try {
            pfd = context.contentResolver.openFileDescriptor(vaultUri, "r")
            if (pfd != null) {
                channel = FileInputStream(pfd!!.fileDescriptor).channel
            } else {
                throw IllegalStateException("Failed to open FileDescriptor")
            }
        } catch (e: Exception) {
            val localStream = try {
                context.contentResolver.openInputStream(vaultUri)
            } catch (innerE: Exception) { null }

            if (localStream is FileInputStream) {
                channel = localStream.channel
                inputStream = localStream
            } else {
                localStream?.close()
                val path = com.example.bib_vault.vault.VaultManager.getFilePathFromUri(context, vaultUri)
                if (path != null) {
                    try {
                        val fileStream = java.io.FileInputStream(java.io.File(path))
                        channel = fileStream.channel
                        inputStream = fileStream
                    } catch (ioe: Exception) {
                        throw IllegalStateException("Cannot obtain random access channel via path for vault: $vaultUri", ioe)
                    }
                } else {
                    throw IllegalStateException("Cannot obtain random access channel for vault: $vaultUri", e)
                }
            }
        }

        // Clear chunk cache
        cachedChunkIndex = -1
        cachedChunkData = null

        return bytesRemaining
    }

    /**
     * Read decrypted data from the vault.
     *
     * This method handles the chunk boundary logic:
     * - Determines which chunk contains the current position
     * - Reads and decrypts that chunk (using cache if available)
     * - Copies the requested portion to the output buffer
     */
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (bytesRemaining <= 0) return C.RESULT_END_OF_INPUT

        val currentEntry = entry ?: return C.RESULT_END_OF_INPUT
        val currentChannel = channel ?: return C.RESULT_END_OF_INPUT
        val chunkSize = currentEntry.chunkSize.toLong()

        // Determine which chunk we're reading from
        val chunkIndex = position / chunkSize
        val offsetInChunk = (position % chunkSize).toInt()

        // Read and decrypt the chunk if not cached
        if (chunkIndex != cachedChunkIndex) {
            val absoluteChunkOffset = header.dataSectionOffset +
                    currentEntry.offset +
                    (chunkIndex * chunkSize)

            // Calculate how many bytes this chunk contains
            val isLastChunk = chunkIndex >= (currentEntry.chunkCount - 1).toLong()
            val chunkDataSize = if (isLastChunk) {
                (currentEntry.originalSize - chunkIndex * chunkSize).toInt()
            } else {
                chunkSize.toInt()
            }

            // Read encrypted chunk data from vault file
            val encryptedBuffer = ByteBuffer.allocate(chunkDataSize)
            currentChannel.position(absoluteChunkOffset)
            while (encryptedBuffer.hasRemaining()) {
                if (currentChannel.read(encryptedBuffer) == -1) break
            }
            encryptedBuffer.flip()
            val encryptedData = ByteArray(encryptedBuffer.remaining())
            encryptedBuffer.get(encryptedData)

            // Decrypt the chunk using AES-CTR with deterministic IV
            cachedChunkData = CryptoManager.decryptChunk(
                key,
                currentEntry.baseIvBytes,
                chunkIndex,
                encryptedData
            )
            cachedChunkIndex = chunkIndex
        }

        // Calculate how many bytes to copy from the decrypted chunk
        val decryptedChunk = cachedChunkData!!
        val availableInChunk = decryptedChunk.size - offsetInChunk
        val bytesToRead = minOf(length, availableInChunk, bytesRemaining.toInt())

        if (bytesToRead <= 0) return C.RESULT_END_OF_INPUT

        // Copy decrypted data to ExoPlayer's buffer
        System.arraycopy(decryptedChunk, offsetInChunk, buffer, offset, bytesToRead)

        position += bytesToRead
        bytesRemaining -= bytesToRead

        return bytesToRead
    }

    override fun getUri(): Uri? = dataSpec?.uri

    override fun close() {
        try {
            channel?.close()
        } catch (_: Exception) {}
        try {
            pfd?.close()
        } catch (_: Exception) {}
        try {
            inputStream?.close()
        } catch (_: Exception) {}
        channel = null
        pfd = null
        inputStream = null
        entry = null
        dataSpec = null
        cachedChunkData = null
        cachedChunkIndex = -1
        position = 0
        bytesRemaining = 0
    }
}
