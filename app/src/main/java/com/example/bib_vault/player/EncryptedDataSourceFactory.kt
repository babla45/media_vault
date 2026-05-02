package com.example.bib_vault.player

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.DataSource
import com.example.bib_vault.vault.VaultEntry
import com.example.bib_vault.vault.VaultHeader
import javax.crypto.SecretKey

/**
 * Factory that creates [EncryptedDataSource] instances for ExoPlayer.
 *
 * Each DataSource instance gets its own file handle and chunk cache,
 * so multiple concurrent reads (e.g., video + audio tracks) are safe.
 *
 * Usage:
 * ```
 * val factory = EncryptedDataSourceFactory(context, vaultUri, key, entries, header)
 * val mediaSource = ProgressiveMediaSource.Factory(factory)
 *     .createMediaSource(MediaItem.fromUri("bibvault://entry/$entryId"))
 * exoPlayer.setMediaSource(mediaSource)
 * ```
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class EncryptedDataSourceFactory(
    private val context: Context,
    private val vaultUri: Uri,
    private val key: SecretKey,
    private val entries: Map<String, VaultEntry>,
    private val header: VaultHeader
) : DataSource.Factory {

    override fun createDataSource(): DataSource {
        return EncryptedDataSource(context, vaultUri, key, entries, header)
    }
}
