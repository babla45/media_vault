package com.example.bib_vault

import android.content.Context

/**
 * Shared preferences for BibVault settings.
 */
object AppPreferences {
    const val PREFS_NAME = "BibVaultSettings"

    const val KEY_BIV_ENABLED = "isBivEnabled"
    const val KEY_ICON_HIDDEN = "isIconHidden"
    const val KEY_LOCK_ON_BACKGROUND = "lockOnBackground"

    fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isLockOnBackground(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LOCK_ON_BACKGROUND, true)

    fun setLockOnBackground(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LOCK_ON_BACKGROUND, enabled).apply()
    }
}
