package com.example.bib_vault.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.webkit.WebView
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.font.FontFamily
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
import androidx.media3.ui.R as Media3UiR
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import android.view.View
import android.view.WindowManager
import android.media.AudioManager
import android.provider.Settings
import android.content.Context
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Max decrypted size loaded into memory for text / markdown preview. */
private const val TEXT_PREVIEW_MAX_BYTES = 750_000L

/** Max size for PDF preview (decrypt + temp file on disk). */
private const val PDF_PREVIEW_MAX_BYTES = 25L * 1024L * 1024L

private val markdownParser: Parser = Parser.builder().build()
private val markdownHtmlRenderer: HtmlRenderer = HtmlRenderer.builder().build()

private fun markdownToHtml(markdown: String): String {
    val document = markdownParser.parse(markdown)
    return markdownHtmlRenderer.render(document)
}

private fun wrapMarkdownHtml(body: String): String = """
<!DOCTYPE html>
<html><head>
<meta charset="UTF-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1"/>
<style>
body { margin:0; padding:12px; background:#1a1a1a; color:#e8e8e8; font-family: sans-serif; font-size:15px; line-height:1.5; }
pre { background:#2a2a2a; padding:10px; border-radius:8px; overflow:auto; }
code { background:#2a2a2a; padding:2px 6px; border-radius:4px; font-family: monospace; font-size:0.9em; }
pre code { background:transparent; padding:0; }
a { color:#8ab4f8; }
blockquote { border-left:3px solid #555; margin:8px 0; padding-left:12px; color:#c8c8c8; }
h1,h2,h3,h4 { color:#fff; margin:0.6em 0 0.3em; }
table { border-collapse:collapse; width:100%; }
th,td { border:1px solid #444; padding:6px; }
th { background:#2a2a2a; }
img { max-width:100%; height:auto; }
</style></head><body>$body</body></html>
""".trimIndent()

private class PdfPreviewHolder(
    private val file: File,
    private val pfd: ParcelFileDescriptor,
    val renderer: PdfRenderer
) {
    private var closed = false
    val pageCount: Int get() = renderer.pageCount

    fun renderPage(pageIndex: Int, targetWidthPx: Int): Bitmap? {
        if (closed || pageIndex < 0 || pageIndex >= renderer.pageCount || targetWidthPx <= 0) return null
        renderer.openPage(pageIndex).use { page ->
            val scale = targetWidthPx.toFloat() / page.width
            val w = targetWidthPx
            val h = (page.height * scale).roundToInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val matrix = Matrix()
            matrix.setScale(scale, scale)
            page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bitmap
        }
    }

    fun close() {
        if (closed) return
        closed = true
        try {
            renderer.close()
        } catch (_: Exception) {
        }
        try {
            pfd.close()
        } catch (_: Exception) {
        }
        try {
            file.delete()
        } catch (_: Exception) {
        }
    }
}

private fun writePdfTempAndOpen(context: Context, bytes: ByteArray): PdfPreviewHolder {
    val file = File.createTempFile("bib_vault_pdf_", ".pdf", context.cacheDir)
    FileOutputStream(file).use { it.write(bytes) }
    val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    val renderer = PdfRenderer(pfd)
    return PdfPreviewHolder(file, pfd, renderer)
}

private fun decodeUtf8WithReplacement(bytes: ByteArray): String {
    val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    return decoder.decode(ByteBuffer.wrap(bytes)).toString()
}

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

private fun setFullscreenSystemBarsVisible(activity: Activity?, visible: Boolean) {
    val window = activity?.window ?: return
    WindowCompat.setDecorFitsSystemWindows(window, false)
    val insetsController = WindowCompat.getInsetsController(window, window.decorView) ?: return
    insetsController.systemBarsBehavior =
        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    if (visible) {
        insetsController.show(WindowInsetsCompat.Type.systemBars())
    } else {
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
    }
}

