package com.example.bib_vault.state

import android.net.Uri
import com.example.bib_vault.vault.VaultEntry
import com.example.bib_vault.vault.VaultHeader
import javax.crypto.SecretKey

/**
 * Sealed class representing the possible states of the vault UI.
 */
sealed class VaultState {

    /** No vault is open — show home screen */
    data object Locked : VaultState()

    /** A vault operation is in progress (opening, creating, adding files) */
    data class Loading(val message: String = "Processing...") : VaultState()

    /**
     * Vault is unlocked and ready to browse.
     *
     * @property entries List of files inside the vault
     * @property vaultUri URI of the opened vault file
     * @property header Parsed vault header (contains salt, index IV, etc.)
     * @property key Derived encryption key (held in memory only)
     * @property vaultName Display name of the vault file
     */
    data class Unlocked(
        val entries: List<VaultEntry>,
        val vaultUri: Uri,
        val header: VaultHeader,
        val key: SecretKey,
        val vaultName: String = "Vault"
    ) : VaultState()

    /** An error occurred */
    data class Error(val message: String) : VaultState()
}

/**
 * Represents progress during vault creation or file operations.
 */
data class VaultProgress(
    val currentFile: Int = 0,
    val totalFiles: Int = 0,
    val message: String = ""
) {
    val isActive: Boolean get() = totalFiles > 0
    val fraction: Float get() = if (totalFiles > 0) currentFile.toFloat() / totalFiles else 0f
}
