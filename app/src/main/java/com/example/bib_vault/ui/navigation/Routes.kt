package com.example.bib_vault.ui.navigation

/**
 * Navigation route constants for the app.
 */
object Routes {
    const val HOME = "home"
    const val CREATE_VAULT = "create_vault"
    const val VAULT_BROWSER = "vault_browser"
    const val MEDIA_PLAYER = "media_player/{entryId}"

    fun mediaPlayer(entryId: String) = "media_player/$entryId"
}
