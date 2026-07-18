package com.example.bib_vault.vault

/**
 * Decrypted vault index payload.
 *
 * Legacy vaults store a bare JSON array of entries.
 * Newer vaults wrap entries with an optional sensitive-password verifier
 * used for restore and disabling screenshot protection.
 */
data class VaultIndexData(
    val entries: List<VaultEntry>,
    val sensitiveSaltHex: String? = null,
    val sensitiveVerifierHex: String? = null
) {
    val hasSensitiveVerifier: Boolean
        get() = !sensitiveSaltHex.isNullOrBlank() && !sensitiveVerifierHex.isNullOrBlank()
}
