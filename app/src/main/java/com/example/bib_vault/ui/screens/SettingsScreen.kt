package com.example.bib_vault.ui.screens

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.bib_vault.ui.theme.VaultBackground
import com.example.bib_vault.ui.theme.VaultOnBackground

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val packageManager = context.packageManager
    val prefs = context.getSharedPreferences("BibVaultSettings", Context.MODE_PRIVATE)
    
    val bivAliasName = ComponentName(context, context.packageName + ".BivFileAlias")
    val launcherAliasName = ComponentName(context, context.packageName + ".LauncherAlias")

    var isBivEnabled by remember {
        mutableStateOf(prefs.getBoolean("isBivEnabled", true))
    }

    var isIconHidden by remember {
        mutableStateOf(prefs.getBoolean("isIconHidden", false))
    }
    
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            isIconHidden = true
            prefs.edit().putBoolean("isIconHidden", true).apply()
            packageManager.setComponentEnabledSetting(launcherAliasName, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        } else {
            android.widget.Toast.makeText(context, "Permission needed to catch dialer code", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    fun toggleBiv(enabled: Boolean) {
        isBivEnabled = enabled
        prefs.edit().putBoolean("isBivEnabled", enabled).apply()
        val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        try {
            packageManager.setComponentEnabledSetting(bivAliasName, state, PackageManager.DONT_KILL_APP)
        } catch(e: Exception) {}
    }

    fun toggleIcon(hidden: Boolean) {
        if (hidden) {
            val permission = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.PROCESS_OUTGOING_CALLS)
            if (permission == PackageManager.PERMISSION_GRANTED) {
                isIconHidden = true
                prefs.edit().putBoolean("isIconHidden", true).apply()
                try {
                    packageManager.setComponentEnabledSetting(launcherAliasName, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
                } catch(e: Exception) {}
            } else {
                permissionLauncher.launch(android.Manifest.permission.PROCESS_OUTGOING_CALLS)
            }
        } else {
            isIconHidden = false
            prefs.edit().putBoolean("isIconHidden", false).apply()
            try {
                packageManager.setComponentEnabledSetting(launcherAliasName, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            } catch(e: Exception) {}
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", color = VaultOnBackground) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back", tint = VaultOnBackground)
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Open .biv files directly", style = MaterialTheme.typography.titleMedium, color = VaultOnBackground)
                    Text("Allow opening .biv files directly from the file manager", style = MaterialTheme.typography.bodyMedium, color = VaultOnBackground.copy(alpha = 0.7f))
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
                    Text("Hide App Icon", style = MaterialTheme.typography.titleMedium, color = VaultOnBackground)
                    Text("Hide app from launcher. Dial *5643# to open", style = MaterialTheme.typography.bodyMedium, color = VaultOnBackground.copy(alpha = 0.7f))
                }
                Switch(checked = isIconHidden, onCheckedChange = { toggleIcon(it) })
            }
        }
    }
}
