package com.example.bib_vault.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
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
import android.app.Activity
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.WindowManager
import android.media.AudioManager
import android.provider.Settings
import android.content.Context
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

fun android.content.Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun currentWindowBrightnessFraction(activity: Activity?): Float? {
    val b = activity?.window?.attributes?.screenBrightness ?: return null
    if (b < 0f || b > 1f) return null
    return b
}

private fun readSystemBrightnessFraction(context: android.content.Context): Float =
    try {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
            .coerceIn(0, 255) / 255f
    } catch (_: Settings.SettingNotFoundException) {
        0.5f
    } catch (_: SecurityException) {
        0.5f
    }

private fun applyWindowBrightness(activity: Activity?, level: Float) {
    val window = activity?.window ?: return
    val lp = window.attributes
    lp.screenBrightness = level.coerceIn(0f, 1f)
    window.attributes = lp
}

private fun clearWindowBrightnessOverride(activity: Activity?) {
    val window = activity?.window ?: return
    val lp = window.attributes
    lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    window.attributes = lp
}

private sealed interface VideoGestureHud {
    data object Hidden : VideoGestureHud
    data class BrightnessLevel(val fraction: Float) : VideoGestureHud
    data class VolumeLevel(val fraction: Float) : VideoGestureHud
    data class Seeking(val positionMs: Long, val durationMs: Long) : VideoGestureHud
}

private enum class VideoTouchZone { Left, Center, Right }

private enum class VideoTouchMode {
    Brightness,
    Volume,
    Seek
}

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
                actions = {
                    val context = LocalContext.current
                    if (mediaType == MediaType.VIDEO) {
                        val activity = context.findActivity()
                        var isLandscape by remember { 
                            mutableStateOf(activity?.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) 
                        }
                        IconButton(onClick = {
                            if (isLandscape) {
                                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                isLandscape = false
                            } else {
                                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                isLandscape = true
                            }
                        }) {
                            Icon(Icons.Default.ScreenRotation, "Rotate Screen")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.7f),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White
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
        VideoPlayerWithGestureControls(exoPlayer = exoPlayer)
    }
}

/**
 * ExoPlayer [PlayerView] with gestures:
 * - Left third: vertical drag → brightness (HUD with vertical level bar).
 * - Right third: vertical drag → volume (vertical bar).
 * - Center band: horizontal drag → seek (full swipe across center ≈ full timeline).
 */
