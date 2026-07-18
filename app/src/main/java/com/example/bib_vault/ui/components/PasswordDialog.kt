package com.example.bib_vault.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
 * @param isCreateMode True for vault creation (shows strength indicator + optional second password)
 * @param errorMessage Error message to display (e.g., "Wrong password")
 * @param isLoading Show loading state during key derivation
 * @param onConfirm Called with (vaultPassword, sensitivePassword). In create mode, if the
 *                  optional second password is blank, sensitivePassword equals vaultPassword.
 * @param onDismiss Called when dialog is cancelled
 */
@Composable
fun PasswordDialog(
    isCreateMode: Boolean = false,
    errorMessage: String? = null,
    isLoading: Boolean = false,
    onConfirm: (password: String, sensitivePassword: String) -> Unit,
    onDismiss: () -> Unit
) {
    var password by remember { mutableStateOf("") }
    var secondPassword by remember { mutableStateOf("") }
    var showSecondPassword by remember { mutableStateOf(false) }
    var passwordVisible by remember { mutableStateOf(false) }
    var secondPasswordVisible by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }

    val displayError = errorMessage ?: localError

    fun resolveSensitivePassword(): String =
        if (secondPassword.isBlank()) password else secondPassword

    fun tryConfirm() {
        when {
            password.isEmpty() -> localError = "Password cannot be empty"
            isCreateMode && !isValidVaultPassword(password) ->
                localError = "Password must be at least 4 characters"
            isCreateMode && secondPassword.isNotBlank() && !isValidVaultPassword(secondPassword) ->
                localError = "Second password must be at least 4 characters"
            else -> onConfirm(password, resolveSensitivePassword())
        }
    }

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
                        imeAction = if (isCreateMode && showSecondPassword) ImeAction.Next else ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (!isCreateMode || !showSecondPassword) tryConfirm()
                        }
                    ),
                    isError = displayError != null,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    enabled = !isLoading
                )

                if (isCreateMode) {
                    PasswordStrengthIndicator(password = password)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isLoading) {
                                showSecondPassword = !showSecondPassword
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Second password (optional)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Icon(
                            imageVector = if (showSecondPassword) {
                                Icons.Default.KeyboardArrowUp
                            } else {
                                Icons.Default.KeyboardArrowDown
                            },
                            contentDescription = if (showSecondPassword) {
                                "Hide second password"
                            } else {
                                "Show second password"
                            },
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    AnimatedVisibility(
                        visible = showSecondPassword,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "Used for restore and disabling screenshot protection. " +
                                    "If left empty, the vault password is used.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedTextField(
                                value = secondPassword,
                                onValueChange = {
                                    secondPassword = it
                                    localError = null
                                },
                                label = { Text("Second password") },
                                singleLine = true,
                                visualTransformation = if (secondPasswordVisible) {
                                    VisualTransformation.None
                                } else {
                                    PasswordVisualTransformation()
                                },
                                trailingIcon = {
                                    IconButton(
                                        onClick = { secondPasswordVisible = !secondPasswordVisible }
                                    ) {
                                        Icon(
                                            imageVector = if (secondPasswordVisible) {
                                                Icons.Default.VisibilityOff
                                            } else {
                                                Icons.Default.Visibility
                                            },
                                            contentDescription = "Toggle visibility"
                                        )
                                    }
                                },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Password,
                                    imeAction = ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(onDone = { tryConfirm() }),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                enabled = !isLoading
                            )
                        }
                    }
                }

                if (displayError != null) {
                    Text(
                        text = displayError,
                        color = VaultError,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

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
                onClick = { tryConfirm() },
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

private fun isValidVaultPassword(password: String): Boolean {
    return password.length >= 4
}
