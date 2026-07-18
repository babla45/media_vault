package com.example.bib_vault.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.bib_vault.ui.theme.VaultPrimary
import com.example.bib_vault.util.VaultFileFinder

@Composable
fun FoundVaultsDialog(
    vaults: List<VaultFileFinder.FoundVault>,
    isScanning: Boolean,
    onSelect: (VaultFileFinder.FoundVault) -> Unit,
    onBrowseManually: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!isScanning) onDismiss() },
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        icon = {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = VaultPrimary,
                modifier = Modifier.size(28.dp)
            )
        },
        title = {
            Text(
                text = if (isScanning) "Searching for vaults…" else "Found vaults",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            when {
                isScanning -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = VaultPrimary
                        )
                        Text(
                            text = "Scanning storage for .biv files…",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                vaults.isEmpty() -> {
                    Text(
                        text = "No vault files were found. You can browse manually instead.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                    ) {
                        items(vaults, key = { it.file.absolutePath }) { vault ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(vault) }
                                    .padding(vertical = 12.dp)
                            ) {
                                Text(
                                    text = vault.displayName,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = vault.parentPath,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onBrowseManually,
                enabled = !isScanning
            ) {
                Text("Browse manually")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isScanning
            ) {
                Text("Cancel")
            }
        }
    )
}