private fun restoreSystemBarsVisibility(activity: Activity?) {
    val window = activity?.window ?: return
    WindowCompat.setDecorFitsSystemWindows(window, true)
    WindowCompat.getInsetsController(window, window.decorView)
        ?.show(WindowInsetsCompat.Type.systemBars())
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
    Seeking
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
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val sortedEntries = remember(entries) {
        entries.values.sortedWith(compareBy<VaultEntry> { it.addedTimestamp }.thenBy { it.fileName })
    }
    val imageEntries = remember(sortedEntries) {
        sortedEntries.filter { MimeUtils.getMediaType(it.mimeType) == MediaType.IMAGE }
    }
    val videoEntries = remember(sortedEntries) {
        sortedEntries.filter {
            val type = MimeUtils.getMediaType(it.mimeType)
            type == MediaType.VIDEO
        }
    }
    val audioEntries = remember(sortedEntries) {
        sortedEntries.filter { MimeUtils.getMediaType(it.mimeType) == MediaType.AUDIO }
    }
    val otherEntries = remember(sortedEntries) {
        sortedEntries.filter { MimeUtils.getMediaType(it.mimeType) == MediaType.OTHER }
    }
    var currentEntryId by remember(entry.id) { mutableStateOf(entry.id) }
    val currentEntry = remember(entry, entries, currentEntryId) {
        entries[currentEntryId] ?: entry
    }

    val mediaType = MimeUtils.getMediaType(currentEntry.mimeType)
    val isVideo = mediaType == MediaType.VIDEO
    val currentImageIndex = if (mediaType == MediaType.IMAGE) {
        imageEntries.indexOfFirst { it.id == currentEntry.id }
    } else {
        -1
    }
    val canSwipePrev = currentImageIndex > 0
    val canSwipeNext = currentImageIndex >= 0 && currentImageIndex < imageEntries.lastIndex
    val mediaPlaylist = when (mediaType) {
        MediaType.VIDEO -> videoEntries
        MediaType.AUDIO -> audioEntries
        MediaType.OTHER -> otherEntries
        else -> emptyList()
    }
    val currentPlaylistIndex = mediaPlaylist.indexOfFirst { it.id == currentEntry.id }
    val canPrevMedia = currentPlaylistIndex > 0
    val canNextMedia = currentPlaylistIndex >= 0 && currentPlaylistIndex < mediaPlaylist.lastIndex
    val isImage = mediaType == MediaType.IMAGE
    var imageChromeVisible by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(isImage, imageChromeVisible, activity) {
        if (!isImage) return@LaunchedEffect
        setFullscreenSystemBarsVisible(activity, visible = imageChromeVisible)
    }

    DisposableEffect(isImage, activity) {
        onDispose {
            if (isImage) {
                restoreSystemBarsVisibility(activity)
            }
        }
    }

    Scaffold(
        topBar = {
            if (!isVideo && (!isImage || imageChromeVisible)) {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                currentEntry.fileName,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                "${MimeUtils.getTypeLabel(currentEntry.mimeType)} • ${FormatUtils.formatFileSize(currentEntry.originalSize)}",
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
                        navigationIconContentColor = Color.White,
                        actionIconContentColor = Color.White
                    )
                )
            }
        },
        containerColor = Color.Black
    ) { padding ->
        Box(
            modifier = if (isVideo) {
                Modifier.fillMaxSize()
            } else {
                Modifier
                    .fillMaxSize()
                    .padding(padding)
            },
            contentAlignment = Alignment.Center
        ) {
            when (mediaType) {
                MediaType.VIDEO, MediaType.AUDIO -> {
                    EncryptedMediaPlayer(
                        entry = currentEntry,
                        vaultUri = vaultUri,
                        header = header,
                        key = key,
                        entries = entries,
                        isAudioOnly = mediaType == MediaType.AUDIO,
                        canPrevMedia = canPrevMedia,
                        canNextMedia = canNextMedia,
                        onPrevMedia = {
                            if (canPrevMedia) {
                                currentEntryId = mediaPlaylist[currentPlaylistIndex - 1].id
                            }
                        },
                        onNextMedia = {
                            if (canNextMedia) {
                                currentEntryId = mediaPlaylist[currentPlaylistIndex + 1].id
                            }
                        }
                    )
                }
                MediaType.IMAGE -> {
                    EncryptedImageViewer(
                        entry = currentEntry,
                        onDecryptImage = onDecryptImage,
                        showNavButtons = imageChromeVisible,
                        onToggleChrome = {
                            imageChromeVisible = !imageChromeVisible
                        },
                        canSwipePrev = canSwipePrev,
                        canSwipeNext = canSwipeNext,
                        onSwipePrev = {
                            if (canSwipePrev) {
                                currentEntryId = imageEntries[currentImageIndex - 1].id
                            }
                        },
                        onSwipeNext = {
                            if (canSwipeNext) {
                                currentEntryId = imageEntries[currentImageIndex + 1].id
                            }
                        }
                    )
                }
                MediaType.OTHER -> {
                    when {
                        MimeUtils.isPdfPreviewable(currentEntry.mimeType, currentEntry.fileName) -> {
                            PdfVaultFilePreview(
                                entry = currentEntry,
                                onDecrypt = onDecryptImage,
                                canPrev = canPrevMedia,
                                canNext = canNextMedia,
                                onPrev = {
                                    if (canPrevMedia) {
                                        currentEntryId = mediaPlaylist[currentPlaylistIndex - 1].id
                                    }
                                },
                                onNext = {
                                    if (canNextMedia) {
                                        currentEntryId = mediaPlaylist[currentPlaylistIndex + 1].id
                                    }
                                }
                            )
                        }
                        MimeUtils.isMarkdownFile(currentEntry.mimeType, currentEntry.fileName) -> {
                            MarkdownVaultFilePreview(
                                entry = currentEntry,
                                onDecrypt = onDecryptImage,
                                canPrev = canPrevMedia,
                                canNext = canNextMedia,
                                onPrev = {
                                    if (canPrevMedia) {
                                        currentEntryId = mediaPlaylist[currentPlaylistIndex - 1].id
                                    }
                                },
                                onNext = {
                                    if (canNextMedia) {
                                        currentEntryId = mediaPlaylist[currentPlaylistIndex + 1].id
                                    }
                                }
                            )
                        }
                        MimeUtils.isTextPreviewable(currentEntry.mimeType, currentEntry.fileName) -> {
                            TextVaultFilePreview(
                                entry = currentEntry,
                                onDecrypt = onDecryptImage,
                                canPrev = canPrevMedia,
                                canNext = canNextMedia,
                                onPrev = {
                                    if (canPrevMedia) {
                                        currentEntryId = mediaPlaylist[currentPlaylistIndex - 1].id
                                    }
                                },
                                onNext = {
                                    if (canNextMedia) {
                                        currentEntryId = mediaPlaylist[currentPlaylistIndex + 1].id
                                    }
                                }
                            )
                        }
                        else -> {
                            OtherVaultFileView(
                                entry = currentEntry,
                                canPrev = canPrevMedia,
                                canNext = canNextMedia,
                                onPrev = {
                                    if (canPrevMedia) {
                                        currentEntryId = mediaPlaylist[currentPlaylistIndex - 1].id
                                    }
                                },
                                onNext = {
                                    if (canNextMedia) {
                                        currentEntryId = mediaPlaylist[currentPlaylistIndex + 1].id
                                    }
                                }
                            )
                        }
                    }
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
    isAudioOnly: Boolean,
    canPrevMedia: Boolean,
    canNextMedia: Boolean,
    onPrevMedia: () -> Unit,
    onNextMedia: () -> Unit
) {
    val context = LocalContext.current

    // Create the encrypted data source factory
    val dataSourceFactory = remember(vaultUri, key) {
        EncryptedDataSourceFactory(context, vaultUri, key, entries, header)
    }

    // Keep one player instance; swap source when entry changes.
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build()
    }

    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
        }
    }

    LaunchedEffect(entry.id, dataSourceFactory) {
        val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(
                MediaItem.fromUri(Uri.parse("bibvault://entry/${entry.id}"))
            )
        exoPlayer.stop()
        exoPlayer.setMediaSource(mediaSource, true)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    if (isAudioOnly) {
        // Audio-only UI with playback controls
        AudioPlayerUI(
            entry = entry,
            player = exoPlayer,
            canPrevTrack = canPrevMedia,
            canNextTrack = canNextMedia,
            onPrevTrack = onPrevMedia,
            onNextTrack = onNextMedia
        )
    } else {
        VideoPlayerWithGestureControls(
            exoPlayer = exoPlayer,
            canPrevMedia = canPrevMedia,
            canNextMedia = canNextMedia,
            onPrevMedia = onPrevMedia,
            onNextMedia = onNextMedia
        )
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
private fun VideoPlayerWithGestureControls(
    exoPlayer: ExoPlayer,
    canPrevMedia: Boolean,
    canNextMedia: Boolean,
    onPrevMedia: () -> Unit,
    onNextMedia: () -> Unit
) {
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
    var isControllerVisible by remember { mutableStateOf(false) }
    var isLandscape by remember {
        mutableStateOf(activity?.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
    }
    var showSeekSettings by remember { mutableStateOf(false) }
    var seekButtonStepSec by rememberSaveable { mutableIntStateOf(5) }
    var seekButtonStepDraftSec by remember { mutableFloatStateOf(seekButtonStepSec.toFloat()) }
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }

    var screenHeightPx by remember { mutableFloatStateOf(1f) }
    var hud by remember { mutableStateOf<VideoGestureHud>(VideoGestureHud.Hidden) }

    LaunchedEffect(hud) {
        if (hud is VideoGestureHud.Hidden) return@LaunchedEffect
        delay(850)
        hud = VideoGestureHud.Hidden
    }

    DisposableEffect(activity) {
        setFullscreenSystemBarsVisible(activity, visible = false)
        onDispose {
            clearWindowBrightnessOverride(activity)
            restoreSystemBarsVisibility(activity)
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    LaunchedEffect(activity, isControllerVisible) {
        setFullscreenSystemBarsVisible(activity, visible = isControllerVisible)
    }

    fun bindSeekButtons(stepSec: Int) {
        val pv = playerViewRef ?: return
        val stepMs = stepSec * 1000L

        listOf(
            Media3UiR.id.exo_rew,
            Media3UiR.id.exo_rew_with_amount
        ).forEach { id ->
            pv.findViewById<View>(id)?.setOnClickListener {
                val newPos = (exoPlayer.currentPosition - stepMs).coerceAtLeast(0L)
                exoPlayer.seekTo(newPos)
            }
        }
        listOf(
            Media3UiR.id.exo_ffwd,
            Media3UiR.id.exo_ffwd_with_amount
        ).forEach { id ->
            pv.findViewById<View>(id)?.setOnClickListener {
                val dur = exoPlayer.duration
                val maxPos = if (dur > 0) dur else Long.MAX_VALUE
                val newPos = (exoPlayer.currentPosition + stepMs).coerceAtMost(maxPos)
                exoPlayer.seekTo(newPos)
            }
        }
        pv.findViewById<View>(Media3UiR.id.exo_prev)?.setOnClickListener {
            if (canPrevMedia) onPrevMedia()
        }
        pv.findViewById<View>(Media3UiR.id.exo_next)?.setOnClickListener {
            if (canNextMedia) onNextMedia()
        }
        pv.findViewById<View>(Media3UiR.id.exo_prev)?.isEnabled = canPrevMedia
        pv.findViewById<View>(Media3UiR.id.exo_prev)?.alpha = if (canPrevMedia) 1f else 0.45f
        pv.findViewById<View>(Media3UiR.id.exo_next)?.isEnabled = canNextMedia
        pv.findViewById<View>(Media3UiR.id.exo_next)?.alpha = if (canNextMedia) 1f else 0.45f
    }

    LaunchedEffect(playerViewRef, seekButtonStepSec, isControllerVisible, canPrevMedia, canNextMedia) {
        // Controller subviews can be recreated when shown/hidden; re-bind on each change.
        bindSeekButtons(seekButtonStepSec)
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                val dur = exoPlayer.duration
                if (dur > 0) {
                    durationMs = dur
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    LaunchedEffect(exoPlayer) {
        while (true) {
            currentPositionMs = exoPlayer.currentPosition
            val dur = exoPlayer.duration
            if (dur > 0) {
                durationMs = dur
            }
            delay(250)
        }
    }

    val sideZoneFraction = 0.36f
    val dragSensitivity = 1.15f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged {
                screenHeightPx = it.height.toFloat().coerceAtLeast(1f)
            }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = true
                    controllerAutoShow = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    setControllerVisibilityListener(
                        PlayerView.ControllerVisibilityListener { visibility ->
                            isControllerVisible = visibility == View.VISIBLE
                        }
                    )
                    playerViewRef = this
                }
            },
            update = { pv ->
                playerViewRef = pv
                bindSeekButtons(seekButtonStepSec)
            },
            modifier = Modifier.fillMaxSize()
        )

        // Single overlay: drags = brightness / volume / seek; short tap = show PlayerView controller
        val gestureOverlayModifier =
            if (isControllerVisible) {
                Modifier.fillMaxSize()
            } else {
                Modifier
                    .fillMaxSize()
                    .pointerInput(
                        touchSlop,
                        screenHeightPx,
                        sideZoneFraction,
                        dragSensitivity,
                        activity,
                        context,
                        audioManager,
                        maxMusicVolume,
                        exoPlayer
                    ) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
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
                            var dragStartPositionMs = 0L
                            var dragStartDurationMs = 0L
                            var pendingSeekPositionMs = 0L

                            gestureLoop@ while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                val change = event.changes.find { it.id == down.id } ?: break
                                if (change.changedToUpIgnoreConsumed()) {
                                    if (mode == null) {
                                        val dist = hypot(totalDx.toDouble(), totalDy.toDouble()).toFloat()
                                        if (dist < touchSlop) {
                                            playerViewHandle.value?.let { pv ->
                                                if (isControllerVisible) {
                                                    pv.hideController()
                                                } else {
                                                    pv.showController()
                                                }
                                            }
                                        }
                                    } else if (mode == VideoTouchMode.Seeking) {
                                        change.consume()
                                        exoPlayer.seekTo(pendingSeekPositionMs)
                                        hud = VideoGestureHud.Seeking(
                                            positionMs = pendingSeekPositionMs,
                                            durationMs = dragStartDurationMs
                                        )
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
                                                if (ax >= ay) VideoTouchMode.Seeking else null
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
                                            VideoTouchMode.Seeking -> {
                                                val duration = exoPlayer.duration
                                                if (duration > 0L && duration != C.TIME_UNSET) {
                                                    dragStartDurationMs = duration
                                                    dragStartPositionMs = exoPlayer.currentPosition
                                                    pendingSeekPositionMs = dragStartPositionMs
                                                    hud = VideoGestureHud.Seeking(
                                                        positionMs = pendingSeekPositionMs,
                                                        durationMs = dragStartDurationMs
                                                    )
                                                } else {
                                                    mode = null
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
                                    VideoTouchMode.Seeking -> {
                                        change.consume()
                                        val width = size.width.toFloat().coerceAtLeast(1f)
                                        val seekDeltaMs =
                                            (totalDx / width * dragStartDurationMs.toFloat()).toLong()
                                        pendingSeekPositionMs =
                                            (dragStartPositionMs + seekDeltaMs)
                                                .coerceIn(0L, dragStartDurationMs)
                                        hud = VideoGestureHud.Seeking(
                                            positionMs = pendingSeekPositionMs,
                                            durationMs = dragStartDurationMs
                                        )
                                    }
                                    null -> Unit
                                }
                            }
                        }
                    }
            }
        Box(gestureOverlayModifier)

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

        if (isControllerVisible) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 24.dp, end = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = {
                        if (isLandscape) {
                            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            isLandscape = false
                        } else {
                            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            isLandscape = true
                        }
                    },
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.35f), CircleShape)
                ) {
                    Icon(
                        Icons.Default.ScreenRotation,
                        contentDescription = if (isLandscape) "Switch to Portrait" else "Switch to Landscape",
                        tint = Color.White
                    )
                }

                IconButton(
                    onClick = {
                        seekButtonStepDraftSec = seekButtonStepSec.toFloat()
                        showSeekSettings = true
                    },
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.35f), CircleShape)
                ) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "Seek Settings",
                        tint = Color.White
                    )
                }
            }

            Surface(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 14.dp, bottom = 112.dp),
                color = Color.Black.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = "${FormatUtils.formatDuration(currentPositionMs)} / ${
                        if (durationMs > 0) FormatUtils.formatDuration(durationMs) else "--:--"
                    }",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

        }

        if (showSeekSettings) {
            AlertDialog(
                onDismissRequest = { showSeekSettings = false },
                title = { Text("Seek Button Time") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Set rewind/forward step: ${seekButtonStepDraftSec.toInt()} sec")
                        Slider(
                            value = seekButtonStepDraftSec,
                            onValueChange = { seekButtonStepDraftSec = it },
                            valueRange = 1f..30f,
                            steps = 28
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            seekButtonStepSec = seekButtonStepDraftSec.toInt().coerceIn(1, 30)
                            showSeekSettings = false
                        }
                    ) {
                        Text("Apply")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showSeekSettings = false }) {
                        Text("Cancel")
                    }
                }
            )
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
    player: ExoPlayer,
    canPrevTrack: Boolean,
    canNextTrack: Boolean,
    onPrevTrack: () -> Unit,
    onNextTrack: () -> Unit
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

        // Playback controls: previous track, seek -10s, play/pause, +10s, next track
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { if (canPrevTrack) onPrevTrack() },
                enabled = canPrevTrack
            ) {
                Icon(
                    Icons.Default.SkipPrevious,
                    "Previous track",
                    tint = VaultOnSurface,
                    modifier = Modifier.size(36.dp)
                )
            }

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

            IconButton(
                onClick = {
                    val dur = player.duration
                    val maxPos = if (dur > 0) dur else Long.MAX_VALUE
                    player.seekTo((player.currentPosition + 10_000).coerceAtMost(maxPos))
                }
            ) {
                Icon(
                    Icons.Default.Forward10,
                    "Forward 10s",
                    tint = VaultOnSurface,
                    modifier = Modifier.size(36.dp)
                )
            }

            IconButton(
                onClick = { if (canNextTrack) onNextTrack() },
                enabled = canNextTrack
            ) {
                Icon(
                    Icons.Default.SkipNext,
                    "Next track",
                    tint = VaultOnSurface,
                    modifier = Modifier.size(36.dp)
                )
            }
        }
    }
}

