package com.example.bib_vault.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.bib_vault.ui.theme.VaultError
import com.example.bib_vault.ui.theme.VaultPrimary
import com.example.bib_vault.ui.theme.VaultPrimaryLight

/**
 * Modal dialog for password entry when opening or creating a vault.
 *
 * @param isCreateMode True for vault creation (shows confirm field + strength indicator)
 * @param errorMessage Error message to display (e.g., "Wrong password")
 * @param isLoading Show loading state during key derivation
 * @param onConfirm Called with the entered password
 * @param onDismiss Called when dialog is cancelled
 */
@Composable
fun PasswordDialog(
    isCreateMode: Boolean = false,
    errorMessage: String? = null,
    isLoading: Boolean = false,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }

    val displayError = errorMessage ?: localError

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        icon = {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = VaultPrimary,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                text = if (isCreateMode) "Set Vault Password" else "Enter Password",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Password field
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        localError = null
                    },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Default.VisibilityOff
                                else Icons.Default.Visibility,
                                contentDescription = "Toggle visibility"
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (password.isNotEmpty()) {
                                onConfirm(password)
                            }
                        }
                    ),
                    isError = displayError != null,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    enabled = !isLoading
                )

                // Strength indicator (create mode)
                if (isCreateMode) {
                    PasswordStrengthIndicator(password = password)
                }

                // Error message
                if (displayError != null) {
                    Text(
                        text = displayError,
                        color = VaultError,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                // Loading indicator
                if (isLoading) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = VaultPrimary
                        )
                        Text(
                            text = "Deriving encryption key...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    when {
                        password.isEmpty() -> localError = "Password cannot be empty"
                        isCreateMode && password.length < 6 -> localError = "Password must be at least 6 characters"
                        else -> onConfirm(password)
                    }
                },
                enabled = !isLoading && password.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = VaultPrimary),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(if (isCreateMode) "Create" else "Unlock")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isLoading
            ) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Visual password strength indicator bar.
 */
@Composable
private fun PasswordStrengthIndicator(password: String) {
    val strength = calculatePasswordStrength(password)
    val color by animateColorAsState(
        targetValue = when {
            strength < 0.25f -> VaultError
            strength < 0.5f -> MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
            strength < 0.75f -> MaterialTheme.colorScheme.tertiary
            else -> VaultPrimaryLight
        },
        label = "strength_color"
    )
    val label = when {
        password.isEmpty() -> ""
        strength < 0.25f -> "Weak"
        strength < 0.5f -> "Fair"
        strength < 0.75f -> "Good"
        else -> "Strong"
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LinearProgressIndicator(
            progress = { strength },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        if (label.isNotEmpty()) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = color
            )
        }
    }
}

private fun calculatePasswordStrength(password: String): Float {
    if (password.isEmpty()) return 0f
    var score = 0f
    if (password.length >= 6) score += 0.2f
    if (password.length >= 10) score += 0.2f
    if (password.any { it.isUpperCase() }) score += 0.15f
    if (password.any { it.isLowerCase() }) score += 0.15f
    if (password.any { it.isDigit() }) score += 0.15f
    if (password.any { !it.isLetterOrDigit() }) score += 0.15f
    return score.coerceIn(0f, 1f)
}
