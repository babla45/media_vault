package com.example.bib_vault.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.bib_vault.ui.theme.*
import com.example.bib_vault.util.FormatUtils
import com.example.bib_vault.util.MediaType
import com.example.bib_vault.util.MimeUtils
import com.example.bib_vault.vault.VaultEntry

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
    onDeleteFile: (VaultEntry) -> Unit,
    onAddFiles: () -> Unit,
    onLock: () -> Unit
) {
    var selectedFilter by remember { mutableStateOf(FilterType.ALL) }
    var showDeleteDialog by remember { mutableStateOf<VaultEntry?>(null) }

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
                    IconButton(onClick = onAddFiles) {
                        Icon(Icons.Default.Add, "Add files", tint = VaultPrimaryLight)
                    }
                    IconButton(onClick = onLock) {
                        Icon(Icons.Default.LockOpen, "Lock vault", tint = VaultError)
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
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(filteredEntries, key = { it.id }) { entry ->
                        VaultFileCard(
                            entry = entry,
                            onClick = { onFileClick(entry) },
                            onLongClick = { showDeleteDialog = entry }
                        )
                    }
                }
            }
        }
    }

    // Delete confirmation dialog
    showDeleteDialog?.let { entry ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            shape = RoundedCornerShape(20.dp),
            containerColor = VaultSurface,
            icon = { Icon(Icons.Default.Delete, null, tint = VaultError) },
            title = { Text("Remove File") },
            text = {
                Text("Remove \"${entry.fileName}\" from the vault? This cannot be undone.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteFile(entry)
                        showDeleteDialog = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = VaultError)
                ) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
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
            .aspectRatio(0.85f)
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(16.dp),
        color = VaultSurface
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
                Icon(
                    typeIcon,
                    contentDescription = null,
                    tint = typeColor.copy(alpha = 0.7f),
                    modifier = Modifier.size(48.dp)
                )

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
