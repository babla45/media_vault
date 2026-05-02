package com.example.bib_vault.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.lazy.itemsIndexed as lazyItemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.example.bib_vault.ui.theme.*
import com.example.bib_vault.util.FormatUtils
import com.example.bib_vault.util.MediaType
import com.example.bib_vault.util.MimeUtils
import com.example.bib_vault.vault.VaultEntry
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext

/**
 * Gallery-style vault file browser shown after successful unlock.
 * Supports filtering by media type, grid display, and file actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultBrowserScreen(
    vaultName: String,
    entries: List<VaultEntry>,
    onFileClick: (VaultEntry) -> Unit,
    onDeleteFiles: (List<VaultEntry>) -> Unit,
    onRestoreFiles: (List<VaultEntry>) -> Unit,
    onAddFiles: () -> Unit,
    onLoadPreviewBytes: suspend (VaultEntry) -> ByteArray?,
    onLock: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences("vault_browser_settings", android.content.Context.MODE_PRIVATE)
    }

    val savedFilterOrdinal = remember {
        prefs.getInt("selected_filter_ordinal", FilterType.ALL.ordinal)
            .coerceIn(0, FilterType.entries.lastIndex)
    }
    val savedShowListView = remember { prefs.getBoolean("show_list_view", true) }
    val savedListNamesOnly = remember { prefs.getBoolean("list_names_only", true) }
    val savedPreviewsEnabled = remember { prefs.getBoolean("previews_enabled", true) }

    var selectedFilter by rememberSaveable {
        mutableStateOf(FilterType.entries[savedFilterOrdinal])
    }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showRestoreDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showListView by rememberSaveable { mutableStateOf(savedShowListView) }
    var listNamesOnly by rememberSaveable { mutableStateOf(savedListNamesOnly) }
    var previewsEnabled by rememberSaveable { mutableStateOf(savedPreviewsEnabled) }
    val previewCache = remember { mutableStateMapOf<String, Bitmap?>() }
    val selectedIds = remember { mutableStateListOf<String>() }
    val isSelectionMode = selectedIds.isNotEmpty()
    val namesOnlyListState = rememberLazyListState()

    LaunchedEffect(selectedFilter, showListView, listNamesOnly, previewsEnabled) {
        prefs.edit()
            .putInt("selected_filter_ordinal", selectedFilter.ordinal)
            .putBoolean("show_list_view", showListView)
            .putBoolean("list_names_only", listNamesOnly)
            .putBoolean("previews_enabled", previewsEnabled)
            .apply()
    }

    LaunchedEffect(entries, previewsEnabled) {
        if (!previewsEnabled) {
            previewCache.clear()
            return@LaunchedEffect
        }
        val previewableEntries = entries.filter { it.isImage || it.isVideo }
        supervisorScope {
            previewableEntries
                .filter { !previewCache.containsKey(it.id) }
                .map { entry ->
                    async(Dispatchers.IO) {
                        val bytes = onLoadPreviewBytes(entry)
                        val mediaType = MimeUtils.getMediaType(entry.mimeType)
                        val bitmap = when (mediaType) {
                            MediaType.IMAGE ->
                                bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                            MediaType.VIDEO ->
                                bytes?.let { extractVideoFrame(it) }
                            else -> null
                        }
                        entry.id to bitmap
                    }
                }
                .forEach { deferred ->
                    val (id, bitmap) = deferred.await()
                    previewCache[id] = bitmap
                }
        }
    }

    val filteredEntries = remember(entries, selectedFilter) {
        when (selectedFilter) {
            FilterType.ALL -> entries
            FilterType.VIDEO -> entries.filter { it.isVideo }
            FilterType.AUDIO -> entries.filter { it.isAudio }
            FilterType.IMAGE -> entries.filter { it.isImage }
        }
    }

    // Counts for filter badges
    val videosCount = remember(entries) { entries.count { it.isVideo } }
    val audiosCount = remember(entries) { entries.count { it.isAudio } }
    val imagesCount = remember(entries) { entries.count { it.isImage } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            vaultName,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${entries.size} file${if (entries.size != 1) "s" else ""} • ${FormatUtils.formatFileSize(entries.sumOf { it.originalSize })}",
                            style = MaterialTheme.typography.bodySmall,
                            color = VaultOnSurfaceVariant
                        )
                    }
                },
                actions = {
                    if (isSelectionMode) {
                        val allFilteredSelected = filteredEntries.isNotEmpty() &&
                            filteredEntries.all { selectedIds.contains(it.id) }
                        IconButton(
                            onClick = {
                                if (allFilteredSelected) {
                                    selectedIds.removeAll(filteredEntries.map { it.id }.toSet())
                                } else {
                                    filteredEntries.forEach { if (!selectedIds.contains(it.id)) selectedIds.add(it.id) }
                                }
                            }
                        ) {
                            Icon(
                                if (allFilteredSelected) Icons.Default.Deselect else Icons.Default.SelectAll,
                                if (allFilteredSelected) "Clear selected in tab" else "Select all in tab",
                                tint = VaultPrimaryLight
                            )
                        }
                        IconButton(onClick = { showRestoreDialog = true }) {
                            Icon(Icons.Default.Restore, "Restore selected", tint = VaultPrimaryLight)
                        }
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(Icons.Default.Delete, "Delete selected", tint = VaultError)
                        }
                        IconButton(onClick = { selectedIds.clear() }) {
                            Icon(Icons.Default.Close, "Cancel selection", tint = VaultOnSurfaceVariant)
                        }
                    } else {
                        IconButton(onClick = onAddFiles) {
                            Icon(Icons.Default.Add, "Add files", tint = VaultPrimaryLight)
                        }
                        IconButton(onClick = { showSettingsDialog = true }) {
                            Icon(Icons.Default.Settings, "Settings", tint = VaultOnSurfaceVariant)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = VaultBackground,
                    titleContentColor = VaultOnBackground
                )
            )
        },
        containerColor = VaultBackground
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Filter tabs
            ScrollableTabRow(
                selectedTabIndex = selectedFilter.ordinal,
                containerColor = VaultBackground,
                contentColor = VaultPrimary,
                edgePadding = 16.dp,
                divider = {}
            ) {
                FilterTab("All", entries.size, selectedFilter == FilterType.ALL) {
                    selectedFilter = FilterType.ALL
                }
                FilterTab("Video", videosCount, selectedFilter == FilterType.VIDEO) {
                    selectedFilter = FilterType.VIDEO
                }
                FilterTab("Audio", audiosCount, selectedFilter == FilterType.AUDIO) {
                    selectedFilter = FilterType.AUDIO
                }
                FilterTab("Image", imagesCount, selectedFilter == FilterType.IMAGE) {
                    selectedFilter = FilterType.IMAGE
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (filteredEntries.isEmpty()) {
                // Empty state
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.FolderOpen,
                            contentDescription = null,
                            tint = VaultOnSurfaceVariant.copy(alpha = 0.3f),
                            modifier = Modifier.size(72.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = if (selectedFilter == FilterType.ALL) "No files in vault"
                            else "No ${selectedFilter.name.lowercase()} files",
                            style = MaterialTheme.typography.titleMedium,
                            color = VaultOnSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            } else {
                // File grid
                if (showListView) {
                    if (listNamesOnly) {
                        LazyColumn(
                            state = namesOnlyListState,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier.pointerInput(filteredEntries, isSelectionMode) {
                                if (!isSelectionMode || filteredEntries.isEmpty()) return@pointerInput
                                var anchorIndex = -1
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { offset ->
                                        val hit = namesOnlyListState.layoutInfo.visibleItemsInfo
                                            .firstOrNull { info ->
                                                offset.y >= info.offset && offset.y <= info.offset + info.size
                                            } ?: return@detectDragGesturesAfterLongPress
                                        anchorIndex = hit.index
                                        filteredEntries.getOrNull(anchorIndex)?.let { entry ->
                                            if (!selectedIds.contains(entry.id)) selectedIds.add(entry.id)
                                        }
                                    },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        if (anchorIndex < 0) return@detectDragGesturesAfterLongPress
                                        val hit = namesOnlyListState.layoutInfo.visibleItemsInfo
                                            .firstOrNull { info ->
                                                val y = change.position.y
                                                y >= info.offset && y <= info.offset + info.size
                                            } ?: return@detectDragGesturesAfterLongPress
                                        val current = hit.index
                                        val start = minOf(anchorIndex, current)
                                        val end = maxOf(anchorIndex, current)
                                        for (i in start..end) {
                                            filteredEntries.getOrNull(i)?.let { entry ->
                                                if (!selectedIds.contains(entry.id)) selectedIds.add(entry.id)
                                            }
                                        }
                                    }
                                )
                            }
                        ) {
                            lazyItemsIndexed(filteredEntries, key = { _, it -> it.id }) { index, entry ->
                                VaultFileNameRow(
                                    index = index + 1,
                                    entry = entry,
                                    isSelected = selectedIds.contains(entry.id),
                                    onClick = {
                                        if (isSelectionMode) {
                                            if (selectedIds.contains(entry.id)) selectedIds.remove(entry.id)
                                            else selectedIds.add(entry.id)
                                        } else {
                                            onFileClick(entry)
                                        }
                                    },
                                    onLongClick = {
                                        if (selectedIds.contains(entry.id)) selectedIds.remove(entry.id)
                                        else selectedIds.add(entry.id)
                                    }
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            lazyItems(filteredEntries, key = { it.id }) { entry ->
                                VaultFileCard(
                                    entry = entry,
                                    previewsEnabled = previewsEnabled,
                                    previewBitmap = previewCache[entry.id],
                                    useListLayout = true,
                                    isSelected = selectedIds.contains(entry.id),
                                    onClick = {
                                        if (isSelectionMode) {
                                            if (selectedIds.contains(entry.id)) selectedIds.remove(entry.id)
                                            else selectedIds.add(entry.id)
                                        } else {
                                            onFileClick(entry)
                                        }
                                    },
                                    onLongClick = {
                                        if (selectedIds.contains(entry.id)) selectedIds.remove(entry.id)
                                        else selectedIds.add(entry.id)
                                    }
                                )
                            }
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        gridItems(filteredEntries, key = { it.id }) { entry ->
                            VaultFileCard(
                                entry = entry,
                                previewsEnabled = previewsEnabled,
                                previewBitmap = previewCache[entry.id],
                                useListLayout = false,
                                isSelected = selectedIds.contains(entry.id),
                                onClick = {
                                    if (isSelectionMode) {
                                        if (selectedIds.contains(entry.id)) selectedIds.remove(entry.id)
                                        else selectedIds.add(entry.id)
                                    } else {
                                        onFileClick(entry)
                                    }
                                },
                                onLongClick = {
                                    if (selectedIds.contains(entry.id)) selectedIds.remove(entry.id)
                                    else selectedIds.add(entry.id)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            shape = RoundedCornerShape(20.dp),
            containerColor = VaultSurface,
            title = { Text("Vault Settings") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("List view")
                        Switch(
                            checked = showListView,
                            onCheckedChange = { showListView = it }
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Show media preview")
                        Switch(
                            checked = previewsEnabled,
                            onCheckedChange = { previewsEnabled = it }
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("List: names only (no preview)")
                        Switch(
                            checked = listNamesOnly,
                            enabled = showListView,
                            onCheckedChange = { listNamesOnly = it }
                        )
                    }
                    Text(
                        text = if (showListView) "Showing files in list" else "Showing files in grid (2 per row)",
                        style = MaterialTheme.typography.bodySmall,
                        color = VaultOnSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = {
                            showSettingsDialog = false
                            onLock()
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = VaultError)
                    ) {
                        Icon(Icons.Default.Lock, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Lock Vault")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSettingsDialog = false }) {
                    Text("Done")
                }
            }
        )
    }

    // Delete confirmation dialog
    if (showDeleteDialog && selectedIds.isNotEmpty()) {
        val selectedEntries = entries.filter { selectedIds.contains(it.id) }
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            shape = RoundedCornerShape(20.dp),
            containerColor = VaultSurface,
            icon = { Icon(Icons.Default.Delete, null, tint = VaultError) },
            title = { Text("Remove ${selectedEntries.size} file(s)") },
            text = {
                Text("Remove ${selectedEntries.size} selected file(s) from the vault? This cannot be undone.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteFiles(selectedEntries)
                        selectedIds.clear()
                        showDeleteDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = VaultError)
                ) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Restore confirmation dialog
    if (showRestoreDialog && selectedIds.isNotEmpty()) {
        val selectedEntries = entries.filter { selectedIds.contains(it.id) }
        AlertDialog(
            onDismissRequest = { showRestoreDialog = false },
            shape = RoundedCornerShape(20.dp),
            containerColor = VaultSurface,
            icon = { Icon(Icons.Default.Restore, null, tint = VaultPrimaryLight) },
            title = { Text("Restore ${selectedEntries.size} file(s)") },
            text = {
                Text("Restore ${selectedEntries.size} selected file(s) to the same folder as this vault?")
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRestoreFiles(selectedEntries)
                        selectedIds.clear()
                        showRestoreDialog = false
                    }
                ) {
                    Text("Restore")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VaultFileCard(
    entry: VaultEntry,
    previewsEnabled: Boolean,
    previewBitmap: Bitmap?,
    useListLayout: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val mediaType = MimeUtils.getMediaType(entry.mimeType)
    val typeColor = when (mediaType) {
        MediaType.VIDEO -> VideoColor
        MediaType.AUDIO -> AudioColor
        MediaType.IMAGE -> ImageColor
        MediaType.OTHER -> VaultOnSurfaceVariant
    }
    val typeIcon = when (mediaType) {
        MediaType.VIDEO -> Icons.Default.PlayCircle
        MediaType.AUDIO -> Icons.Default.MusicNote
        MediaType.IMAGE -> Icons.Default.Image
        MediaType.OTHER -> Icons.Default.InsertDriveFile
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(if (useListLayout) 2.1f else 0.85f)
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(16.dp),
        color = if (isSelected) VaultPrimary.copy(alpha = 0.22f) else VaultSurface
    ) {
        Column {
            // Media type visual area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                typeColor.copy(alpha = 0.15f),
                                typeColor.copy(alpha = 0.05f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (previewBitmap != null && (mediaType == MediaType.IMAGE || mediaType == MediaType.VIDEO)) {
                    Image(
                        bitmap = previewBitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        typeIcon,
                        contentDescription = null,
                        tint = typeColor.copy(alpha = 0.7f),
                        modifier = Modifier.size(48.dp)
                    )
                }

                // Extension badge
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(typeColor.copy(alpha = 0.2f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = FormatUtils.getExtension(entry.fileName),
                        style = MaterialTheme.typography.labelSmall,
                        color = typeColor,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // File info
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                Text(
                    text = entry.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = VaultOnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = FormatUtils.formatFileSize(entry.originalSize),
                        style = MaterialTheme.typography.bodySmall,
                        color = VaultOnSurfaceVariant
                    )
                    Text(
                        text = MimeUtils.getTypeLabel(entry.mimeType),
                        style = MaterialTheme.typography.labelSmall,
                        color = typeColor
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterTab(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit
) {
    Tab(
        selected = selected,
        onClick = onClick,
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(label)
                if (count > 0) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (selected) VaultPrimary.copy(alpha = 0.2f)
                        else VaultSurfaceLight
                    ) {
                        Text(
                            text = "$count",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) VaultPrimaryLight
                            else VaultOnSurfaceVariant
                        )
                    }
                }
            }
        },
        selectedContentColor = VaultPrimaryLight,
        unselectedContentColor = VaultOnSurfaceVariant
    )
}

private enum class FilterType { ALL, VIDEO, AUDIO, IMAGE }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VaultFileNameRow(
    index: Int,
    entry: VaultEntry,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(8.dp),
        color = if (isSelected) VaultPrimary.copy(alpha = 0.22f) else VaultSurface
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = "$index. ${entry.fileName}",
                style = MaterialTheme.typography.bodySmall,
                color = VaultOnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private class ByteArrayMediaDataSource(
    private val data: ByteArray
) : MediaDataSource() {
    override fun getSize(): Long = data.size.toLong()

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= data.size) return -1
        val length = minOf(size, data.size - position.toInt())
        System.arraycopy(data, position.toInt(), buffer, offset, length)
        return length
    }

    override fun close() = Unit
}

private fun extractVideoFrame(videoBytes: ByteArray): Bitmap? {
    return try {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(ByteArrayMediaDataSource(videoBytes))
        val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        retriever.release()
        frame
    } catch (_: Exception) {
        null
    }
}
