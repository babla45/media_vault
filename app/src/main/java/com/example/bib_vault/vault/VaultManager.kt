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
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Mac
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
     * v2 vaults reserve a fixed encrypted index region so the data section offset
     * never changes during add/delete operations (fast updates).
     *
     * This value is the CIPHERTEXT size stored in the header (includes GCM tag).
     */
    private const val INDEX_RESERVED_CIPHERTEXT_SIZE: Int = 256 * 1024 // 256 KiB
    private const val INDEX_V2_PREFIX_USED_LENGTH_BYTES: Int = 4
    private val secureRandom = SecureRandom()

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
        val indexIv = CryptoManager.generateGcmIv()
        val encryptedIndex = if (CryptoConstants.FORMAT_VERSION_CURRENT == CryptoConstants.FORMAT_VERSION_V2) {
            encryptIndexV2(key, indexIv, indexJson)
        } else {
            CryptoManager.encryptIndex(key, indexIv, indexJson.toByteArray(Charsets.UTF_8))
        }

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
                dos.writeInt(CryptoConstants.FORMAT_VERSION_CURRENT)            // 4 bytes
                dos.write(salt)                                                 // 32 bytes
                dos.write(indexIv)                                              // 12 bytes
                dos.writeInt(encryptedIndex.size)                               // 4 bytes (v1: exact, v2: reserved)
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
                val indexJson = if (header.version == CryptoConstants.FORMAT_VERSION_V2) {
                    decryptIndexV2ToJson(key, header.indexIv, encryptedIndex)
                } else {
                    val indexBytes = CryptoManager.decryptIndex(key, header.indexIv, encryptedIndex)
                    String(indexBytes, Charsets.UTF_8)
                }
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
        val (header, existingEntries) = openVault(context, vaultUri, password)
        if (header.version != CryptoConstants.FORMAT_VERSION_V2) {
            // v1 fallback: rewrite the entire vault (slow but compatible)
            addFilesV1Rewrite(context, vaultUri, password, existingEntries, newFileUris, onProgress)
            return
        }

        val key = CryptoManager.deriveKey(password, header.salt)
        val totalFiles = existingEntries.size + newFileUris.size
        var fileCounter = existingEntries.size

        withFileChannelRw(context, vaultUri) { ch ->
            val initialSize = ch.size()
            var currentOffset = (initialSize - header.dataSectionOffset).coerceAtLeast(0L)
            ch.position(initialSize) // append to end

            val updatedEntries = existingEntries.toMutableList()

            for (fileUri in newFileUris) {
                fileCounter++
                onProgress?.invoke(fileCounter, totalFiles)

                val fileName = getFileName(context, fileUri)
                val mimeType = context.contentResolver.getType(fileUri) ?: MimeUtils.guessMimeType(fileName)
                val baseIv = CryptoManager.generateCtrBaseIv()

                val mac = Mac.getInstance("HmacSHA256").apply { init(key) }
                var originalSize = 0L
                var chunkIndex = 0L

                openInputStreamCompat(context, fileUri).use { stream ->
                    val buffer = ByteArray(CryptoConstants.CHUNK_SIZE)
                    while (true) {
                        val bytesRead = stream.read(buffer)
                        if (bytesRead <= 0) break
                        val plaintext = if (bytesRead < buffer.size) buffer.copyOf(bytesRead) else buffer
                        val encrypted = CryptoManager.encryptChunk(key, baseIv, chunkIndex, plaintext)
                        ch.write(java.nio.ByteBuffer.wrap(encrypted))
                        mac.update(encrypted)
                        originalSize += bytesRead
                        chunkIndex++
                    }
                }

                val hmac = mac.doFinal()
                updatedEntries.add(
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

            // Update index in-place (fast)
            writeIndexV2InPlace(channel = ch, header = header, key = key, entries = updatedEntries)
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
        if (header.version != CryptoConstants.FORMAT_VERSION_V2) {
            // v1 fallback: rewrite the entire vault (slow but compatible)
            removeFileV1Rewrite(context, vaultUri, password, entryId, onProgress)
            return
        }

        val keepEntries = existingEntries.filter { it.id != entryId }
        val key = CryptoManager.deriveKey(password, header.salt)

        onProgress?.invoke(keepEntries.size, keepEntries.size)
        withFileChannelRw(context, vaultUri) { ch ->
            writeIndexV2InPlace(channel = ch, header = header, key = key, entries = keepEntries)
        }
    }

    // ──────────────────────────────────────
    //  v2 index (fast in-place updates)
    // ──────────────────────────────────────

    private fun encryptIndexV2(key: SecretKey, iv: ByteArray, indexJson: String): ByteArray {
        val jsonBytes = indexJson.toByteArray(Charsets.UTF_8)
        val reservedPlainSize = INDEX_RESERVED_CIPHERTEXT_SIZE - (CryptoConstants.GCM_TAG_LENGTH_BITS / 8)
        val maxJsonBytes = reservedPlainSize - INDEX_V2_PREFIX_USED_LENGTH_BYTES
        require(jsonBytes.size <= maxJsonBytes) {
            "Vault index too large for reserved index region (${jsonBytes.size} > $maxJsonBytes). Create a new vault."
        }

        val plain = ByteArray(reservedPlainSize)
        val bb = ByteBuffer.wrap(plain)
        bb.putInt(jsonBytes.size)
        bb.put(jsonBytes)
        // Fill remaining bytes with random padding (ignored on read)
        val padStart = INDEX_V2_PREFIX_USED_LENGTH_BYTES + jsonBytes.size
        if (padStart < plain.size) {
            val pad = ByteArray(plain.size - padStart)
            secureRandom.nextBytes(pad)
            System.arraycopy(pad, 0, plain, padStart, pad.size)
        }
        return CryptoManager.encryptIndex(key, iv, plain) // ciphertext size == INDEX_RESERVED_CIPHERTEXT_SIZE
    }

    private fun decryptIndexV2ToJson(key: SecretKey, iv: ByteArray, reservedCiphertext: ByteArray): String {
        val plain = CryptoManager.decryptIndex(key, iv, reservedCiphertext)
        val bb = ByteBuffer.wrap(plain)
        val used = bb.int
        if (used < 0 || used > plain.size - INDEX_V2_PREFIX_USED_LENGTH_BYTES) {
            throw IllegalStateException("Invalid v2 index payload length: $used")
        }
        val jsonBytes = ByteArray(used)
        bb.get(jsonBytes)
        return String(jsonBytes, Charsets.UTF_8)
    }

    private fun writeIndexV2InPlace(
        channel: java.nio.channels.FileChannel,
        header: VaultHeader,
        key: SecretKey,
        entries: List<VaultEntry>
    ) {
        // Write new header (with new IV) + reserved encrypted index region
        val indexJson = entriesToJson(entries)
        val newIndexIv = CryptoManager.generateGcmIv()
        val encryptedIndex = encryptIndexV2(key, newIndexIv, indexJson)

        // Header bytes (60)
        val headerBuf = ByteBuffer.allocate(CryptoConstants.HEADER_FIXED_SIZE)
        headerBuf.put(CryptoConstants.MAGIC_BYTES)
        headerBuf.putInt(CryptoConstants.FORMAT_VERSION_V2)
        headerBuf.put(header.salt) // keep salt stable for same password
        headerBuf.put(newIndexIv)
        headerBuf.putInt(INDEX_RESERVED_CIPHERTEXT_SIZE)
        headerBuf.flip()

        channel.position(0)
        while (headerBuf.hasRemaining()) channel.write(headerBuf)

        val idxBuf = ByteBuffer.wrap(encryptedIndex)
        while (idxBuf.hasRemaining()) channel.write(idxBuf)

        channel.force(true)
    }

    private fun openInputStreamCompat(context: Context, uri: Uri): java.io.InputStream {
        return try {
            context.contentResolver.openInputStream(uri)
        } catch (_: Exception) {
            null
        } ?: run {
            val path = getFilePathFromUri(context, uri)
            if (path != null) {
                java.io.FileInputStream(java.io.File(path))
            } else {
                throw IllegalStateException("Cannot open input stream: $uri")
            }
        }
    }

    private inline fun <T> withFileChannelRw(
        context: Context,
        uri: Uri,
        block: (java.nio.channels.FileChannel) -> T
    ): T {
        // Prefer SAF random access via ParcelFileDescriptor
        try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "rw")
            if (pfd != null) {
                pfd.use { fd ->
                    FileOutputStream(fd.fileDescriptor).channel.use { ch ->
                        return block(ch)
                    }
                }
            }
        } catch (_: Exception) {}

        // Fallback to direct file path
        val path = getFilePathFromUri(context, uri)
        if (path != null) {
            java.io.RandomAccessFile(java.io.File(path), "rw").channel.use { ch ->
                return block(ch)
            }
        }
        throw IllegalStateException("Cannot open random access channel for vault: $uri")
    }

    // ──────────────────────────────────────
    //  v1 compatibility (slow rewrite paths)
    // ──────────────────────────────────────

    private fun addFilesV1Rewrite(
        context: Context,
        vaultUri: Uri,
        password: String,
        existingEntries: List<VaultEntry>,
        newFileUris: List<Uri>,
        onProgress: ((Int, Int) -> Unit)?
    ) {
        // Keep old behavior: fully re-create vault.
        // Note: this is intentionally slower; v2 vaults are fast.
        val (header, _) = openVault(context, vaultUri, password)
        val key = CryptoManager.deriveKey(password, header.salt)

        val existingData = mutableListOf<ByteArray>()
        for (entry in existingEntries) {
            existingData.add(readFileBytes(context, vaultUri, header, entry, key))
        }

        val salt = CryptoManager.generateSalt()
        val newKey = CryptoManager.deriveKey(password, salt)

        val entries = mutableListOf<VaultEntry>()
        val allEncryptedData = ByteArrayOutputStream()
        var currentOffset = 0L
        val totalFiles = existingEntries.size + newFileUris.size
        var fileCounter = 0

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

        for (fileUri in newFileUris) {
            fileCounter++
            onProgress?.invoke(fileCounter, totalFiles)

            val fileName = getFileName(context, fileUri)
            val mimeType = context.contentResolver.getType(fileUri) ?: MimeUtils.guessMimeType(fileName)
            val baseIv = CryptoManager.generateCtrBaseIv()

            var originalSize = 0L
            var chunkIndex = 0L
            val hmacAccumulator = ByteArrayOutputStream()

            openInputStreamCompat(context, fileUri).use { stream ->
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

        val indexJson = entriesToJson(entries)
        val indexIv = CryptoManager.generateGcmIv()
        val encryptedIndex = CryptoManager.encryptIndex(newKey, indexIv, indexJson.toByteArray(Charsets.UTF_8))

        val outputStream = context.contentResolver.openOutputStream(vaultUri, "wt")
            ?: throw IllegalStateException("Cannot write to vault: $vaultUri")

        outputStream.use { stream ->
            DataOutputStream(stream).use { dos ->
                dos.write(CryptoConstants.MAGIC_BYTES)
                dos.writeInt(CryptoConstants.FORMAT_VERSION_V1)
                dos.write(salt)
                dos.write(indexIv)
                dos.writeInt(encryptedIndex.size)
                dos.write(encryptedIndex)
                dos.write(allEncryptedData.toByteArray())
                dos.flush()
            }
        }
    }

    private fun removeFileV1Rewrite(
        context: Context,
        vaultUri: Uri,
        password: String,
        entryId: String,
        onProgress: ((Int, Int) -> Unit)?
    ) {
        // Original v1 implementation: re-create vault without the removed file.
        // (Kept minimal: delegate through existing public API patterns.)
        val (header, existingEntries) = openVault(context, vaultUri, password)
        val key = CryptoManager.deriveKey(password, header.salt)

        val keepEntries = existingEntries.filter { it.id != entryId }
        val keepData = keepEntries.map { entry -> readFileBytes(context, vaultUri, header, entry, key) }

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
        val indexIv = CryptoManager.generateGcmIv()
        val encryptedIndex = CryptoManager.encryptIndex(newKey, indexIv, indexJson.toByteArray(Charsets.UTF_8))

        val outputStream = context.contentResolver.openOutputStream(vaultUri, "wt")
            ?: throw IllegalStateException("Cannot write to vault: $vaultUri")

        outputStream.use { stream ->
            DataOutputStream(stream).use { dos ->
                dos.write(CryptoConstants.MAGIC_BYTES)
                dos.writeInt(CryptoConstants.FORMAT_VERSION_V1)
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
     * Restore multiple files from vault to the same directory as the vault file.
     * Files are written in original (decrypted) form. Name collisions are resolved safely.
     *
     * @return Number of restored files.
     */
    fun restoreFiles(
        context: Context,
        vaultUri: Uri,
        password: String,
        entryIds: List<String>,
        onProgress: ((Int, Int) -> Unit)? = null
    ): Int {
        if (entryIds.isEmpty()) return 0

        val vaultPath = getFilePathFromUri(context, vaultUri)
            ?: throw IllegalStateException("Cannot resolve vault path for restore destination")
        val vaultFile = java.io.File(vaultPath)
        val outputDir = vaultFile.parentFile
            ?: throw IllegalStateException("Cannot resolve vault parent directory")
        if (!outputDir.exists()) outputDir.mkdirs()

        val (header, allEntries) = openVault(context, vaultUri, password)
        val key = CryptoManager.deriveKey(password, header.salt)
        val targetEntries = allEntries.filter { it.id in entryIds.toSet() }

        targetEntries.forEachIndexed { index, entry ->
            onProgress?.invoke(index + 1, targetEntries.size)
            val bytes = readFileBytes(context, vaultUri, header, entry, key)
            val outputFile = resolveUniqueOutputFile(outputDir, entry.fileName)
            FileOutputStream(outputFile).use { it.write(bytes) }
        }
        return targetEntries.size
    }

    private fun resolveUniqueOutputFile(dir: java.io.File, originalName: String): java.io.File {
        val dot = originalName.lastIndexOf('.')
        val base = if (dot > 0) originalName.substring(0, dot) else originalName
        val ext = if (dot > 0) originalName.substring(dot) else ""

        var candidate = java.io.File(dir, originalName)
        var counter = 1
        while (candidate.exists()) {
            candidate = java.io.File(dir, "${base}_restored_$counter$ext")
            counter++
        }
        return candidate
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
