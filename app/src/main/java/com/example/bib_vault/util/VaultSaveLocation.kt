package com.example.bib_vault.util

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/**
 * Helpers for saving new vaults into a user-chosen directory tree.
 */
object VaultSaveLocation {

    /**
     * Create a new .biv document under [treeUri] with a unique display name.
     * Returns null if creation fails.
     */
    fun createVaultInTree(
        context: Context,
        treeUri: Uri,
        baseName: String = "vault"
    ): Uri? {
        return try {
            val resolver = context.contentResolver
            val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)
            val existing = listChildNames(context, treeUri, treeDocId)

            var name = "$baseName.biv"
            var counter = 1
            while (existing.any { it.equals(name, ignoreCase = true) }) {
                name = "${baseName}_$counter.biv"
                counter++
            }

            DocumentsContract.createDocument(
                resolver,
                parentUri,
                "application/octet-stream",
                name
            )
        } catch (_: Exception) {
            null
        }
    }

    /** Human-readable label for the saved tree URI. */
    fun displayPath(treeUri: Uri): String {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
        if (!docId.isNullOrBlank()) {
            val decoded = Uri.decode(docId)
            val colon = decoded.indexOf(':')
            return if (colon >= 0 && colon < decoded.lastIndex) {
                decoded.substring(colon + 1).ifBlank { decoded }
            } else {
                decoded
            }
        }
        return treeUri.lastPathSegment?.let { Uri.decode(it) } ?: treeUri.toString()
    }

    private fun listChildNames(
        context: Context,
        treeUri: Uri,
        treeDocId: String
    ): Set<String> {
        val names = mutableSetOf<String>()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)
        try {
            context.contentResolver.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                if (nameIndex < 0) return names
                while (cursor.moveToNext()) {
                    cursor.getString(nameIndex)?.let { names.add(it) }
                }
            }
        } catch (_: Exception) {
            // Best-effort; unique naming will still usually work
        }
        return names
    }
}
