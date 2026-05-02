package com.example.bib_vault

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.bib_vault.state.VaultState
import com.example.bib_vault.state.VaultViewModel
import com.example.bib_vault.ui.components.PasswordDialog
import com.example.bib_vault.ui.navigation.Routes
import com.example.bib_vault.ui.screens.*
import com.example.bib_vault.ui.theme.Bib_vaultTheme

/**
 * Main entry point for BibVault.
 *
 * Sets FLAG_SECURE to prevent screenshots of decrypted content.
 * Manages navigation between Home, Create, Browser, and Player screens.
 * Coordinates file picker intents and password dialogs.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Security: Block screenshots and screen recording
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        enableEdgeToEdge()

        setContent {
            Bib_vaultTheme {
                BibVaultApp()
            }
        }
    }
}

/**
 * Extract filename and size from a content URI.
 */
private fun queryFileInfo(context: Context, uri: Uri): Pair<String, Long> {
    android.util.Log.d("BibVault", "queryFileInfo: URI type is ${uri.scheme} -> $uri")
    var name = uri.lastPathSegment ?: "unknown"
    var size = 0L
    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIdx >= 0) name = cursor.getString(nameIdx)
                if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
            }
        }
    } catch (_: Exception) {}
    return Pair(name, size)
}

@Composable
private fun BibVaultApp() {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val navController = rememberNavController()
    val viewModel: VaultViewModel = viewModel()
    val vaultState by viewModel.vaultState.collectAsState()
    val progress by viewModel.progress.collectAsState()
    var screenshotProtectionEnabled by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(activity, screenshotProtectionEnabled) {
        val window = activity?.window ?: return@LaunchedEffect
        if (screenshotProtectionEnabled) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    // ── File selection state ──
    var selectedFileUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var selectedFileInfo by remember { mutableStateOf<List<Pair<String, Long>>>(emptyList()) }

    // ── Dialog state ──
    var showOpenPasswordDialog by remember { mutableStateOf(false) }
    var showCreatePasswordDialog by remember { mutableStateOf(false) }
    var pendingVaultUri by remember { mutableStateOf<Uri?>(null) }
    var currentPassword by remember { mutableStateOf("") }
    var intentProcessed by rememberSaveable { mutableStateOf(false) }

    // ── Process Incoming Intents & Permissions ──
    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                try {
                    val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = android.net.Uri.parse("package:${context.packageName}")
                    context.startActivity(intent)
                } catch (e: Exception) {
                    val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    context.startActivity(intent)
                }
            }
        }

        if (!intentProcessed) {
            val intent = (context as? ComponentActivity)?.intent
            if (intent != null) {
                val uris = mutableSetOf<Uri>()
                
                // 1. Try extracting from ClipData (modern Android standard for permissions)
                val clipData = intent.clipData
                if (clipData != null) {
                    for (i in 0 until clipData.itemCount) {
                        val uri = clipData.getItemAt(i).uri
                        if (uri != null) uris.add(uri)
                    }
                }

                // 2. Fallback to EXTRA_STREAM
                if (intent.action == android.content.Intent.ACTION_SEND) {
                    @Suppress("DEPRECATION")
                    val uri = intent.getParcelableExtra<Uri>(android.content.Intent.EXTRA_STREAM)
                    if (uri != null) uris.add(uri)
                } else if (intent.action == android.content.Intent.ACTION_SEND_MULTIPLE) {
                    @Suppress("DEPRECATION")
                    val list = intent.getParcelableArrayListExtra<Uri>(android.content.Intent.EXTRA_STREAM)
                    if (list != null) uris.addAll(list)
                } else if (intent.action == android.content.Intent.ACTION_VIEW) {
                    val uri = intent.data
                    if (uri != null) uris.add(uri)
                }

                if (uris.isNotEmpty()) {
                    val uriList = uris.toList()
                    val firstUri = uriList.first()
                    val name = queryFileInfo(context, firstUri).first
                    if (name.endsWith(".biv") && uriList.size == 1) {
                        pendingVaultUri = firstUri
                        showOpenPasswordDialog = true
                    } else {
                        selectedFileUris = uriList
                        selectedFileInfo = uriList.map { queryFileInfo(context, it) }
                        navController.navigate(Routes.CREATE_VAULT)
                    }
                }
            }
            intentProcessed = true
        }
    }

    // ── File pickers ──

    // Pick files for vault creation
    val createFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                if (uri.scheme == "content") {
                    try {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (e: SecurityException) {
                        android.util.Log.w("BibVault", "Could not take persistable permission for $uri", e)
                    }
                }
            }
            selectedFileUris = selectedFileUris + uris
            selectedFileInfo = selectedFileInfo + uris.map { queryFileInfo(context, it) }
        }
    }

    // Fallback for MIUI and problematic devices
    val fallbackCreateFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            selectedFileUris = selectedFileUris + uris
            selectedFileInfo = selectedFileInfo + uris.map { queryFileInfo(context, it) }
        }
    }

    // Pick vault file to open
    val openVaultPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            if (uri.scheme == "content") {
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (e: SecurityException) {
                    android.util.Log.w("BibVault", "Could not take persistable permission for $uri", e)
                }
            }
            pendingVaultUri = uri
            showOpenPasswordDialog = true
        }
    }

    // Fallback for opening vault
    val fallbackOpenVaultPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            pendingVaultUri = uri
            showOpenPasswordDialog = true
        }
    }

    // Create vault file (SAF save dialog)
    val createVaultSaver = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) {
            pendingVaultUri = uri
            showCreatePasswordDialog = true
        }
    }

    // Pick files to add to existing vault
    val addFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                if (uri.scheme == "content") {
                    try {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (e: SecurityException) {}
                }
            }
            if (currentPassword.isNotBlank()) {
                viewModel.addFiles(uris, currentPassword)
            }
        }
    }

    // Fallback for adding files
    val fallbackAddFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            if (currentPassword.isNotBlank()) {
                viewModel.addFiles(uris, currentPassword)
            }
        }
    }

    // ── React to vault state changes ──
    LaunchedEffect(vaultState) {
        when (vaultState) {
            is VaultState.Unlocked -> {
                val currentRoute = navController.currentDestination?.route
                if (currentRoute != Routes.VAULT_BROWSER &&
                    currentRoute?.startsWith("media_player") != true
                ) {
                    navController.navigate(Routes.VAULT_BROWSER) {
                        popUpTo(Routes.HOME) { inclusive = false }
                    }
                }
            }
            is VaultState.Locked -> {
                val currentRoute = navController.currentDestination?.route
                if (currentRoute != null && currentRoute != Routes.HOME && currentRoute != Routes.CREATE_VAULT) {
                    navController.navigate(Routes.HOME) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            }
            else -> {}
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { _ ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    onCreateVault = {
                        selectedFileUris = emptyList()
                        selectedFileInfo = emptyList()
                        navController.navigate(Routes.CREATE_VAULT)
                    },
                    onOpenVault = {
                        try {
                            openVaultPicker.launch(arrayOf("*/*"))
                        } catch (e: android.content.ActivityNotFoundException) {
                            fallbackOpenVaultPicker.launch("*/*")
                        }
                    }
                )
            }

            composable(Routes.CREATE_VAULT) {
                VaultCreationScreen(
                    selectedFiles = selectedFileInfo,
                    progress = progress,
                    isProcessing = vaultState is VaultState.Loading,
                    onAddFiles = {
                        try {
                            createFilePicker.launch(arrayOf("video/*", "audio/*", "image/*"))
                        } catch (e: android.content.ActivityNotFoundException) {
                            fallbackCreateFilePicker.launch("*/*")
                        }
                    },
                    onRemoveFile = { index ->
                        selectedFileUris = selectedFileUris.toMutableList().also { it.removeAt(index) }
                        selectedFileInfo = selectedFileInfo.toMutableList().also { it.removeAt(index) }
                    },
                    onCreateVault = {
                        createVaultSaver.launch("vault.biv")
                    },
                    onBack = {
                        navController.popBackStack()
                    }
                )
            }

            composable(Routes.VAULT_BROWSER) {
                val state = vaultState
                when (state) {
                    is VaultState.Unlocked -> {
                        VaultBrowserScreen(
                            vaultName = state.vaultName,
                            entries = state.entries,
                            onFileClick = { entry ->
                                navController.navigate(Routes.mediaPlayer(entry.id))
                            },
                        onDeleteFiles = { selectedEntries ->
                            viewModel.removeFiles(selectedEntries.map { it.id }, currentPassword)
                            },
                        onRestoreFiles = { selectedEntries ->
                            viewModel.restoreFiles(selectedEntries.map { it.id }, currentPassword)
                        },
                            onAddFiles = {
                                try {
                                    addFilePicker.launch(arrayOf("video/*", "audio/*", "image/*"))
                                } catch (e: android.content.ActivityNotFoundException) {
                                    fallbackAddFilePicker.launch("*/*")
                                }
                            },
                            onLoadPreviewBytes = { entry ->
                                viewModel.decryptImageBytes(entry)
                            },
                            screenshotProtectionEnabled = screenshotProtectionEnabled,
                            onUpdateScreenshotProtection = { enabled, passwordForDisable ->
                                if (enabled) {
                                    screenshotProtectionEnabled = true
                                    true
                                } else {
                                    if (!passwordForDisable.isNullOrEmpty() && passwordForDisable == currentPassword) {
                                        screenshotProtectionEnabled = false
                                        true
                                    } else {
                                        false
                                    }
                                }
                            },
                            onVerifyVaultPassword = { enteredPassword ->
                                enteredPassword.isNotBlank() && enteredPassword == currentPassword
                            },
                            onLock = {
                                currentPassword = ""
                                viewModel.lock()
                            }
                        )
                    }
                    is VaultState.Loading -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            androidx.compose.foundation.layout.Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(horizontal = 24.dp)
                            ) {
                                CircularProgressIndicator()
                                Text(
                                    text = if (progress.message.isNotBlank()) progress.message else state.message,
                                    modifier = Modifier.padding(top = 16.dp)
                                )
                                if (progress.isActive) {
                                    LinearProgressIndicator(
                                        progress = { progress.fraction },
                                        modifier = Modifier
                                            .padding(top = 12.dp)
                                            .fillMaxWidth(0.8f)
                                    )
                                    Text(
                                        text = "${progress.currentFile} / ${progress.totalFiles}",
                                        modifier = Modifier.padding(top = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                    else -> Unit
                }
            }

            composable(
                route = Routes.MEDIA_PLAYER,
                arguments = listOf(navArgument("entryId") { type = NavType.StringType })
            ) { backStackEntry ->
                val entryId = backStackEntry.arguments?.getString("entryId") ?: return@composable
                val state = vaultState
                if (state is VaultState.Unlocked) {
                    val entry = state.entries.find { it.id == entryId } ?: return@composable

                    MediaPlayerScreen(
                        entry = entry,
                        vaultUri = state.vaultUri,
                        header = state.header,
                        key = state.key,
                        entries = state.entries.associateBy { it.id },
                        onDecryptImage = { e -> viewModel.decryptImageBytes(e) },
                        onBack = { navController.popBackStack() }
                    )
                }
            }
        }
    }

    // ── Password Dialogs ──

    // Open vault password dialog
    if (showOpenPasswordDialog) {
        PasswordDialog(
            isCreateMode = false,
            errorMessage = if (vaultState is VaultState.Error) (vaultState as VaultState.Error).message else null,
            isLoading = vaultState is VaultState.Loading,
            onConfirm = { password ->
                currentPassword = password
                pendingVaultUri?.let { uri ->
                    viewModel.openVault(uri, password)
                }
            },
            onDismiss = {
                showOpenPasswordDialog = false
                pendingVaultUri = null
                if (vaultState is VaultState.Error) viewModel.clearError()
            }
        )
    }

    // Create vault password dialog
    if (showCreatePasswordDialog) {
        PasswordDialog(
            isCreateMode = true,
            errorMessage = if (vaultState is VaultState.Error) (vaultState as VaultState.Error).message else null,
            isLoading = vaultState is VaultState.Loading,
            onConfirm = { password ->
                currentPassword = password
                pendingVaultUri?.let { uri ->
                    viewModel.createVault(uri, password, selectedFileUris)
                }
                showCreatePasswordDialog = false
            },
            onDismiss = {
                showCreatePasswordDialog = false
                if (vaultState is VaultState.Error) viewModel.clearError()
            }
        )
    }

    // Dismiss password dialog on successful unlock
    LaunchedEffect(vaultState) {
        if (vaultState is VaultState.Unlocked) {
            showOpenPasswordDialog = false
            showCreatePasswordDialog = false
        }
    }
}