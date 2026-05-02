package com.example.bib_vault.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bib_vault.ui.theme.*

/**
 * Landing screen with vault branding, create and open vault buttons.
 * Features a glowing vault icon animation and glassmorphism card styling.
 */
@Composable
fun HomeScreen(
    onCreateVault: () -> Unit,
    onOpenVault: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(VaultBackground)
    ) {
        // Ambient glow effects in background
        AmbientGlowBackground()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(modifier = Modifier.weight(0.3f))

            // Animated vault icon with glow
            VaultIconWithGlow()

            Spacer(modifier = Modifier.height(32.dp))

            // App title
            Text(
                text = "BibVault",
                style = MaterialTheme.typography.displayLarge.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-1).sp
                ),
                color = VaultOnBackground
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Secure encrypted media container",
                style = MaterialTheme.typography.bodyLarge,
                color = VaultOnSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Security badges
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                SecurityBadge("AES-256")
                SecurityBadge("Zero-Disk")
                SecurityBadge("Offline")
            }

            Spacer(modifier = Modifier.weight(0.4f))

            // Action buttons
            // Create Vault button
            Button(
                onClick = onCreateVault,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = VaultPrimary
                ),
                shape = RoundedCornerShape(16.dp),
                elevation = ButtonDefaults.buttonElevation(
                    defaultElevation = 8.dp,
                    pressedElevation = 2.dp
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Create New Vault",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    )
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Open Vault button
            OutlinedButton(
                onClick = onOpenVault,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = VaultPrimaryLight
                ),
                border = ButtonDefaults.outlinedButtonBorder(enabled = true)
            ) {
                Icon(
                    imageVector = Icons.Default.FolderOpen,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Open Existing Vault",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Spacer(modifier = Modifier.weight(0.2f))

            // Footer
            Text(
                text = "Your media, your password, your privacy.",
                style = MaterialTheme.typography.bodySmall,
                color = VaultOnSurfaceVariant.copy(alpha = 0.5f),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * Animated vault shield icon with pulsing glow effect.
 */
@Composable
private fun VaultIconWithGlow() {
    val infiniteTransition = rememberInfiniteTransition(label = "glow")
    val glowScale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow_scale"
    )
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow_alpha"
    )

    Box(contentAlignment = Alignment.Center) {
        // Outer glow
        Box(
            modifier = Modifier
                .size(140.dp)
                .scale(glowScale)
                .blur(40.dp)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            VaultPrimary.copy(alpha = glowAlpha),
                            Color.Transparent
                        )
                    ),
                    shape = CircleShape
                )
        )

        // Icon container
        Box(
            modifier = Modifier
                .size(100.dp)
                .clip(CircleShape)
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(VaultPrimaryDark, VaultPrimary)
                    )
                )
                .border(2.dp, GlassBorder, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Shield,
                contentDescription = "Vault",
                tint = Color.White,
                modifier = Modifier.size(48.dp)
            )
        }
    }
}

/**
 * Small security feature badge pill.
 */
@Composable
private fun SecurityBadge(text: String) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = GlassWhite,
        border = ButtonDefaults.outlinedButtonBorder(enabled = true),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = VaultPrimaryLight
        )
    }
}

/**
 * Background ambient glow blobs for visual depth.
 */
@Composable
private fun AmbientGlowBackground() {
    val infiniteTransition = rememberInfiniteTransition(label = "ambient")
    val offset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 30f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ambient_offset"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        // Top-right green glow
        Box(
            modifier = Modifier
                .offset(x = (200 + offset).dp, y = (-50).dp)
                .size(300.dp)
                .blur(100.dp)
                .background(
                    VaultPrimary.copy(alpha = 0.08f),
                    CircleShape
                )
        )
        // Bottom-left purple glow
        Box(
            modifier = Modifier
                .offset(x = (-100 - offset).dp, y = (500 + offset).dp)
                .size(350.dp)
                .blur(120.dp)
                .background(
                    VaultAccentPurple.copy(alpha = 0.06f),
                    CircleShape
                )
        )
    }
}

private val EaseInOutCubic = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)
private val EaseInOutSine = CubicBezierEasing(0.37f, 0f, 0.63f, 1f)
