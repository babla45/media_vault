package com.example.bib_vault.ui.screens

import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.bib_vault.state.VaultProgress
import com.example.bib_vault.ui.theme.*
import com.example.bib_vault.util.FormatUtils
import com.example.bib_vault.util.MimeUtils
import com.example.bib_vault.util.MediaType

/**
 * Screen for selecting files and creating a new vault.
 * Shows selected files, allows adding/removing, and initiates encryption.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultCreationScreen(
    selectedFiles: List<Pair<String, Long>>, // (name, size)
    progress: VaultProgress,
    isProcessing: Boolean,
    onAddFiles: () -> Unit,
    onRemoveFile: (Int) -> Unit,
    onCreateVault: () -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Create Vault",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !isProcessing) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = VaultBackground,
                    titleContentColor = VaultOnBackground
                )
            )
        },
        containerColor = VaultBackground,
        floatingActionButton = {
            if (!isProcessing) {
                ExtendedFloatingActionButton(
                    onClick = onAddFiles,
                    containerColor = VaultPrimary,
                    contentColor = VaultOnPrimary,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.Add, "Add files")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Add Files")
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            // File count summary
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = VaultSurface,
                border = ButtonDefaults.outlinedButtonBorder(enabled = true)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Folder,
                        contentDescription = null,
                        tint = VaultPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "${selectedFiles.size} file${if (selectedFiles.size != 1) "s" else ""} selected",
                            style = MaterialTheme.typography.titleMedium,
                            color = VaultOnSurface
                        )
                        Text(
                            "Total: ${FormatUtils.formatFileSize(selectedFiles.sumOf { it.second })}",
                            style = MaterialTheme.typography.bodySmall,
                            color = VaultOnSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Progress indicator
            AnimatedVisibility(visible = isProcessing) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = VaultSurface
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                progress.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = VaultOnSurface
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { progress.fraction },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = VaultPrimary,
                                trackColor = VaultSurfaceLight
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "${progress.currentFile} / ${progress.totalFiles}",
                                style = MaterialTheme.typography.labelSmall,
                                color = VaultOnSurfaceVariant
                            )
                        }
                    }
                }
            }

            // File list
            if (selectedFiles.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.CloudUpload,
                            contentDescription = null,
                            tint = VaultOnSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "No files selected",
                            style = MaterialTheme.typography.titleMedium,
                            color = VaultOnSurfaceVariant.copy(alpha = 0.6f)
                        )
                        Text(
                            "Tap + to add media files",
                            style = MaterialTheme.typography.bodyMedium,
                            color = VaultOnSurfaceVariant.copy(alpha = 0.4f)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(selectedFiles) { index, (name, size) ->
                        FileListItem(
                            fileName = name,
                            fileSize = size,
                            onRemove = { onRemoveFile(index) },
                            enabled = !isProcessing
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Create button
            Button(
                onClick = onCreateVault,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                enabled = selectedFiles.isNotEmpty() && !isProcessing,
                colors = ButtonDefaults.buttonColors(containerColor = VaultPrimary),
                shape = RoundedCornerShape(16.dp)
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = VaultOnPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Encrypting...")
                } else {
                    Icon(Icons.Default.Lock, null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Encrypt & Create Vault",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun FileListItem(
    fileName: String,
    fileSize: Long,
    onRemove: () -> Unit,
    enabled: Boolean
) {
    val mediaType = MimeUtils.getMediaType(MimeUtils.guessMimeType(fileName))
    val typeColor = when (mediaType) {
        MediaType.VIDEO -> VideoColor
        MediaType.AUDIO -> AudioColor
        MediaType.IMAGE -> ImageColor
        MediaType.OTHER -> VaultOnSurfaceVariant
    }
    val typeIcon = when (mediaType) {
        MediaType.VIDEO -> Icons.Default.Videocam
        MediaType.AUDIO -> Icons.Default.MusicNote
        MediaType.IMAGE -> Icons.Default.Image
        MediaType.OTHER -> Icons.Default.InsertDriveFile
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = VaultSurface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Type icon
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(typeColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(typeIcon, null, tint = typeColor, modifier = Modifier.size(20.dp))
            }

            Spacer(modifier = Modifier.width(12.dp))

            // File info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = VaultOnSurface,
                    maxLines = 1
                )
                Text(
                    "${MimeUtils.getTypeLabel(MimeUtils.guessMimeType(fileName))} • ${FormatUtils.formatFileSize(fileSize)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = VaultOnSurfaceVariant
                )
            }

            // Remove button
            IconButton(onClick = onRemove, enabled = enabled) {
                Icon(
                    Icons.Default.Close,
                    "Remove",
                    tint = VaultError.copy(alpha = 0.7f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
