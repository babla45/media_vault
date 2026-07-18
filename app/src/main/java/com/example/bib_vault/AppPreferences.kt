package com.example.bib_vault

import android.content.Context
import android.net.Uri

/**
 * Shared preferences for BibVault settings.
 */
object AppPreferences {
    const val PREFS_NAME = "BibVaultSettings"

    const val KEY_BIV_ENABLED = "isBivEnabled"
    const val KEY_ICON_HIDDEN = "isIconHidden"
    const val KEY_LOCK_ON_BACKGROUND = "lockOnBackground"
    const val KEY_AUTO_FIND_VAULTS = "autoFindVaults"
    const val KEY_CUSTOM_VAULT_SAVE_ENABLED = "customVaultSaveEnabled"
    const val KEY_CUSTOM_VAULT_SAVE_URI = "customVaultSaveUri"

    fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isLockOnBackground(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LOCK_ON_BACKGROUND, true)

    fun setLockOnBackground(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LOCK_ON_BACKGROUND, enabled).apply()
    }

    fun isAutoFindVaults(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_FIND_VAULTS, false)

    fun setAutoFindVaults(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_FIND_VAULTS, enabled).apply()
    }

    fun isCustomVaultSaveEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CUSTOM_VAULT_SAVE_ENABLED, false)

    fun setCustomVaultSaveEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CUSTOM_VAULT_SAVE_ENABLED, enabled).apply()
    }

    fun getCustomVaultSaveUri(context: Context): Uri? {
        val raw = prefs(context).getString(KEY_CUSTOM_VAULT_SAVE_URI, null) ?: return null
        return runCatching { Uri.parse(raw) }.getOrNull()
    }

    fun setCustomVaultSaveUri(context: Context, uri: Uri?) {
        prefs(context).edit().putString(KEY_CUSTOM_VAULT_SAVE_URI, uri?.toString()).apply()
    }
}
