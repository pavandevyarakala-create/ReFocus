package com.reeltrak.app.features.settings.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reeltrak.app.core.ui.theme.ReFocusTheme
import com.reeltrak.app.features.settings.viewmodel.BackupUiState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Clean, restrained Backup Setting Item for entitled Re:Focus users.
 *
 * Visual principles:
 * - Seamlessly integrates inside SettingsGroupContainer via SettingsItemRow
 * - Squircle cloud upload icon tile
 * - Displays relative or formatted timestamp when available ("Last backup: Today at 2:30 PM")
 * - Dedicated trailing action: "Back Up Now" -> Spinner -> "✓ Saved" / "Retry"
 * - Disables interaction and shows loading indicator while in progress
 * - Completely friendly failure state without technical leakages
 */
@Composable
fun BackupSettingItem(
    backupState: BackupUiState,
    lastBackupTimeMs: Long,
    isBackedUpToday: Boolean = false,
    nextEligibleTimeMs: Long = 0L,
    isEntitled: Boolean = true,
    enabled: Boolean = true,
    onBackupClick: () -> Unit,
    modifier: Modifier = Modifier,
    showDivider: Boolean = true
) {
    val colors = ReFocusTheme.colors
    val isBackingUp = backupState is BackupUiState.InProgress
    val isAlreadyDone = isBackedUpToday || backupState is BackupUiState.AlreadyBackedUpToday

    val subtitleText = when {
        !isEntitled -> "Trial & Premium feature • Save habit data to cloud"
        backupState is BackupUiState.InProgress -> "Backing up your data to cloud..."
        backupState is BackupUiState.Success -> "Backup completed • Just now"
        backupState is BackupUiState.AlreadyBackedUpToday -> {
            if (nextEligibleTimeMs > 0L) {
                "Next backup available: ${formatNextEligibleTimestamp(nextEligibleTimeMs)}"
            } else {
                backupState.message
            }
        }
        backupState is BackupUiState.Error -> "Backup failed • Tap to retry"
        backupState is BackupUiState.Idle -> {
            if (isAlreadyDone) {
                if (lastBackupTimeMs > 0L && nextEligibleTimeMs > 0L) {
                    val lastStr = formatLastBackupTimestamp(lastBackupTimeMs)
                    val nextStr = formatNextEligibleTimestamp(nextEligibleTimeMs)
                    "$lastStr • Next: $nextStr"
                } else if (lastBackupTimeMs > 0L) {
                    formatLastBackupTimestamp(lastBackupTimeMs)
                } else if (nextEligibleTimeMs > 0L) {
                    "Next backup available: ${formatNextEligibleTimestamp(nextEligibleTimeMs)}"
                } else {
                    "Your data is backed up (1 backup per 24 hours)"
                }
            } else if (lastBackupTimeMs > 0L) {
                formatLastBackupTimestamp(lastBackupTimeMs)
            } else {
                "Keep your Re:Focus data safe"
            }
        }
        else -> "Keep your Re:Focus data safe"
    }

    SettingsItemRow(
        title = "Back Up Data",
        subtitle = subtitleText,
        icon = Icons.Outlined.CloudUpload,
        onClick = if (enabled && !isBackingUp && (!isAlreadyDone || !isEntitled)) onBackupClick else null,
        showDivider = showDivider,
        modifier = modifier,
        trailing = {
            if (!isEntitled) {
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = "Premium Only",
                    tint = colors.inkMuted,
                    modifier = Modifier.size(18.dp)
                )
            } else {
                Crossfade(targetState = backupState, label = "backupActionState") { state ->
                    when {
                        state is BackupUiState.InProgress -> {
                            Box(
                                modifier = Modifier
                                    .height(32.dp)
                                    .padding(horizontal = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = colors.accentLime
                                )
                            }
                        }
                        state is BackupUiState.Success -> {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = colors.accentLime.copy(alpha = 0.15f),
                                contentColor = colors.accentLime
                            ) {
                                Text(
                                    text = "✓ Saved",
                                    style = ReFocusTheme.Typography.Metadata.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 12.sp
                                    ),
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                        state is BackupUiState.Error -> {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = colors.destructiveSubtle,
                                contentColor = colors.destructive,
                                modifier = Modifier.clickable(enabled = enabled, onClick = onBackupClick)
                            ) {
                                Text(
                                    text = "Retry",
                                    style = ReFocusTheme.Typography.Metadata.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 12.sp
                                    ),
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                        isAlreadyDone -> {
                            val remainingMs = (nextEligibleTimeMs - System.currentTimeMillis()).coerceAtLeast(0L)
                            val buttonText = if (remainingMs > 0L) {
                                val hours = (remainingMs + 3599999L) / (1000 * 60 * 60)
                                "Next in ${hours}h"
                            } else {
                                "Backed Up"
                            }
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (colors.isDark) colors.surfaceFloating else colors.surfaceSoft,
                                contentColor = colors.inkSecondary
                            ) {
                                Text(
                                    text = buttonText,
                                    style = ReFocusTheme.Typography.Metadata.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 12.sp
                                    ),
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                        else -> {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (colors.isDark) colors.surfaceFloating else colors.surfaceSoft,
                                contentColor = if (enabled) colors.inkPrimary else colors.inkMuted,
                                modifier = Modifier.clickable(enabled = enabled, onClick = onBackupClick)
                            ) {
                                Text(
                                    text = "Back Up Now",
                                    style = ReFocusTheme.Typography.Metadata.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 12.sp
                                    ),
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    )
}

/**
 * Human-friendly backup timestamp formatter.
 */
private fun formatLastBackupTimestamp(timestampMs: Long): String {
    return try {
        val backupDate = Instant.ofEpochMilli(timestampMs)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
        val today = LocalDate.now()
        val timeFormatter = DateTimeFormatter.ofPattern("h:mm a")
        val timeStr = Instant.ofEpochMilli(timestampMs)
            .atZone(ZoneId.systemDefault())
            .format(timeFormatter)

        when {
            backupDate == today -> "Last backup: Today at $timeStr"
            backupDate == today.minusDays(1) -> "Last backup: Yesterday at $timeStr"
            else -> {
                val dateFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy")
                val dateStr = backupDate.format(dateFormatter)
                "Last backup: $dateStr"
            }
        }
    } catch (_: Exception) {
        "Keep your Re:Focus data safe"
    }
}

/**
 * Human-friendly next eligible backup timestamp formatter.
 */
private fun formatNextEligibleTimestamp(timestampMs: Long): String {
    if (timestampMs <= 0L) return "in 24 hours"
    return try {
        val eligibleZoneTime = Instant.ofEpochMilli(timestampMs)
            .atZone(ZoneId.systemDefault())
        val eligibleDate = eligibleZoneTime.toLocalDate()
        val today = LocalDate.now()
        val timeFormatter = DateTimeFormatter.ofPattern("h:mm a")
        val timeStr = eligibleZoneTime.format(timeFormatter)

        when {
            eligibleDate == today -> "Today at $timeStr"
            eligibleDate == today.plusDays(1) -> "Tomorrow at $timeStr"
            else -> {
                val dateFormatter = DateTimeFormatter.ofPattern("MMM d 'at' h:mm a")
                eligibleZoneTime.format(dateFormatter)
            }
        }
    } catch (_: Exception) {
        "in 24 hours"
    }
}
