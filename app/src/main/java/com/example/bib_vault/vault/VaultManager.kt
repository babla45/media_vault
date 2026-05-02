package com.example.bib_vault.vault

import android.content.Context
import android.net.Uri
import com.example.bib_vault.crypto.CryptoConstants
import com.example.bib_vault.crypto.CryptoManager
import com.example.bib_vault.util.MimeUtils
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.UUID
import javax.crypto.SecretKey

/**
 * High-level manager for creating, reading, and modifying vault containers.
 *
 * Vault creation flow:
 * 1. Generate salt, derive AES key from password
 * 2. For each input file: encrypt in chunks (AES-CTR), compute HMAC
 * 3. Build JSON index of all entries
 * 4. Encrypt index with AES-GCM
 * 5. Write header + encrypted index + file data blocks
 *
 * Vault open flow:
 * 1. Read fixed header (60 bytes)
 * 2. Derive key from password + salt
 * 3. Decrypt index with AES-GCM (wrong password → AEADBadTagException)
 * 4. Parse JSON → List<VaultEntry>
 */
object VaultManager {

    /**
     * Attempts to resolve a content URI to an absolute file path.
     * This relies on the MANAGE_EXTERNAL_STORAGE permission to bypass restrictive providers.
     */
    fun getFilePathFromUri(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.path
        if (uri.scheme == "content") {
            // Check if it's a SAF external storage URI
            if (uri.authority == "com.android.externalstorage.documents") {
                try {
                    val docId = android.provider.DocumentsContract.getDocumentId(uri)
                    val split = docId.split(":")
                    val type = split[0]
                    val path = if (split.size > 1) split[1] else ""
                    
                    if ("primary".equals(type, ignoreCase = true)) {
                        return android.os.Environment.getExternalStorageDirectory().toString() + "/" + path
                    } else {
                        return "/storage/$type/$path"
                    }
                } catch (e: Exception) {
                    // Ignore and fallback
                }
            }

            // Fallback for MediaStore URIs
            try {
                val projection = arrayOf(android.provider.MediaStore.MediaColumns.DATA)
                context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val columnIndex = cursor.getColumnIndex(android.provider.MediaStore.MediaColumns.DATA)
                        if (columnIndex != -1) {
                            return cursor.getString(columnIndex)
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        return null
    }

    /**
     * Create a new vault container from a list of source file URIs.
     *
     * @param context  Android context for content resolver access
     * @param vaultUri Output URI for the .vault file (from SAF)
     * @param password User password for encryption
     * @param fileUris List of content URIs to encrypt into the vault
     * @param onProgress Callback with (currentFile, totalFiles) for progress UI
     */
    fun createVault(
        context: Context,
        vaultUri: Uri,
        password: String,
        fileUris: List<Uri>,
        onProgress: ((Int, Int) -> Unit)? = null
    ) {
        val salt = CryptoManager.generateSalt()
        val key = CryptoManager.deriveKey(password, salt)

        // Phase 1: Encrypt all files into memory-buffered chunks
        // and build the entry index simultaneously
        val entries = mutableListOf<VaultEntry>()
        val allEncryptedData = ByteArrayOutputStream()
        var currentOffset = 0L

        for ((index, fileUri) in fileUris.withIndex()) {
            onProgress?.invoke(index + 1, fileUris.size)

            val fileName = getFileName(context, fileUri)
            val mimeType = context.contentResolver.getType(fileUri) ?: MimeUtils.guessMimeType(fileName)
            val baseIv = CryptoManager.generateCtrBaseIv()

            // Read and encrypt the file chunk by chunk
            var inputStream: java.io.InputStream? = null
            try {
                inputStream = context.contentResolver.openInputStream(fileUri)
            } catch (e: Exception) {
                val path = getFilePathFromUri(context, fileUri)
                if (path != null) {
                    inputStream = java.io.FileInputStream(java.io.File(path))
                } else {
                    throw IllegalStateException("Cannot open file via ContentResolver and could not resolve path: $fileUri", e)
                }
            }
            if (inputStream == null) {
                val path = getFilePathFromUri(context, fileUri)
                if (path != null) inputStream = java.io.FileInputStream(java.io.File(path))
                else throw IllegalStateException("Cannot open file: $fileUri")
            }

            var originalSize = 0L
            var chunkIndex = 0L
            val hmacAccumulator = ByteArrayOutputStream()

            inputStream.use { stream ->
                val buffer = ByteArray(CryptoConstants.CHUNK_SIZE)
                while (true) {
                    val bytesRead = stream.read(buffer)
                    if (bytesRead <= 0) break

                    val plaintext = if (bytesRead < buffer.size) {
                        buffer.copyOf(bytesRead)
                    } else {
                        buffer
                    }

                    val encrypted = CryptoManager.encryptChunk(key, baseIv, chunkIndex, plaintext)
                    allEncryptedData.write(encrypted)
                    hmacAccumulator.write(encrypted)

                    originalSize += bytesRead
                    chunkIndex++
                }
            }

            val encryptedSize = originalSize // CTR mode: ciphertext size == plaintext size
            val hmac = CryptoManager.computeHmac(key, hmacAccumulator.toByteArray())

            entries.add(
                VaultEntry(
                    id = UUID.randomUUID().toString(),
                    fileName = fileName,
                    mimeType = mimeType,
                    originalSize = originalSize,
                    offset = currentOffset,
                    encryptedSize = encryptedSize,
                    chunkSize = CryptoConstants.CHUNK_SIZE,
                    chunkCount = chunkIndex.toInt(),
                    baseIvHex = CryptoManager.bytesToHex(baseIv),
                    hmacHex = CryptoManager.bytesToHex(hmac),
                    addedTimestamp = System.currentTimeMillis()
                )
            )

            currentOffset += encryptedSize
        }

        // Phase 2: Encrypt the index
        val indexJson = entriesToJson(entries)
        val indexBytes = indexJson.toByteArray(Charsets.UTF_8)
        val indexIv = CryptoManager.generateGcmIv()
        val encryptedIndex = CryptoManager.encryptIndex(key, indexIv, indexBytes)

        // Phase 3: Write the vault file
        var outputStream: java.io.OutputStream? = null
        try {
            outputStream = context.contentResolver.openOutputStream(vaultUri)
        } catch (e: Exception) {
            val path = getFilePathFromUri(context, vaultUri)
            if (path != null) {
                outputStream = java.io.FileOutputStream(java.io.File(path))
            } else {
                throw IllegalStateException("Cannot write vault via ContentResolver and could not resolve path: $vaultUri", e)
            }
        }
        if (outputStream == null) {
            val path = getFilePathFromUri(context, vaultUri)
            if (path != null) outputStream = java.io.FileOutputStream(java.io.File(path))
            else throw IllegalStateException("Cannot write to vault: $vaultUri")
        }

        outputStream.use { stream ->
            DataOutputStream(stream).use { dos ->
                // Header
                dos.write(CryptoConstants.MAGIC_BYTES)                         // 8 bytes
                dos.writeInt(CryptoConstants.FORMAT_VERSION)                    // 4 bytes
                dos.write(salt)                                                 // 32 bytes
                dos.write(indexIv)                                              // 12 bytes
                dos.writeInt(encryptedIndex.size)                               // 4 bytes
                // = 60 bytes total header

                // Encrypted index
                dos.write(encryptedIndex)

                // File data blocks
                dos.write(allEncryptedData.toByteArray())

                dos.flush()
            }
        }
    }

    /**
     * Open and decrypt a vault's file index.
     *
     * @return Pair of (VaultHeader, List<VaultEntry>) on success
     * @throws javax.crypto.AEADBadTagException if password is wrong
     * @throws IllegalStateException if the file is not a valid vault
     */
    fun openVault(
        context: Context,
        vaultUri: Uri,
        password: String
    ): Pair<VaultHeader, List<VaultEntry>> {
        var inputStream: java.io.InputStream? = null
        try {
            inputStream = context.contentResolver.openInputStream(vaultUri)
        } catch (e: Exception) {
            val path = getFilePathFromUri(context, vaultUri)
            if (path != null) {
                inputStream = java.io.FileInputStream(java.io.File(path))
            } else {
                throw IllegalStateException("Cannot open vault via ContentResolver and could not resolve path: $vaultUri", e)
            }
        }
        if (inputStream == null) {
            val path = getFilePathFromUri(context, vaultUri)
            if (path != null) inputStream = java.io.FileInputStream(java.io.File(path))
            else throw IllegalStateException("Cannot open vault: $vaultUri")
        }

        inputStream.use {
            DataInputStream(it).use { dis ->
                // Read header
                val header = readHeader(dis)

                if (!header.isValid()) {
                    throw IllegalStateException("Invalid vault file: bad magic bytes or version")
                }

                // Derive key
                val key = CryptoManager.deriveKey(password, header.salt)

                // Read and decrypt index (AEADBadTagException if wrong password)
                val encryptedIndex = ByteArray(header.indexSize)
                dis.readFully(encryptedIndex)
                val indexBytes = CryptoManager.decryptIndex(key, header.indexIv, encryptedIndex)

                // Parse index
                val indexJson = String(indexBytes, Charsets.UTF_8)
                val entries = entriesFromJson(indexJson)

                return Pair(header, entries)
            }
        }
    }

    /**
     * Read and decrypt a single chunk from a vault file.
     *
     * @param context Application context
     * @param vaultUri URI of the vault file
     * @param header Vault header (provides dataSectionOffset)
     * @param entry The file entry to read from
     * @param chunkIndex Zero-based chunk index within the file
     * @param key Derived encryption key
     * @return Decrypted chunk data
     */
    fun readChunk(
        context: Context,
        vaultUri: Uri,
        header: VaultHeader,
        entry: VaultEntry,
        chunkIndex: Long,
        key: SecretKey
    ): ByteArray {
        var pfd: android.os.ParcelFileDescriptor? = null
        var inputStream: java.io.InputStream? = null
        var channel: java.nio.channels.FileChannel

        try {
            pfd = context.contentResolver.openFileDescriptor(vaultUri, "r")
            if (pfd != null) {
                channel = FileInputStream(pfd.fileDescriptor).channel
            } else {
                throw IllegalStateException("Failed to open FileDescriptor")
            }
        } catch (e: Exception) {
            try {
                inputStream = context.contentResolver.openInputStream(vaultUri)
                if (inputStream is FileInputStream) {
                    channel = inputStream.channel
                } else {
                    inputStream?.close()
                    throw IllegalStateException("Not FileInputStream")
                }
            } catch (e2: Exception) {
                val path = getFilePathFromUri(context, vaultUri)
                if (path != null) {
                    inputStream = java.io.FileInputStream(java.io.File(path))
                    channel = (inputStream as java.io.FileInputStream).channel
                } else {
                    throw IllegalStateException("Cannot obtain random access channel for vault: $vaultUri", e)
                }
            }
        }

        try {
            // Calculate the absolute byte offset of this chunk in the vault file
            val chunkOffset = header.dataSectionOffset + entry.offset +
                    (chunkIndex * entry.chunkSize)

            // Calculate chunk size (last chunk may be smaller)
            val isLastChunk = chunkIndex == (entry.chunkCount - 1).toLong()
            val chunkDataSize = if (isLastChunk) {
                (entry.originalSize - chunkIndex * entry.chunkSize).toInt()
            } else {
                entry.chunkSize
            }

            // Read encrypted data
            val buffer = ByteBuffer.allocate(chunkDataSize)
            channel.position(chunkOffset)
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) == -1) break
            }
            buffer.flip()
            val encrypted = ByteArray(buffer.remaining())
            buffer.get(encrypted)

            // Decrypt
            return CryptoManager.decryptChunk(key, entry.baseIvBytes, chunkIndex, encrypted)
        } finally {
            try { channel.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
            try { inputStream?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Read and decrypt an entire file from the vault (used for images).
     * The entire decrypted content is returned in memory — never written to disk.
     */
    fun readFileBytes(
        context: Context,
        vaultUri: Uri,
        header: VaultHeader,
        entry: VaultEntry,
        key: SecretKey
    ): ByteArray {
        val output = ByteArrayOutputStream(entry.originalSize.toInt())

        for (i in 0 until entry.chunkCount) {
            val chunk = readChunk(context, vaultUri, header, entry, i.toLong(), key)
            output.write(chunk)
        }

        return output.toByteArray()
    }

    /**
     * Add new files to an existing vault.
     * This re-creates the vault with existing + new files.
     */
    fun addFiles(
        context: Context,
        vaultUri: Uri,
        password: String,
        newFileUris: List<Uri>,
        onProgress: ((Int, Int) -> Unit)? = null
    ) {
        // Open existing vault
        val (header, existingEntries) = openVault(context, vaultUri, password)
        val key = CryptoManager.deriveKey(password, header.salt)

        // Read all existing file data
        val existingData = mutableListOf<ByteArray>()
        for (entry in existingEntries) {
            existingData.add(readFileBytes(context, vaultUri, header, entry, key))
        }

        // Create temporary vault with all files
        val salt = CryptoManager.generateSalt()
        val newKey = CryptoManager.deriveKey(password, salt)

        val entries = mutableListOf<VaultEntry>()
        val allEncryptedData = ByteArrayOutputStream()
        var currentOffset = 0L
        val totalFiles = existingEntries.size + newFileUris.size
        var fileCounter = 0

        // Re-encrypt existing files
        for ((idx, existingEntry) in existingEntries.withIndex()) {
            fileCounter++
            onProgress?.invoke(fileCounter, totalFiles)

            val baseIv = CryptoManager.generateCtrBaseIv()
            val plainData = existingData[idx]
            val hmacAccumulator = ByteArrayOutputStream()
            var chunkIndex = 0L
            var bytesProcessed = 0

            while (bytesProcessed < plainData.size) {
                val end = minOf(bytesProcessed + CryptoConstants.CHUNK_SIZE, plainData.size)
                val chunk = plainData.copyOfRange(bytesProcessed, end)
                val encrypted = CryptoManager.encryptChunk(newKey, baseIv, chunkIndex, chunk)
                allEncryptedData.write(encrypted)
                hmacAccumulator.write(encrypted)
                bytesProcessed = end
                chunkIndex++
            }

            val hmac = CryptoManager.computeHmac(newKey, hmacAccumulator.toByteArray())
            entries.add(
                existingEntry.copy(
                    offset = currentOffset,
                    baseIvHex = CryptoManager.bytesToHex(baseIv),
                    hmacHex = CryptoManager.bytesToHex(hmac),
                    chunkCount = chunkIndex.toInt()
                )
            )
            currentOffset += existingEntry.encryptedSize
        }

        // Encrypt new files
        for (fileUri in newFileUris) {
            fileCounter++
            onProgress?.invoke(fileCounter, totalFiles)

            val fileName = getFileName(context, fileUri)
            val mimeType = context.contentResolver.getType(fileUri) ?: MimeUtils.guessMimeType(fileName)
            val baseIv = CryptoManager.generateCtrBaseIv()

            var inputStream: java.io.InputStream? = null
            try {
                inputStream = context.contentResolver.openInputStream(fileUri)
            } catch (e: Exception) {
                val path = getFilePathFromUri(context, fileUri)
                if (path != null) {
                    inputStream = java.io.FileInputStream(java.io.File(path))
                } else {
                    throw IllegalStateException("Cannot open file via ContentResolver and could not resolve path: $fileUri", e)
                }
            }
            if (inputStream == null) {
                val path = getFilePathFromUri(context, fileUri)
                if (path != null) inputStream = java.io.FileInputStream(java.io.File(path))
                else throw IllegalStateException("Cannot open file: $fileUri")
            }

            var originalSize = 0L
            var chunkIndex = 0L
            val hmacAccumulator = ByteArrayOutputStream()

            inputStream.use { stream ->
                val buffer = ByteArray(CryptoConstants.CHUNK_SIZE)
                while (true) {
                    val bytesRead = stream.read(buffer)
                    if (bytesRead <= 0) break
                    val plaintext = if (bytesRead < buffer.size) buffer.copyOf(bytesRead) else buffer
                    val encrypted = CryptoManager.encryptChunk(newKey, baseIv, chunkIndex, plaintext)
                    allEncryptedData.write(encrypted)
                    hmacAccumulator.write(encrypted)
                    originalSize += bytesRead
                    chunkIndex++
                }
            }

            val hmac = CryptoManager.computeHmac(newKey, hmacAccumulator.toByteArray())
            entries.add(
                VaultEntry(
                    id = UUID.randomUUID().toString(),
                    fileName = fileName,
                    mimeType = mimeType,
                    originalSize = originalSize,
                    offset = currentOffset,
                    encryptedSize = originalSize,
                    chunkSize = CryptoConstants.CHUNK_SIZE,
                    chunkCount = chunkIndex.toInt(),
                    baseIvHex = CryptoManager.bytesToHex(baseIv),
                    hmacHex = CryptoManager.bytesToHex(hmac),
                    addedTimestamp = System.currentTimeMillis()
                )
            )
            currentOffset += originalSize
        }

        // Write updated vault
        val indexJson = entriesToJson(entries)
        val indexBytes = indexJson.toByteArray(Charsets.UTF_8)
        val indexIv = CryptoManager.generateGcmIv()
        val encryptedIndex = CryptoManager.encryptIndex(newKey, indexIv, indexBytes)

        var outputStream: java.io.OutputStream? = null
        try {
            outputStream = context.contentResolver.openOutputStream(vaultUri, "wt")
        } catch (e: Exception) {
            val path = getFilePathFromUri(context, vaultUri)
            if (path != null) {
                outputStream = java.io.FileOutputStream(java.io.File(path))
            } else {
                throw IllegalStateException("Cannot write vault via ContentResolver and could not resolve path: $vaultUri", e)
            }
        }
        if (outputStream == null) {
            val path = getFilePathFromUri(context, vaultUri)
            if (path != null) outputStream = java.io.FileOutputStream(java.io.File(path))
            else throw IllegalStateException("Cannot write to vault: $vaultUri")
        }

        outputStream.use { stream ->
            DataOutputStream(stream).use { dos ->
                dos.write(CryptoConstants.MAGIC_BYTES)
                dos.writeInt(CryptoConstants.FORMAT_VERSION)
                dos.write(salt)
                dos.write(indexIv)
                dos.writeInt(encryptedIndex.size)
                dos.write(encryptedIndex)
                dos.write(allEncryptedData.toByteArray())
                dos.flush()
            }
        }
    }

    /**
     * Remove a file from an existing vault by its entry ID.
     */
    fun removeFile(
        context: Context,
        vaultUri: Uri,
        password: String,
        entryId: String,
        onProgress: ((Int, Int) -> Unit)? = null
    ) {
        val (header, existingEntries) = openVault(context, vaultUri, password)
        val key = CryptoManager.deriveKey(password, header.salt)

        // Read all files except the one being removed
        val keepEntries = existingEntries.filter { it.id != entryId }
        val keepData = keepEntries.map { entry ->
            readFileBytes(context, vaultUri, header, entry, key)
        }

        // Re-create vault without the removed file
        val salt = CryptoManager.generateSalt()
        val newKey = CryptoManager.deriveKey(password, salt)

        val newEntries = mutableListOf<VaultEntry>()
        val allEncryptedData = ByteArrayOutputStream()
        var currentOffset = 0L

        for ((idx, entry) in keepEntries.withIndex()) {
            onProgress?.invoke(idx + 1, keepEntries.size)

            val baseIv = CryptoManager.generateCtrBaseIv()
            val plainData = keepData[idx]
            val hmacAccumulator = ByteArrayOutputStream()
            var chunkIndex = 0L
            var bytesProcessed = 0

            while (bytesProcessed < plainData.size) {
                val end = minOf(bytesProcessed + CryptoConstants.CHUNK_SIZE, plainData.size)
                val chunk = plainData.copyOfRange(bytesProcessed, end)
                val encrypted = CryptoManager.encryptChunk(newKey, baseIv, chunkIndex, chunk)
                allEncryptedData.write(encrypted)
                hmacAccumulator.write(encrypted)
                bytesProcessed = end
                chunkIndex++
            }

            val hmac = CryptoManager.computeHmac(newKey, hmacAccumulator.toByteArray())
            newEntries.add(
                entry.copy(
                    offset = currentOffset,
                    baseIvHex = CryptoManager.bytesToHex(baseIv),
                    hmacHex = CryptoManager.bytesToHex(hmac),
                    chunkCount = chunkIndex.toInt()
                )
            )
            currentOffset += entry.encryptedSize
        }

        val indexJson = entriesToJson(newEntries)
        val indexBytes = indexJson.toByteArray(Charsets.UTF_8)
        val indexIv = CryptoManager.generateGcmIv()
        val encryptedIndex = CryptoManager.encryptIndex(newKey, indexIv, indexBytes)

        var outputStream: java.io.OutputStream? = null
        try {
            outputStream = context.contentResolver.openOutputStream(vaultUri, "wt")
        } catch (e: Exception) {
            val path = getFilePathFromUri(context, vaultUri)
            if (path != null) {
                outputStream = java.io.FileOutputStream(java.io.File(path))
            } else {
                throw IllegalStateException("Cannot write vault via ContentResolver and could not resolve path: $vaultUri", e)
            }
        }
        if (outputStream == null) {
            val path = getFilePathFromUri(context, vaultUri)
            if (path != null) outputStream = java.io.FileOutputStream(java.io.File(path))
            else throw IllegalStateException("Cannot write to vault: $vaultUri")
        }

        outputStream.use { stream ->
            DataOutputStream(stream).use { dos ->
                dos.write(CryptoConstants.MAGIC_BYTES)
                dos.writeInt(CryptoConstants.FORMAT_VERSION)
                dos.write(salt)
                dos.write(indexIv)
                dos.writeInt(encryptedIndex.size)
                dos.write(encryptedIndex)
                dos.write(allEncryptedData.toByteArray())
                dos.flush()
            }
        }
    }

    // ──────────────────────────────────────
    //  Private helpers
    // ──────────────────────────────────────

    /** Read the fixed header from a vault file input stream */
    private fun readHeader(dis: DataInputStream): VaultHeader {
        val magic = ByteArray(8)
        dis.readFully(magic)

        val version = dis.readInt()

        val salt = ByteArray(CryptoConstants.SALT_SIZE)
        dis.readFully(salt)

        val indexIv = ByteArray(CryptoConstants.GCM_IV_SIZE)
        dis.readFully(indexIv)

        val indexSize = dis.readInt()

        return VaultHeader(magic, version, salt, indexIv, indexSize)
    }

    /** Serialize a list of entries to JSON string */
    private fun entriesToJson(entries: List<VaultEntry>): String {
        val jsonArray = JSONArray()
        for (entry in entries) {
            jsonArray.put(entry.toJson())
        }
        return jsonArray.toString()
    }

    /** Deserialize entries from a JSON string */
    private fun entriesFromJson(json: String): List<VaultEntry> {
        val jsonArray = JSONArray(json)
        val entries = mutableListOf<VaultEntry>()
        for (i in 0 until jsonArray.length()) {
            entries.add(VaultEntry.fromJson(jsonArray.getJSONObject(i)))
        }
        return entries
    }

    /** Extract the display filename from a content URI */
    private fun getFileName(context: Context, uri: Uri): String {
        // Try to get from ContentResolver
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    return cursor.getString(nameIndex)
                }
            }
        }
        // Fallback to URI path
        return uri.lastPathSegment ?: "unknown_file"
    }
}
