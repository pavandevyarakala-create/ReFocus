package com.reeltrak.app.features.settings.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
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
import com.reeltrak.app.features.settings.viewmodel.RestoreUiState

/**
 * Editorial Restore Setting Item for Re:Focus users.
 *
 * Visual principles:
 * - Seamlessly integrates inside SettingsGroupContainer via SettingsItemRow
 * - Squircle cloud download icon tile
 * - Dedicated trailing action: "Restore" -> Spinner -> "✓ Restored" / "Retry"
 * - Clear error and progress messaging without silent swallowing
 * - Disabled state when another operation is in flight
 */
@Composable
fun RestoreSettingItem(
    restoreState: RestoreUiState,
    isEntitled: Boolean = true,
    enabled: Boolean = true,
    onRestoreClick: () -> Unit,
    modifier: Modifier = Modifier,
    showDivider: Boolean = false
) {
    val colors = ReFocusTheme.colors
    val isRestoring = restoreState is RestoreUiState.InProgress

    val subtitleText = when {
        !isEntitled -> "Trial & Premium feature • Restore history from cloud"
        restoreState is RestoreUiState.InProgress -> "Restoring habit history from cloud..."
        restoreState is RestoreUiState.Success -> restoreState.message
        restoreState is RestoreUiState.Error -> restoreState.message
        else -> "Restore habits, limits & history from cloud"
    }

    SettingsItemRow(
        title = "Restore Data",
        subtitle = subtitleText,
        icon = Icons.Outlined.CloudDownload,
        onClick = if (enabled && !isRestoring) onRestoreClick else null,
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
                Crossfade(targetState = restoreState, label = "restoreActionState") { state ->
                    when (state) {
                        is RestoreUiState.InProgress -> {
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
                        is RestoreUiState.Success -> {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = colors.accentLime.copy(alpha = 0.15f),
                                contentColor = colors.accentLime
                            ) {
                                Text(
                                    text = "✓ Restored",
                                    style = ReFocusTheme.Typography.Metadata.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 12.sp
                                    ),
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                        is RestoreUiState.Error -> {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = colors.destructiveSubtle,
                                contentColor = colors.destructive,
                                modifier = Modifier.clickable(enabled = enabled, onClick = onRestoreClick)
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
                        is RestoreUiState.Idle -> {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (colors.isDark) colors.surfaceFloating else colors.surfaceSoft,
                                contentColor = if (enabled) colors.inkPrimary else colors.inkMuted,
                                modifier = Modifier.clickable(enabled = enabled, onClick = onRestoreClick)
                            ) {
                                Text(
                                    text = "Restore",
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
