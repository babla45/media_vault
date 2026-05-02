package com.example.bib_vault.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * BibVault dark color scheme — always dark for a premium security feel.
 */
private val VaultDarkColorScheme = darkColorScheme(
    primary = VaultPrimary,
    onPrimary = VaultOnPrimary,
    primaryContainer = VaultPrimaryDark,
    onPrimaryContainer = VaultPrimaryLight,
    secondary = VaultSecondary,
    onSecondary = VaultOnPrimary,
    secondaryContainer = VaultSecondary.copy(alpha = 0.3f),
    onSecondaryContainer = VaultSecondaryLight,
    tertiary = VaultAccentPurple,
    background = VaultBackground,
    onBackground = VaultOnBackground,
    surface = VaultSurface,
    onSurface = VaultOnSurface,
    surfaceVariant = VaultSurfaceLight,
    onSurfaceVariant = VaultOnSurfaceVariant,
    error = VaultError,
    onError = VaultOnError,
    outline = GlassBorder
)

@Composable
fun Bib_vaultTheme(
    content: @Composable () -> Unit
) {
    val colorScheme = VaultDarkColorScheme
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = VaultBackground.toArgb()
            window.navigationBarColor = VaultBackground.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}