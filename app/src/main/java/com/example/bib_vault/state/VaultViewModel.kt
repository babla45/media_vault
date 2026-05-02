package com.example.bib_vault.state

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.bib_vault.vault.VaultEntry
import com.example.bib_vault.vault.VaultHeader
import com.example.bib_vault.vault.VaultManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.crypto.AEADBadTagException
import javax.crypto.SecretKey

/**
 * ViewModel managing vault state throughout the app lifecycle.
 *
 * Holds the derived SecretKey in memory only while the vault is unlocked.
 * The key is zeroed when the vault is locked.
 *
 * All vault operations run on [Dispatchers.IO] to avoid blocking the UI.
 */
class VaultViewModel(application: Application) : AndroidViewModel(application) {

    private val _vaultState = MutableStateFlow<VaultState>(VaultState.Locked)
    val vaultState: StateFlow<VaultState> = _vaultState.asStateFlow()

    private val _progress = MutableStateFlow(VaultProgress())
    val progress: StateFlow<VaultProgress> = _progress.asStateFlow()

    /** Auto-lock timer job */
    private var autoLockJob: Job? = null

    /** Auto-lock timeout in milliseconds (default 2 minutes) */
    var autoLockTimeoutMs: Long = 2 * 60 * 1000L

    // ──────────────────────────────────────
    //  Vault operations
    // ──────────────────────────────────────