@Composable
private fun PdfVaultFilePreview(
    entry: VaultEntry,
    onDecrypt: suspend (VaultEntry) -> ByteArray?,
    canPrev: Boolean,
    canNext: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var pdfHolder by remember { mutableStateOf<PdfPreviewHolder?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var pageIndex by remember { mutableIntStateOf(0) }
    var pageBitmap by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    val holderToDispose = rememberUpdatedState(pdfHolder)

    LaunchedEffect(entry.id) {
        pdfHolder?.close()
        pdfHolder = null
        pageBitmap = null
        pageIndex = 0
        isLoading = true
        error = null
        if (entry.originalSize > PDF_PREVIEW_MAX_BYTES) {
            isLoading = false
            error =
                "Preview is limited to ${FormatUtils.formatFileSize(PDF_PREVIEW_MAX_BYTES)}. Restore the file to view the full document."
            return@LaunchedEffect
        }
        try {
            val bytes = withContext(Dispatchers.IO) {
                onDecrypt(entry)
            }
            if (bytes == null) {
                error = "Could not decrypt file"
            } else {
                val newHolder = withContext(Dispatchers.IO) {
                    writePdfTempAndOpen(context.applicationContext, bytes)
                }
                if (newHolder.pageCount <= 0) {
                    newHolder.close()
                    error = "Could not read PDF pages"
                } else {
                    pdfHolder = newHolder
                }
            }
        } catch (e: Exception) {
            pdfHolder?.close()
            pdfHolder = null
            error = e.message?.takeIf { it.isNotBlank() } ?: "Could not open PDF"
        }
        isLoading = false
    }

    DisposableEffect(Unit) {
        onDispose {
            holderToDispose.value?.close()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        when {
            isLoading -> {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = VaultPrimary)
                }
            }
            error != null -> {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = error!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = VaultError
                    )
                }
            }
            pdfHolder != null -> {
                val holder = pdfHolder!!
                val pageCount = holder.pageCount
                val pdfScroll = rememberScrollState()
                LaunchedEffect(pageIndex) {
                    pdfScroll.scrollTo(0)
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    BoxWithConstraints(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp)
                    ) {
                        val widthPx = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
                        LaunchedEffect(holder, pageIndex, widthPx) {
                            pageBitmap = withContext(Dispatchers.Default) {
                                holder.renderPage(pageIndex, widthPx)?.asImageBitmap()
                            }
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(pdfScroll)
                        ) {
                            val bm = pageBitmap
                            if (bm != null) {
                                Image(
                                    bitmap = bm,
                                    contentDescription = "PDF page ${pageIndex + 1}",
                                    modifier = Modifier.fillMaxWidth(),
                                    contentScale = ContentScale.FillWidth
                                )
                            }
                        }
                    }
                    if (pageCount > 1) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp, bottom = 4.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = { if (pageIndex > 0) pageIndex-- },
                                enabled = pageIndex > 0
                            ) {
                                Icon(
                                    Icons.Default.ChevronLeft,
                                    "Previous page",
                                    tint = Color.White
                                )
                            }
                            Text(
                                text = "${pageIndex + 1} / $pageCount",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White
                            )
                            IconButton(
                                onClick = { if (pageIndex < pageCount - 1) pageIndex++ },
                                enabled = pageIndex < pageCount - 1
                            ) {
                                Icon(
                                    Icons.Default.ChevronRight,
                                    "Next page",
                                    tint = Color.White
                                )
                            }
                        }
                    }
                }
            }
        }
        if (canPrev || canNext) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onPrev, enabled = canPrev) {
                    Icon(
                        Icons.Default.SkipPrevious,
                        "Previous file",
                        tint = Color.White,
                        modifier = Modifier.size(40.dp)
                    )
                }
                IconButton(onClick = onNext, enabled = canNext) {
                    Icon(
                        Icons.Default.SkipNext,
                        "Next file",
                        tint = Color.White,
                        modifier = Modifier.size(40.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun MarkdownVaultFilePreview(
    entry: VaultEntry,
    onDecrypt: suspend (VaultEntry) -> ByteArray?,
    canPrev: Boolean,
    canNext: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit
) {
    var docHtml by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(entry.id) {
        isLoading = true
        error = null
        docHtml = null
        if (entry.originalSize > TEXT_PREVIEW_MAX_BYTES) {
            isLoading = false
            error =
                "Preview is limited to ${FormatUtils.formatFileSize(TEXT_PREVIEW_MAX_BYTES)}. Restore the file to view the full document."
            return@LaunchedEffect
        }
        try {
            val bytes = withContext(Dispatchers.IO) {
                onDecrypt(entry)
            }
            if (bytes == null) {
                error = "Could not decrypt file"
            } else {
                val md = decodeUtf8WithReplacement(bytes)
                docHtml = withContext(Dispatchers.Default) {
                    wrapMarkdownHtml(markdownToHtml(md))
                }
            }
        } catch (e: Exception) {
            error = e.message?.takeIf { it.isNotBlank() } ?: "Error loading file"
        }
        isLoading = false
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        when {
            isLoading -> {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = VaultPrimary)
                }
            }
            error != null -> {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = error!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = VaultError
                    )
                }
            }
            docHtml != null -> {
                key(docHtml!!) {
                    AndroidView(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                settings.javaScriptEnabled = false
                            }
                        },
                        update = { wv ->
                            wv.loadDataWithBaseURL(null, docHtml!!, "text/html", "UTF-8", null)
                        }
                    )
                }
            }
        }
        if (canPrev || canNext) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onPrev, enabled = canPrev) {
                    Icon(
                        Icons.Default.SkipPrevious,
                        "Previous file",
                        tint = Color.White,
                        modifier = Modifier.size(40.dp)
                    )
                }
                IconButton(onClick = onNext, enabled = canNext) {
                    Icon(
                        Icons.Default.SkipNext,
                        "Next file",
                        tint = Color.White,
                        modifier = Modifier.size(40.dp)
                    )
                }
            }
        }
    }
}