@Composable
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun VideoPlayerWithGestureControls(exoPlayer: ExoPlayer) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    val maxMusicVolume = remember(audioManager) {
        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    }
    val touchSlop = LocalViewConfiguration.current.touchSlop
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }
    val playerViewHandle = rememberUpdatedState(playerViewRef)

    var screenHeightPx by remember { mutableFloatStateOf(1f) }
    var centerWidthPx by remember { mutableFloatStateOf(1f) }
    var hud by remember { mutableStateOf<VideoGestureHud>(VideoGestureHud.Hidden) }

    LaunchedEffect(hud) {
        if (hud is VideoGestureHud.Hidden) return@LaunchedEffect
        delay(850)
        hud = VideoGestureHud.Hidden
    }

    DisposableEffect(activity) {
        onDispose {
            clearWindowBrightnessOverride(activity)
        }
    }

    val sideZoneFraction = 0.36f
    val dragSensitivity = 1.15f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged {
                screenHeightPx = it.height.toFloat().coerceAtLeast(1f)
                val w = it.width.toFloat().coerceAtLeast(1f)
                centerWidthPx = (w * (1f - 2f * sideZoneFraction)).coerceAtLeast(1f)
            }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    playerViewRef = this
                }
            },
            update = { pv ->
                playerViewRef = pv
            },
            modifier = Modifier.fillMaxSize()
        )

        // Single overlay: drags = brightness / volume / seek; short tap = show PlayerView controller
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(
                    touchSlop,
                    screenHeightPx,
                    centerWidthPx,
                    sideZoneFraction,
                    dragSensitivity,
                    activity,
                    context,
                    audioManager,
                    maxMusicVolume,
                    exoPlayer
                ) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val width = size.width.toFloat().coerceAtLeast(1f)
                        val leftBound = width * sideZoneFraction
                        val rightBound = width * (1f - sideZoneFraction)

                        fun zoneAt(x: Float) = when {
                            x < leftBound -> VideoTouchZone.Left
                            x > rightBound -> VideoTouchZone.Right
                            else -> VideoTouchZone.Center
                        }

                        val startZone = zoneAt(down.position.x)
                        var totalDx = 0f
                        var totalDy = 0f
                        var mode: VideoTouchMode? = null
                        var dragStartBrightness = 0f
                        var dragStartVolFraction = 0f
                        var seekBasePos = 0L
                        var seekAccumDx = 0f

                        gestureLoop@ while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val change = event.changes.find { it.id == down.id } ?: break
                            if (change.changedToUpIgnoreConsumed()) {
                                if (mode == null) {
                                    val dist = hypot(totalDx.toDouble(), totalDy.toDouble()).toFloat()
                                    if (dist < touchSlop) {
                                        playerViewHandle.value?.showController()
                                    }
                                }
                                break
                            }

                            val dx = change.positionChange().x
                            val dy = change.positionChange().y
                            totalDx += dx
                            totalDy += dy

                            if (mode == null) {
                                val dist = hypot(totalDx.toDouble(), totalDy.toDouble()).toFloat()
                                if (dist >= touchSlop) {
                                    val ax = abs(totalDx)
                                    val ay = abs(totalDy)
                                    mode = when (startZone) {
                                        VideoTouchZone.Left ->
                                            if (ay >= ax) VideoTouchMode.Brightness else null
                                        VideoTouchZone.Right ->
                                            if (ay >= ax) VideoTouchMode.Volume else null
                                        VideoTouchZone.Center ->
                                            if (ax >= ay) VideoTouchMode.Seek else null
                                    }
                                    when (mode) {
                                        VideoTouchMode.Brightness -> {
                                            dragStartBrightness =
                                                currentWindowBrightnessFraction(activity)
                                                    ?: readSystemBrightnessFraction(context)
                                            hud = VideoGestureHud.BrightnessLevel(dragStartBrightness)
                                        }
                                        VideoTouchMode.Volume -> {
                                            dragStartVolFraction =
                                                audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                                                    .toFloat() / maxMusicVolume
                                            hud = VideoGestureHud.VolumeLevel(dragStartVolFraction)
                                        }
                                        VideoTouchMode.Seek -> {
                                            seekBasePos = exoPlayer.currentPosition
                                            seekAccumDx = 0f
                                            val dur = exoPlayer.duration
                                            if (dur > 0 && dur != C.TIME_UNSET) {
                                                hud = VideoGestureHud.Seeking(seekBasePos, dur)
                                            }
                                        }
                                        null -> Unit
                                    }
                                }
                            }

                            when (mode) {
                                VideoTouchMode.Brightness -> {
                                    change.consume()
                                    val act = activity ?: continue@gestureLoop
                                    val level =
                                        (dragStartBrightness - totalDy / screenHeightPx * dragSensitivity)
                                            .coerceIn(0f, 1f)
                                    applyWindowBrightness(act, level)
                                    hud = VideoGestureHud.BrightnessLevel(level)
                                }
                                VideoTouchMode.Volume -> {
                                    change.consume()
                                    val frac =
                                        (dragStartVolFraction - totalDy / screenHeightPx * dragSensitivity)
                                            .coerceIn(0f, 1f)
                                    val idx = (frac * maxMusicVolume).roundToInt()
                                        .coerceIn(0, maxMusicVolume)
                                    audioManager.setStreamVolume(
                                        AudioManager.STREAM_MUSIC,
                                        idx,
                                        0
                                    )
                                    hud = VideoGestureHud.VolumeLevel(frac)
                                }
                                VideoTouchMode.Seek -> {
                                    change.consume()
                                    val dur = exoPlayer.duration
                                    if (dur > 0 && dur != C.TIME_UNSET) {
                                        seekAccumDx += change.positionChange().x
                                        val deltaMs = (seekAccumDx / centerWidthPx) * dur.toFloat()
                                        val newPos =
                                            (seekBasePos + deltaMs.toLong()).coerceIn(0L, dur)
                                        exoPlayer.seekTo(newPos)
                                        hud = VideoGestureHud.Seeking(newPos, dur)
                                    }
                                }
                                null -> Unit
                            }
                        }
                    }
                }
        )

        when (val state = hud) {
            is VideoGestureHud.BrightnessLevel -> {
                VideoGestureVerticalHudOverlay(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 24.dp),
                    icon = {
                        Icon(
                            Icons.Default.LightMode,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(26.dp)
                        )
                    },
                    progress = state.fraction,
                    label = "${(state.fraction * 100f).roundToInt().coerceIn(0, 100)}%"
                )
            }
            is VideoGestureHud.VolumeLevel -> {
                VideoGestureVerticalHudOverlay(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 24.dp),
                    icon = {
                        Icon(
                            Icons.Default.VolumeUp,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(26.dp)
                        )
                    },
                    progress = state.fraction,
                    label = "${(state.fraction * 100f).roundToInt().coerceIn(0, 100)}%"
                )
            }
            is VideoGestureHud.Seeking -> {
                SeekGestureHudOverlay(
                    modifier = Modifier.align(Alignment.Center),
                    positionMs = state.positionMs,
                    durationMs = state.durationMs
                )
            }
            VideoGestureHud.Hidden -> Unit
        }
    }
}

@Composable
private fun VideoGestureVerticalHudOverlay(
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
    progress: Float,
    label: String
) {
    val fraction = progress.coerceIn(0f, 1f)
    Surface(
        color = Color.Black.copy(alpha = 0.55f),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.widthIn(min = 72.dp, max = 96.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            icon()
            Box(
                modifier = Modifier
                    .height(132.dp)
                    .width(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.White.copy(alpha = 0.28f))
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(fraction)
                        .background(Color.White)
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White
            )
        }
    }
}

@Composable
private fun SeekGestureHudOverlay(
    modifier: Modifier = Modifier,
    positionMs: Long,
    durationMs: Long
) {
    val frac =
        if (durationMs > 0) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
    Surface(
        color = Color.Black.copy(alpha = 0.55f),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.widthIn(min = 200.dp, max = 280.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.Schedule,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = "${FormatUtils.formatDuration(positionMs)} / ${FormatUtils.formatDuration(durationMs)}",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
            }
            LinearProgressIndicator(
                progress = { frac },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.25f)
            )
        }
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
