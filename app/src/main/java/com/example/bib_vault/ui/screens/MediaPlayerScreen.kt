package com.example.bib_vault.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.example.bib_vault.player.EncryptedDataSourceFactory
import com.example.bib_vault.ui.theme.*
import com.example.bib_vault.util.FormatUtils
import com.example.bib_vault.util.MimeUtils
import com.example.bib_vault.util.MediaType
import com.example.bib_vault.vault.VaultEntry
import com.example.bib_vault.vault.VaultHeader
import android.net.Uri
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Full-screen media viewer/player.
 * - Video: ExoPlayer with custom encrypted DataSource + transport controls
 * - Audio: ExoPlayer with waveform-style UI
 * - Image: Zoomable image viewer with pinch-to-zoom
 *
 * All media is decrypted on-the-fly from the vault — never written to disk.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaPlayerScreen(
    entry: VaultEntry,
    vaultUri: Uri,
    header: VaultHeader,
    key: SecretKey,
    entries: Map<String, VaultEntry>,
    onDecryptImage: suspend (VaultEntry) -> ByteArray?,
    onBack: () -> Unit
) {
    val mediaType = MimeUtils.getMediaType(entry.mimeType)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            entry.fileName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            "${MimeUtils.getTypeLabel(entry.mimeType)} • ${FormatUtils.formatFileSize(entry.originalSize)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = VaultOnSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.7f),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        },
        containerColor = Color.Black
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center
        ) {
            when (mediaType) {
                MediaType.VIDEO, MediaType.AUDIO -> {
                    EncryptedMediaPlayer(
                        entry = entry,
                        vaultUri = vaultUri,
                        header = header,
                        key = key,
                        entries = entries,
                        isAudioOnly = mediaType == MediaType.AUDIO
                    )
                }
                MediaType.IMAGE -> {
                    EncryptedImageViewer(
                        entry = entry,
                        onDecryptImage = onDecryptImage
                    )
                }
                MediaType.OTHER -> {
                    Text(
                        "Unsupported file type",
                        color = VaultOnSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Video/Audio player using ExoPlayer with custom EncryptedDataSource.
 * Reads and decrypts media chunks on-the-fly from the vault container.
 */
@Composable
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun EncryptedMediaPlayer(
    entry: VaultEntry,
    vaultUri: Uri,
    header: VaultHeader,
    key: SecretKey,
    entries: Map<String, VaultEntry>,
    isAudioOnly: Boolean
) {
    val context = LocalContext.current

    // Create the encrypted data source factory
    val dataSourceFactory = remember(vaultUri, key) {
        EncryptedDataSourceFactory(context, vaultUri, key, entries, header)
    }

    // Create ExoPlayer instance
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            // Build media source from encrypted data source
            val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(
                    MediaItem.fromUri(Uri.parse("bibvault://entry/${entry.id}"))
                )
            setMediaSource(mediaSource)
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
        }
    }

    if (isAudioOnly) {
        // Audio-only UI with playback controls
        AudioPlayerUI(entry = entry, player = exoPlayer)
    } else {
        // Video player with ExoPlayer's built-in controls
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

/**
 * Audio player UI with large icon, file info, and playback controls.
 */
@Composable
private fun AudioPlayerUI(
    entry: VaultEntry,
    player: ExoPlayer
) {
    var isPlaying by remember { mutableStateOf(true) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }

    LaunchedEffect(player) {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    duration = player.duration
                }
            }
        })
    }

    // Update position periodically
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            currentPosition = player.currentPosition
            kotlinx.coroutines.delay(500)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(VaultBackground)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Large music icon
        Box(
            modifier = Modifier
                .size(160.dp)
                .clip(RoundedCornerShape(32.dp))
                .background(
                    brush = androidx.compose.ui.graphics.Brush.linearGradient(
                        colors = listOf(
                            AudioColor.copy(alpha = 0.3f),
                            AudioColor.copy(alpha = 0.1f)
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.MusicNote,
                contentDescription = null,
                tint = AudioColor,
                modifier = Modifier.size(72.dp)
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        // File name
        Text(
            text = entry.fileName,
            style = MaterialTheme.typography.headlineMedium,
            color = VaultOnSurface,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = FormatUtils.formatFileSize(entry.originalSize),
            style = MaterialTheme.typography.bodyMedium,
            color = VaultOnSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Progress bar
        if (duration > 0) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Slider(
                    value = if (duration > 0) currentPosition.toFloat() / duration else 0f,
                    onValueChange = { fraction ->
                        player.seekTo((fraction * duration).toLong())
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = AudioColor,
                        activeTrackColor = AudioColor,
                        inactiveTrackColor = VaultSurfaceLight
                    )
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        FormatUtils.formatDuration(currentPosition),
                        style = MaterialTheme.typography.bodySmall,
                        color = VaultOnSurfaceVariant
                    )
                    Text(
                        FormatUtils.formatDuration(duration),
                        style = MaterialTheme.typography.bodySmall,
                        color = VaultOnSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Playback controls
        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Rewind 10s
            IconButton(
                onClick = { player.seekTo(maxOf(0, player.currentPosition - 10_000)) }
            ) {
                Icon(
                    Icons.Default.Replay10,
                    "Rewind 10s",
                    tint = VaultOnSurface,
                    modifier = Modifier.size(36.dp)
                )
            }

            // Play/Pause
            FilledIconButton(
                onClick = {
                    if (player.isPlaying) player.pause() else player.play()
                },
                modifier = Modifier.size(64.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = AudioColor
                ),
                shape = CircleShape
            ) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    "Play/Pause",
                    tint = Color.White,
                    modifier = Modifier.size(36.dp)
                )
            }

            // Forward 10s
            IconButton(
                onClick = { player.seekTo(player.currentPosition + 10_000) }
            ) {
                Icon(
                    Icons.Default.Forward10,
                    "Forward 10s",
                    tint = VaultOnSurface,
                    modifier = Modifier.size(36.dp)
                )
            }
        }
    }
}

/**
 * Image viewer with pinch-to-zoom and pan.
 * Decrypts the image entirely in memory — no temp files.
 */
@Composable
private fun EncryptedImageViewer(
    entry: VaultEntry,
    onDecryptImage: suspend (VaultEntry) -> ByteArray?
) {
    var imageBytes by remember { mutableStateOf<ByteArray?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    // Zoom/pan state
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(entry.id) {
        isLoading = true
        try {
            val bytes = withContext(Dispatchers.IO) {
                onDecryptImage(entry)
            }
            if (bytes != null) {
                imageBytes = bytes
            } else {
                error = "Failed to decrypt image"
            }
        } catch (e: Exception) {
            error = "Error: ${e.message}"
        }
        isLoading = false
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        when {
            isLoading -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = VaultPrimary)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Decrypting image...", color = VaultOnSurfaceVariant)
                }
            }
            error != null -> {
                Text(error!!, color = VaultError)
            }
            imageBytes != null -> {
                val bitmap = remember(imageBytes) {
                    BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes!!.size)
                }

                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = entry.fileName,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offset.x,
                                translationY = offset.y
                            )
                            .pointerInput(Unit) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    scale = (scale * zoom).coerceIn(0.5f, 5f)
                                    offset = Offset(
                                        x = offset.x + pan.x,
                                        y = offset.y + pan.y
                                    )
                                }
                            }
                    )
                } else {
                    Text("Could not decode image", color = VaultError)
                }
            }
        }
    }
}