/**
 * Scrollable UTF-8 text preview for text-like vault entries (decrypted in memory only).
 */
@Composable
private fun TextVaultFilePreview(
    entry: VaultEntry,
    onDecrypt: suspend (VaultEntry) -> ByteArray?,
    canPrev: Boolean,
    canNext: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit
) {
    var textContent by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(entry.id) {
        isLoading = true
        error = null
        textContent = null
        if (entry.originalSize > TEXT_PREVIEW_MAX_BYTES) {
            isLoading = false
            error =
                "Preview is limited to ${FormatUtils.formatFileSize(TEXT_PREVIEW_MAX_BYTES)}. Restore the file to view the full contents."
            return@LaunchedEffect
        }
        try {
            val bytes = withContext(Dispatchers.IO) {
                onDecrypt(entry)
            }
            if (bytes == null) {
                error = "Could not decrypt file"
            } else {
                textContent = decodeUtf8WithReplacement(bytes)
            }
        } catch (e: Exception) {
            error = e.message?.takeIf { it.isNotBlank() } ?: "Error loading file"
        }
        isLoading = false
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        when {
            isLoading -> {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = VaultPrimary)
                }
            }
            error != null -> {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = error!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = VaultError
                    )
                }
            }
            textContent != null -> {
                val scroll = rememberScrollState()
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = Color.White.copy(alpha = 0.08f)
                ) {
                    Text(
                        text = textContent!!,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            color = Color.White.copy(alpha = 0.92f)
                        ),
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scroll)
                            .padding(16.dp)
                    )
                }
            }
        }
        if (canPrev || canNext) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onPrev, enabled = canPrev) {
                    Icon(
                        Icons.Default.SkipPrevious,
                        "Previous file",
                        tint = Color.White,
                        modifier = Modifier.size(40.dp)
                    )
                }
                IconButton(onClick = onNext, enabled = canNext) {
                    Icon(
                        Icons.Default.SkipNext,
                        "Next file",
                        tint = Color.White,
                        modifier = Modifier.size(40.dp)
                    )
                }
            }
        }
    }
}