    /**
     * Create a new vault from selected files.
     *
     * @param vaultUri Destination URI for the .vault file
     * @param password User password
     * @param fileUris Source file URIs to encrypt
     */
    fun createVault(vaultUri: Uri, password: String, fileUris: List<Uri>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _vaultState.value = VaultState.Loading("Encrypting files...")
                _progress.value = VaultProgress(message = "Starting encryption...")

                VaultManager.createVault(
                    context = getApplication(),
                    vaultUri = vaultUri,
                    password = password,
                    fileUris = fileUris,
                    onProgress = { current, total ->
                        _progress.value = VaultProgress(current, total, "Encrypting file $current of $total...")
                    }
                )

                _progress.value = VaultProgress()
                _vaultState.value = VaultState.Loading("Opening vault...")

                // Auto-open the newly created vault
                openVaultInternal(vaultUri, password)
            } catch (e: Exception) {
                _progress.value = VaultProgress()
                _vaultState.value = VaultState.Error("Failed to create vault: ${e.message}")
            }
        }
    }

    /**
     * Open an existing vault file with the given password.
     */
    fun openVault(vaultUri: Uri, password: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _vaultState.value = VaultState.Loading("Deriving encryption key...")
                openVaultInternal(vaultUri, password)
            } catch (e: AEADBadTagException) {
                _vaultState.value = VaultState.Error("Wrong password")
            } catch (e: Exception) {
                _vaultState.value = VaultState.Error(
                    e.message ?: "Failed to open vault"
                )
            }
        }
    }

    private fun openVaultInternal(vaultUri: Uri, password: String) {
        val (header, entries) = VaultManager.openVault(
            context = getApplication(),
            vaultUri = vaultUri,
            password = password
        )

        val key = com.example.bib_vault.crypto.CryptoManager.deriveKey(password, header.salt)

        // Extract vault display name
        val vaultName = getVaultName(vaultUri)

        _vaultState.value = VaultState.Unlocked(
            entries = entries,
            vaultUri = vaultUri,
            header = header,
            key = key,
            vaultName = vaultName
        )

        resetAutoLockTimer()
    }

    /**
     * Add files to the currently open vault.
     */
    fun addFiles(newFileUris: List<Uri>, password: String) {
        val currentState = _vaultState.value
        if (currentState !is VaultState.Unlocked) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                _vaultState.value = VaultState.Loading("Adding files...")
                _progress.value = VaultProgress(message = "Starting...")

                VaultManager.addFiles(
                    context = getApplication(),
                    vaultUri = currentState.vaultUri,
                    password = password,
                    newFileUris = newFileUris,
                    onProgress = { current, total ->
                        _progress.value = VaultProgress(current, total, "Processing file $current of $total...")
                    }
                )

                _progress.value = VaultProgress()

                // Re-open vault to refresh entries
                openVaultInternal(currentState.vaultUri, password)
            } catch (e: Exception) {
                _progress.value = VaultProgress()
                _vaultState.value = VaultState.Error("Failed to add files: ${e.message}")
            }
        }
    }

    /**
     * Remove a file from the vault by its entry ID.
     */
    fun removeFile(entryId: String, password: String) {
        val currentState = _vaultState.value
        if (currentState !is VaultState.Unlocked) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                _vaultState.value = VaultState.Loading("Removing file...")

                VaultManager.removeFile(
                    context = getApplication(),
                    vaultUri = currentState.vaultUri,
                    password = password,
                    entryId = entryId
                )

                openVaultInternal(currentState.vaultUri, password)
            } catch (e: Exception) {
                _vaultState.value = VaultState.Error("Failed to remove file: ${e.message}")
            }
        }
    }

    /**
     * Remove multiple files from the vault by entry IDs.
     */
    fun removeFiles(entryIds: List<String>, password: String) {
        val currentState = _vaultState.value
        if (currentState !is VaultState.Unlocked || entryIds.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                _vaultState.value = VaultState.Loading("Removing files...")
                entryIds.forEach { id ->
                    VaultManager.removeFile(
                        context = getApplication(),
                        vaultUri = currentState.vaultUri,
                        password = password,
                        entryId = id
                    )
                }
                openVaultInternal(currentState.vaultUri, password)
            } catch (e: Exception) {
                _vaultState.value = VaultState.Error("Failed to remove files: ${e.message}")
            }
        }
    }

    /**
     * Decrypt an image file entirely in memory for display.
     * Returns null on failure.
     */
    suspend fun decryptImageBytes(entry: VaultEntry): ByteArray? {
        val currentState = _vaultState.value
        if (currentState !is VaultState.Unlocked) return null

        return try {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                VaultManager.readFileBytes(
                    context = getApplication(),
                    vaultUri = currentState.vaultUri,
                    header = currentState.header,
                    entry = entry,
                    key = currentState.key
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Get the current vault header (for DataSource factory).
     */
    fun getCurrentHeader(): VaultHeader? = (_vaultState.value as? VaultState.Unlocked)?.header

    /**
     * Get the current encryption key (for DataSource factory).
     */
    fun getCurrentKey(): SecretKey? = (_vaultState.value as? VaultState.Unlocked)?.key

    /**
     * Get the current vault URI.
     */
    fun getCurrentVaultUri(): Uri? = (_vaultState.value as? VaultState.Unlocked)?.vaultUri

    /**
     * Get the entries map (id → entry) for the DataSource.
     */
    fun getEntriesMap(): Map<String, VaultEntry> {
        val state = _vaultState.value as? VaultState.Unlocked ?: return emptyMap()
        return state.entries.associateBy { it.id }
    }

    /**
     * Find an entry by ID.
     */
    fun findEntry(entryId: String): VaultEntry? {
        val state = _vaultState.value as? VaultState.Unlocked ?: return null
        return state.entries.find { it.id == entryId }
    }

    // ──────────────────────────────────────
    //  Lock & security
    // ──────────────────────────────────────

    /**
     * Lock the vault: clear key from memory and reset state.
     */
    fun lock() {
        autoLockJob?.cancel()
        _vaultState.value = VaultState.Locked
        _progress.value = VaultProgress()
    }

    /**
     * Reset the inactivity auto-lock timer.
     * Should be called on user interaction.
     */
    fun resetAutoLockTimer() {
        autoLockJob?.cancel()
        autoLockJob = viewModelScope.launch {
            delay(autoLockTimeoutMs)
            lock()
        }
    }

    /** Clear error state and return to locked */
    fun clearError() {
        _vaultState.value = VaultState.Locked
    }

    // ──────────────────────────────────────
    //  Helpers
    // ──────────────────────────────────────

    private fun getVaultName(uri: Uri): String {
        val context: Application = getApplication()
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    return cursor.getString(nameIndex)
                }
            }
        }
        return uri.lastPathSegment ?: "Vault"
    }

    override fun onCleared() {
        super.onCleared()
        autoLockJob?.cancel()
    }
}
