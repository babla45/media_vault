package com.example.bib_vault.ui.screens

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.bib_vault.AppPreferences
import com.example.bib_vault.SecretCodeReceiver
import com.example.bib_vault.ui.theme.VaultBackground
import com.example.bib_vault.ui.theme.VaultOnBackground
import com.example.bib_vault.ui.theme.VaultPrimary
import com.example.bib_vault.util.VaultSaveLocation

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val packageManager = context.packageManager
    val prefs = AppPreferences.prefs(context)

    val bivAliasName = ComponentName(context, context.packageName + ".BivFileAlias")
    val launcherAliasName = ComponentName(context, context.packageName + ".LauncherAlias")

    var isBivEnabled by remember {
        mutableStateOf(prefs.getBoolean(AppPreferences.KEY_BIV_ENABLED, true))
    }

    var isIconHidden by remember {
        mutableStateOf(prefs.getBoolean(AppPreferences.KEY_ICON_HIDDEN, false))
    }

    var lockOnBackground by remember {
        mutableStateOf(prefs.getBoolean(AppPreferences.KEY_LOCK_ON_BACKGROUND, true))
    }

    var autoFindVaults by remember {
        mutableStateOf(prefs.getBoolean(AppPreferences.KEY_AUTO_FIND_VAULTS, false))
    }

    var customVaultSaveEnabled by remember {
        mutableStateOf(AppPreferences.isCustomVaultSaveEnabled(context))
    }

    var customVaultSaveUri by remember {
        mutableStateOf(AppPreferences.getCustomVaultSaveUri(context))
    }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: SecurityException) {
            }
            AppPreferences.setCustomVaultSaveUri(context, uri)
            customVaultSaveUri = uri
            if (!customVaultSaveEnabled) {
                customVaultSaveEnabled = true
                AppPreferences.setCustomVaultSaveEnabled(context, true)
            }
        } else if (customVaultSaveUri == null) {
            customVaultSaveEnabled = false
            AppPreferences.setCustomVaultSaveEnabled(context, false)
        }
    }

    fun toggleBiv(enabled: Boolean) {
        isBivEnabled = enabled
        prefs.edit().putBoolean(AppPreferences.KEY_BIV_ENABLED, enabled).apply()
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        try {
            packageManager.setComponentEnabledSetting(
                bivAliasName,
                state,
                PackageManager.DONT_KILL_APP
            )
        } catch (_: Exception) {}
    }

    fun toggleIcon(hidden: Boolean) {
        isIconHidden = hidden
        prefs.edit().putBoolean(AppPreferences.KEY_ICON_HIDDEN, hidden).apply()
        val state = if (hidden) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
        try {
            packageManager.setComponentEnabledSetting(
                launcherAliasName,
                state,
                PackageManager.DONT_KILL_APP
            )
        } catch (_: Exception) {}
    }

    fun toggleLockOnBackground(enabled: Boolean) {
        lockOnBackground = enabled
        AppPreferences.setLockOnBackground(context, enabled)
    }

    fun toggleAutoFindVaults(enabled: Boolean) {
        autoFindVaults = enabled
        AppPreferences.setAutoFindVaults(context, enabled)
    }

    fun toggleCustomVaultSave(enabled: Boolean) {
        if (enabled) {
            if (customVaultSaveUri == null) {
                folderPicker.launch(null)
            } else {
                customVaultSaveEnabled = true
                AppPreferences.setCustomVaultSaveEnabled(context, true)
            }
        } else {
            customVaultSaveEnabled = false
            AppPreferences.setCustomVaultSaveEnabled(context, false)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", color = VaultOnBackground) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = VaultOnBackground
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = VaultBackground)
            )
        },
        containerColor = VaultBackground
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Open .biv files directly",
                        style = MaterialTheme.typography.titleMedium,
                        color = VaultOnBackground
                    )
                    Text(
                        "Allow opening .biv files directly from the file manager",
                        style = MaterialTheme.typography.bodyMedium,
                        color = VaultOnBackground.copy(alpha = 0.7f)
                    )
                }
                Switch(checked = isBivEnabled, onCheckedChange = { toggleBiv(it) })
            }

            HorizontalDivider(color = VaultOnBackground.copy(alpha = 0.1f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Hide App Icon",
                        style = MaterialTheme.typography.titleMedium,
                        color = VaultOnBackground
                    )
                    Text(
                        "Hide app from launcher. Dial ${SecretCodeReceiver.DIAL_CODE} to open",
                        style = MaterialTheme.typography.bodyMedium,
                        color = VaultOnBackground.copy(alpha = 0.7f)
                    )
                }
                Switch(checked = isIconHidden, onCheckedChange = { toggleIcon(it) })
            }

            HorizontalDivider(color = VaultOnBackground.copy(alpha = 0.1f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Lock when minimized",
                        style = MaterialTheme.typography.titleMedium,
                        color = VaultOnBackground
                    )
                    Text(
                        "Lock the vault and ask for the password again when the app goes to the background",
                        style = MaterialTheme.typography.bodyMedium,
                        color = VaultOnBackground.copy(alpha = 0.7f)
                    )
                }
                Switch(
                    checked = lockOnBackground,
                    onCheckedChange = { toggleLockOnBackground(it) }
                )
            }

            HorizontalDivider(color = VaultOnBackground.copy(alpha = 0.1f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Auto-find vault files",
                        style = MaterialTheme.typography.titleMedium,
                        color = VaultOnBackground
                    )
                    Text(
                        "When opening a vault, scan storage for .biv files instead of the file picker",
                        style = MaterialTheme.typography.bodyMedium,
                        color = VaultOnBackground.copy(alpha = 0.7f)
                    )
                }
                Switch(
                    checked = autoFindVaults,
                    onCheckedChange = { toggleAutoFindVaults(it) }
                )
            }

            HorizontalDivider(color = VaultOnBackground.copy(alpha = 0.1f))

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Custom vault save location",
                            style = MaterialTheme.typography.titleMedium,
                            color = VaultOnBackground
                        )
                        Text(
                            "Save new vaults to a chosen folder. When off, you pick the location each time",
                            style = MaterialTheme.typography.bodyMedium,
                            color = VaultOnBackground.copy(alpha = 0.7f)
                        )
                    }
                    Switch(
                        checked = customVaultSaveEnabled,
                        onCheckedChange = { toggleCustomVaultSave(it) }
                    )
                }

                if (customVaultSaveEnabled) {
                    val pathLabel = customVaultSaveUri?.let { VaultSaveLocation.displayPath(it) }
                        ?: "No folder selected"
                    Text(
                        text = pathLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = VaultOnBackground.copy(alpha = 0.6f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(
                        onClick = { folderPicker.launch(customVaultSaveUri) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = null,
                            tint = VaultPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (customVaultSaveUri == null) "Choose folder" else "Change folder"
                        )
                    }
                }
            }
        }
    }
}
