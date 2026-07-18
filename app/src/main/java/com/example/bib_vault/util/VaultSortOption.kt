package com.example.bib_vault.util

import android.content.Context
import com.example.bib_vault.vault.VaultEntry

private const val BROWSER_PREFS = "vault_browser_settings"
private const val KEY_SORT_OPTION = "sort_option_ordinal"

/**
 * How vault browser (and media next/prev) order files.
 */
enum class VaultSortOption(val label: String) {
    NAME_ASC("Name (A–Z)"),
    NAME_DESC("Name (Z–A)"),
    DATE_NEWEST("Date added (newest)"),
    DATE_OLDEST("Date added (oldest)"),
    SIZE_LARGEST("Size (largest)"),
    SIZE_SMALLEST("Size (smallest)"),
    TYPE("Type");

    fun sort(entries: List<VaultEntry>): List<VaultEntry> = when (this) {
        NAME_ASC -> entries.sortedBy { it.fileName.lowercase() }
        NAME_DESC -> entries.sortedByDescending { it.fileName.lowercase() }
        DATE_NEWEST -> entries.sortedByDescending { it.addedTimestamp }
        DATE_OLDEST -> entries.sortedBy { it.addedTimestamp }
        SIZE_LARGEST -> entries.sortedByDescending { it.originalSize }
        SIZE_SMALLEST -> entries.sortedBy { it.originalSize }
        TYPE -> entries.sortedWith(
            compareBy<VaultEntry> { MimeUtils.getMediaType(it.mimeType).ordinal }
                .thenBy { it.fileName.lowercase() }
        )
    }

    companion object {
        fun fromPrefs(context: Context): VaultSortOption {
            val ordinal = context
                .getSharedPreferences(BROWSER_PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_SORT_OPTION, NAME_ASC.ordinal)
                .coerceIn(0, entries.lastIndex)
            return entries[ordinal]
        }

        fun saveToPrefs(context: Context, option: VaultSortOption) {
            context.getSharedPreferences(BROWSER_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_SORT_OPTION, option.ordinal)
                .apply()
        }
    }
}
