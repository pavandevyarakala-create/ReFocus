package com.reeltrak.app.features.settings.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.reeltrak.app.core.ui.theme.FocusReticleMark
import com.reeltrak.app.core.ui.theme.ReFocusFloatingPillButton
import com.reeltrak.app.core.ui.theme.ReFocusTheme
import com.reeltrak.app.core.ui.theme.specularBorder

@Composable
fun DailyLimitSelectionDialog(
    initialLimit: Int,
    onDismiss: () -> Unit,
    onSaveLimit: (Int) -> Unit
) {
    val colors = ReFocusTheme.colors
    var selectedGoal by remember(initialLimit) { mutableIntStateOf(initialLimit) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .wrapContentHeight(),
            shape = ReFocusTheme.Dimens.ShapeLg,
            color = colors.surface,
            tonalElevation = 4.dp,
            shadowElevation = 12.dp,
            border = ReFocusTheme.Dimens.Hairline
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Focus reticle mark
                FocusReticleMark(modifier = Modifier.size(32.dp))

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Daily Reel Limit",
                    style = ReFocusTheme.Typography.Headline.copy(fontSize = 20.sp),
                    color = colors.inkPrimary
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "Set your maximum daily reels threshold. Re:Focus will enforce viewing limits once reached.",
                    style = ReFocusTheme.Typography.Body,
                    color = colors.inkSecondary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Stepper Row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    FilledTonalIconButton(
                        onClick = { if (selectedGoal > 5) selectedGoal -= 5 },
                        enabled = selectedGoal > 5,
                        modifier = Modifier.size(44.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = colors.surfaceSubtle,
                            contentColor = colors.inkPrimary
                        )
                    ) {
                        Text("-", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                    }

                    Spacer(modifier = Modifier.width(20.dp))

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "$selectedGoal",
                            style = ReFocusTheme.Typography.BigMetric.copy(
                                fontSize = 48.sp,
                                color = colors.inkPrimary
                            )
                        )
                        Text(
                            text = "REELS / DAY",
                            style = ReFocusTheme.Typography.SectionTitle.copy(color = colors.inkMuted)
                        )
                    }

                    Spacer(modifier = Modifier.width(20.dp))

                    FilledTonalIconButton(
                        onClick = { if (selectedGoal < 500) selectedGoal += 5 },
                        enabled = selectedGoal < 500,
                        modifier = Modifier.size(44.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = colors.surfaceSubtle,
                            contentColor = colors.inkPrimary
                        )
                    ) {
                        Text("+", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Presets
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(25, 50, 75, 100).forEach { preset ->
                        val isSelected = selectedGoal == preset
                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedGoal = preset },
                            label = {
                                Text(
                                    text = "$preset",
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.Center,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = colors.accentLimeSubtle,
                                selectedLabelColor = colors.inkPrimary,
                                containerColor = colors.surfaceSubtle,
                                labelColor = colors.inkSecondary
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = isSelected,
                                borderColor = if (isSelected) colors.accentLime else colors.lineHairline
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ReFocusFloatingPillButton(
                        text = "Cancel",
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    )

                    ReFocusFloatingPillButton(
                        text = "Save Limit",
                        onClick = { onSaveLimit(selectedGoal) },
                        modifier = Modifier.weight(1f),
                        isPrimary = true
                    )
                }
            }
        }
    }
}