/**
 * Vault entry that is not image/video/audio and is not text-previewable: no in-app preview.
 */
@Composable
private fun OtherVaultFileView(
    entry: VaultEntry,
    canPrev: Boolean,
    canNext: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.InsertDriveFile,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.85f),
            modifier = Modifier.size(96.dp)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = entry.fileName,
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            fontWeight = FontWeight.Medium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "${MimeUtils.getTypeLabel(entry.mimeType)} • ${FormatUtils.formatFileSize(entry.originalSize)}",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.65f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Preview is not available. Restore this file from the vault browser to open it with another app.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.55f)
        )
        if (canPrev || canNext) {
            Spacer(modifier = Modifier.height(32.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onPrev, enabled = canPrev) {
                    Icon(
                        Icons.Default.SkipPrevious,
                        "Previous file",
                        tint = Color.White,
                        modifier = Modifier.size(40.dp)
                    )
                }
                IconButton(onClick = onNext, enabled = canNext) {
                    Icon(
                        Icons.Default.SkipNext,
                        "Next file",
                        tint = Color.White,
                        modifier = Modifier.size(40.dp)
                    )
                }
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
    onDecryptImage: suspend (VaultEntry) -> ByteArray?,
    showNavButtons: Boolean,
    onToggleChrome: () -> Unit,
    canSwipePrev: Boolean,
    canSwipeNext: Boolean,
    onSwipePrev: () -> Unit,
    onSwipeNext: () -> Unit
) {
    var imageBytes by remember { mutableStateOf<ByteArray?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    // Zoom/pan state
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var rotationDeg by remember { mutableFloatStateOf(0f) }
    val swipeThreshold = 90f
    var horizontalDragAccumulator by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(entry.id) {
        isLoading = true
        imageBytes = null
        error = null
        scale = 1f
        offset = Offset.Zero
        rotationDeg = 0f
        horizontalDragAccumulator = 0f
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
                    Box(modifier = Modifier.fillMaxSize()) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = entry.fileName,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer(
                                    scaleX = scale,
                                    scaleY = scale,
                                    rotationZ = rotationDeg,
                                    translationX = offset.x,
                                    translationY = offset.y
                                )
                                .pointerInput(entry.id) {
                                    detectTapGestures(
                                        onTap = { onToggleChrome() }
                                    )
                                }
                                .pointerInput(entry.id, scale, canSwipePrev, canSwipeNext) {
                                    detectHorizontalDragGestures(
                                        onHorizontalDrag = { change, dragAmount ->
                                            if (scale <= 1.05f) {
                                                horizontalDragAccumulator += dragAmount
                                                change.consume()
                                            }
                                        },
                                        onDragCancel = {
                                            horizontalDragAccumulator = 0f
                                        },
                                        onDragEnd = {
                                            if (scale <= 1.05f) {
                                                when {
                                                    horizontalDragAccumulator >= swipeThreshold && canSwipePrev -> onSwipePrev()
                                                    horizontalDragAccumulator <= -swipeThreshold && canSwipeNext -> onSwipeNext()
                                                }
                                            }
                                            horizontalDragAccumulator = 0f
                                        }
                                    )
                                }
                                .pointerInput(Unit) {
                                    detectTransformGestures { _, pan, zoom, _ ->
                                        val newScale = (scale * zoom).coerceIn(1f, 5f)
                                        scale = newScale
                                        offset = if (newScale <= 1.01f) {
                                            Offset.Zero
                                        } else {
                                            Offset(
                                                x = offset.x + pan.x,
                                                y = offset.y + pan.y
                                            )
                                        }
                                    }
                                }
                        )

                        if (showNavButtons) {
                            FilledIconButton(
                                onClick = {
                                    rotationDeg = (rotationDeg - 90f + 360f) % 360f
                                },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(top = 14.dp, end = 14.dp)
                                    .size(44.dp),
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = Color.Black.copy(alpha = 0.45f),
                                    contentColor = Color.White
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.RotateLeft,
                                    contentDescription = "Rotate image 90 degrees anticlockwise"
                                )
                            }
                        }

                        // Prev/Next overlay buttons (work even if swipe doesn't)
                        if (showNavButtons && canSwipePrev) {
                            FilledIconButton(
                                onClick = onSwipePrev,
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .padding(start = 14.dp)
                                    .size(44.dp),
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = Color.Black.copy(alpha = 0.45f),
                                    contentColor = Color.White
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Previous image"
                                )
                            }
                        }

                        if (showNavButtons && canSwipeNext) {
                            FilledIconButton(
                                onClick = onSwipeNext,
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .padding(end = 14.dp)
                                    .size(44.dp),
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = Color.Black.copy(alpha = 0.45f),
                                    contentColor = Color.White
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Next image",
                                    modifier = Modifier.graphicsLayer(rotationZ = 180f)
                                )
                            }
                        }
                    }
                } else {
                    Text("Could not decode image", color = VaultError)
                }
            }
        }
    }
}
