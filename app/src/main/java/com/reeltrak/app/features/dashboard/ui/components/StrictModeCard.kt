package com.reeltrak.app.features.dashboard.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reeltrak.app.core.ui.theme.ReFocusColors
import com.reeltrak.app.domain.models.StrictModeDailyState

@Composable
fun StrictModeCard(
    dailyState: StrictModeDailyState,
    isEntitled: Boolean = true,
    isLoading: Boolean = false,
    onTurnOffClick: () -> Unit = {},
    onReactivateClick: () -> Unit = {},
    onLockedCardClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val strictCardShape = RoundedCornerShape(16.dp)
    Card(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 2.dp,
                shape = strictCardShape,
                ambientColor = ReFocusColors.shadowAmbient,
                spotColor = ReFocusColors.shadowSpot
            )
            .then(
                if (!isEntitled) {
                    Modifier.clickable(enabled = !isLoading) { onLockedCardClick() }
                } else {
                    Modifier
                }
            ),
        shape = strictCardShape,
        colors = CardDefaults.cardColors(containerColor = ReFocusColors.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, ReFocusColors.border)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Strict Mode",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        ),
                        color = ReFocusColors.textPrimary
                    )

                    if (!isEntitled) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(ReFocusColors.accent.copy(alpha = 0.12f))
                                .border(BorderStroke(1.dp, ReFocusColors.accent.copy(alpha = 0.25f)), RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Outlined.Lock,
                                    contentDescription = "Premium feature",
                                    modifier = Modifier.size(10.dp),
                                    tint = ReFocusColors.accent
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = "PREMIUM",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 0.5.sp
                                    ),
                                    color = ReFocusColors.accent
                                )
                            }
                        }
                    } else if (dailyState == StrictModeDailyState.REACTIVATED_LOCKED) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(ReFocusColors.surfaceStrong)
                                .border(BorderStroke(1.dp, ReFocusColors.border), RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Outlined.Lock,
                                    contentDescription = "Locked for today",
                                    modifier = Modifier.size(10.dp),
                                    tint = ReFocusColors.textSecondary
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = "LOCKED",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 0.5.sp
                                    ),
                                    color = ReFocusColors.textSecondary
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                val subtitle = when {
                    !isEntitled -> "Auto-closes apps at limit • Premium feature"
                    isLoading -> "Loading..."
                    dailyState == StrictModeDailyState.REACTIVATED_LOCKED -> "Enforcement active • Locked until tomorrow"
                    dailyState == StrictModeDailyState.DISABLED_TODAY -> "Off for today • Tap to reactivate"
                    else -> "Enforcement active • You can turn this off once today"
                }

                val subtitleColor = when {
                    !isEntitled -> ReFocusColors.textMuted
                    dailyState == StrictModeDailyState.REACTIVATED_LOCKED -> ReFocusColors.textMuted
                    dailyState == StrictModeDailyState.DISABLED_TODAY -> ReFocusColors.warningText
                    else -> if (ReFocusColors.isDark) ReFocusColors.accentHighlight else ReFocusColors.accentDark
                }

                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 11.5.sp,
                        lineHeight = 15.sp
                    ),
                    color = subtitleColor
                )
            }

            // Right side control: Lock indicator for free users, or daily lifecycle controls for entitled users
            if (!isEntitled) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(ReFocusColors.surfaceStrong)
                        .border(BorderStroke(1.dp, ReFocusColors.border), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Lock,
                        contentDescription = "Strict Mode locked. Tap to view details.",
                        modifier = Modifier.size(16.dp),
                        tint = ReFocusColors.textMuted
                    )
                }
            } else {
                when (dailyState) {
                    StrictModeDailyState.ACTIVE_INTERACTIVE -> {
                        Switch(
                            checked = true,
                            onCheckedChange = { onTurnOffClick() },
                            enabled = !isLoading,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = ReFocusColors.textInverse,
                                checkedTrackColor = ReFocusColors.accent,
                                checkedBorderColor = ReFocusColors.accent
                            )
                        )
                    }

                    StrictModeDailyState.DISABLED_TODAY -> {
                        Box(
                            modifier = Modifier
                                .height(34.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(ReFocusColors.surfaceStrong)
                                .border(BorderStroke(1.dp, ReFocusColors.border), RoundedCornerShape(8.dp))
                                .clickable(enabled = !isLoading) { onReactivateClick() }
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Outlined.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = if (ReFocusColors.isDark) ReFocusColors.accentHighlight else ReFocusColors.accentDark
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = "Reactivate",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    ),
                                    color = ReFocusColors.textPrimary
                                )
                            }
                        }
                    }

                    StrictModeDailyState.REACTIVATED_LOCKED -> {
                        Switch(
                            checked = true,
                            onCheckedChange = {},
                            enabled = false,
                            colors = SwitchDefaults.colors(
                                disabledCheckedThumbColor = ReFocusColors.textInverse.copy(alpha = 0.85f),
                                disabledCheckedTrackColor = ReFocusColors.accent.copy(alpha = 0.55f),
                                disabledCheckedBorderColor = ReFocusColors.border
                            )
                        )
                    }
                }
            }
        }
    }
}

/**
 * Backward-compatible overload for StrictModeCard.
 */
@Composable
fun StrictModeCard(
    isStrictModeEnabled: Boolean,
    isLocked: Boolean,
    isEntitled: Boolean = true,
    isLoading: Boolean = false,
    onToggleStrictMode: (Boolean) -> Unit = {},
    onLockedCardClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val dailyState = when {
        isLocked && isStrictModeEnabled -> StrictModeDailyState.REACTIVATED_LOCKED
        !isStrictModeEnabled -> StrictModeDailyState.DISABLED_TODAY
        else -> StrictModeDailyState.ACTIVE_INTERACTIVE
    }

    StrictModeCard(
        dailyState = dailyState,
        isEntitled = isEntitled,
        isLoading = isLoading,
        onTurnOffClick = { onToggleStrictMode(false) },
        onReactivateClick = { onToggleStrictMode(true) },
        onLockedCardClick = onLockedCardClick,
        modifier = modifier
    )
}