@Composable
fun AccountDeletionDialog(
    isDeleting: Boolean,
    onDismiss: () -> Unit,
    onConfirmDelete: () -> Unit
) {
    val colors = ReFocusTheme.colors
    var confirmationText by remember { mutableStateOf("") }

    Dialog(
        onDismissRequest = { if (!isDeleting) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = !isDeleting,
            dismissOnClickOutside = !isDeleting,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .wrapContentHeight(),
            shape = ReFocusTheme.Dimens.ShapeLg,
            color = colors.surface,
            tonalElevation = 4.dp,
            shadowElevation = 12.dp,
            border = BorderStroke(ReFocusTheme.Dimens.HairlineWidth, colors.destructive)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Delete Account?",
                    style = ReFocusTheme.Typography.Headline.copy(
                        color = colors.destructive,
                        fontSize = 20.sp
                    )
                )

                Spacer(modifier = Modifier.height(10.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.surfaceSubtle, ReFocusTheme.Dimens.ShapeSm)
                        .border(ReFocusTheme.Dimens.HairlineWidth, colors.lineHairline, ReFocusTheme.Dimens.ShapeSm)
                        .padding(14.dp)
                ) {
                    Text(
                        text = "• Requesting deletion starts a 7-day recovery grace period.\n" +
                                "• Historical account data will be permanently purged after 7 days.\n" +
                                "• Account deletion does not automatically refund active subscriptions.\n" +
                                "• Today's daily limit enforcement remains active on this device to prevent anti-bypass loops.",
                        style = ReFocusTheme.Typography.Body.copy(
                            fontSize = 12.sp,
                            color = colors.inkSecondary
                        )
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "To confirm, type DELETE below:",
                    style = ReFocusTheme.Typography.SectionTitle.copy(color = colors.inkPrimary),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = confirmationText,
                    onValueChange = { confirmationText = it },
                    placeholder = { Text("DELETE", color = colors.inkMuted) },
                    singleLine = true,
                    enabled = !isDeleting,
                    shape = ReFocusTheme.Dimens.ShapeSm,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.destructive,
                        unfocusedBorderColor = colors.lineHairline,
                        focusedTextColor = colors.inkPrimary,
                        unfocusedTextColor = colors.inkPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ReFocusFloatingPillButton(
                        text = "Cancel",
                        onClick = onDismiss,
                        enabled = !isDeleting,
                        modifier = Modifier.weight(1f)
                    )

                    ReFocusFloatingPillButton(
                        text = "Schedule Deletion",
                        onClick = onConfirmDelete,
                        enabled = confirmationText.trim() == "DELETE" && !isDeleting,
                        isLoading = isDeleting,
                        isDestructive = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
fun UninstallConfirmationDialog(
    onDismiss: () -> Unit,
    onConfirmUninstall: () -> Unit,
    onManageSubscriptions: () -> Unit
) {
    val colors = ReFocusTheme.colors

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .wrapContentHeight(),
            shape = ReFocusTheme.Dimens.ShapeLg,
            color = colors.surface,
            tonalElevation = 4.dp,
            shadowElevation = 12.dp,
            border = ReFocusTheme.Dimens.Hairline
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Uninstall Re:Focus?",
                    style = ReFocusTheme.Typography.Headline.copy(fontSize = 20.sp),
                    color = colors.inkPrimary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(10.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.surfaceSubtle, ReFocusTheme.Dimens.ShapeSm)
                        .border(ReFocusTheme.Dimens.HairlineWidth, colors.lineHairline, ReFocusTheme.Dimens.ShapeSm)
                        .padding(14.dp)
                ) {
                    Column {
                        Text(
                            text = "• Streaks, limits & history remain securely saved in the cloud and restore on reinstall.",
                            style = ReFocusTheme.Typography.Body.copy(
                                fontSize = 12.sp,
                                color = colors.inkSecondary
                            )
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "• Uninstalling does NOT cancel active subscriptions. You can manage or cancel anytime via Subscription Settings.",
                            style = ReFocusTheme.Typography.Body.copy(
                                fontSize = 12.sp,
                                color = colors.inkSecondary
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                TextButton(
                    onClick = onManageSubscriptions,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "MANAGE SUBSCRIPTION →",
                        style = ReFocusTheme.Typography.SectionTitle.copy(
                            color = colors.inkPrimary
                        )
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ReFocusFloatingPillButton(
                        text = "Keep App",
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    )

                    ReFocusFloatingPillButton(
                        text = "Uninstall",
                        onClick = onConfirmUninstall,
                        isDestructive = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/**
 * Restore Data Confirmation Dialog.
 *
 * Confirms with the user before performing cloud data restoration to ensure
 * they understand it will merge cloud records with local tracking.
 */
@Composable
fun RestoreConfirmationDialog(
    isRestoring: Boolean,
    onDismiss: () -> Unit,
    onConfirmRestore: () -> Unit
) {
    val colors = ReFocusTheme.colors

    Dialog(
        onDismissRequest = { if (!isRestoring) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .wrapContentHeight(),
            shape = ReFocusTheme.Dimens.ShapeLg,
            color = colors.surface,
            tonalElevation = 4.dp,
            shadowElevation = 12.dp,
            border = ReFocusTheme.Dimens.Hairline
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(colors.surfaceRecessed, ReFocusTheme.Dimens.ShapeSm),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CloudDownload,
                        contentDescription = null,
                        tint = colors.accentLime,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Restore Data from Cloud?",
                    style = ReFocusTheme.Typography.Headline.copy(fontSize = 20.sp),
                    color = colors.inkPrimary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Restoring will merge your cloud habit history and settings into this device. Any local unsynced progress will be safely preserved. Existing conflicting records will be updated from your latest cloud backup.\n\nAre you sure you want to proceed?",
                    style = ReFocusTheme.Typography.Body,
                    color = colors.inkSecondary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ReFocusFloatingPillButton(
                        text = "Cancel",
                        onClick = onDismiss,
                        enabled = !isRestoring,
                        modifier = Modifier.weight(1f)
                    )

                    ReFocusFloatingPillButton(
                        text = "Confirm Restore",
                        onClick = onConfirmRestore,
                        enabled = !isRestoring,
                        isLoading = isRestoring,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

