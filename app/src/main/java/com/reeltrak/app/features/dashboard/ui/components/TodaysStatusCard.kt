package com.reeltrak.app.features.dashboard.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.SentimentSatisfied
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.reeltrak.app.features.dashboard.viewmodel.DashboardViewModel.StreakStats
import com.reeltrak.app.core.ui.theme.ReFocusColors
import com.reeltrak.app.core.ui.theme.ReFocusTheme

private data class StatusTheme(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val iconColor: Color,
    val containerColor: Color
)

@Composable
fun TodaysStatusCard(
    dailyIntentProvider: () -> Int,
    currentReelsProvider: () -> Int,
    streakStatsProvider: () -> StreakStats = { StreakStats() },
    modifier: Modifier = Modifier
) {
    val dailyIntent = dailyIntentProvider()
    val currentReels = currentReelsProvider()
    val streakStats = streakStatsProvider()

    val isNotSet = dailyIntent == -1
    val isNoLimit = dailyIntent == 0
    val remaining = if (dailyIntent > 0) (dailyIntent - currentReels).coerceAtLeast(0) else 0
    val overage = if (dailyIntent > 0 && currentReels > dailyIntent) currentReels - dailyIntent else 0

    val ratio = if (dailyIntent > 0) currentReels.toFloat() / dailyIntent else 0f
    val isDark = ReFocusTheme.colors.isDark

    val statusTheme = when {
        isNotSet -> StatusTheme(
            id = "not_set",
            title = "Limit Not Set",
            subtitle = "Set a daily limit to track progress",
            icon = Icons.Default.Warning,
            iconColor = ReFocusColors.info,
            containerColor = ReFocusColors.infoSoft
        )
        isNoLimit -> StatusTheme(
            id = "no_limit",
            title = "No Limit Mode",
            subtitle = "Open tracking active",
            icon = Icons.Default.CheckCircle,
            iconColor = ReFocusColors.accent,
            containerColor = ReFocusColors.accentSoft
        )
        currentReels == 0 -> StatusTheme(
            id = "fresh_start",
            title = "Fresh Start",
            subtitle = "Zero reels watched today",
            icon = Icons.Default.Bedtime,
            iconColor = ReFocusColors.accent,
            containerColor = ReFocusColors.accentSoft
        )
        ratio < 0.25f -> StatusTheme(
            id = "excellent_control",
            title = "Excellent Control",
            subtitle = "Well within daily limit",
            icon = Icons.Default.CheckCircle,
            iconColor = ReFocusColors.success,
            containerColor = ReFocusColors.successSoft
        )
        ratio < 0.50f -> StatusTheme(
            id = "on_track",
            title = "On Track",
            subtitle = "Pacing well within limit",
            icon = Icons.Default.SentimentSatisfied,
            iconColor = ReFocusColors.accent,
            containerColor = ReFocusColors.accentSoft
        )
        ratio < 0.75f -> StatusTheme(
            id = "stay_focused",
            title = "Stay Focused",
            subtitle = "Past halfway mark for today",
            icon = Icons.AutoMirrored.Filled.DirectionsRun,
            iconColor = ReFocusColors.info,
            containerColor = ReFocusColors.infoSoft
        )
        ratio < 1.0f -> StatusTheme(
            id = "approaching_limit",
            title = "Approaching Limit",
            subtitle = "$remaining reels remaining",
            icon = Icons.Default.Speed,
            iconColor = ReFocusColors.warning,
            containerColor = ReFocusColors.warningSoft
        )
        ratio == 1.0f -> StatusTheme(
            id = "limit_reached",
            title = "Limit Reached",
            subtitle = "Daily limit reached",
            icon = Icons.Default.Warning,
            iconColor = ReFocusColors.warning,
            containerColor = ReFocusColors.warningSoft
        )
        else -> StatusTheme(
            id = "limit_crossed",
            title = "Limit Reached",
            subtitle = "+$overage over daily limit",
            icon = Icons.Default.Warning,
            iconColor = ReFocusColors.warning,
            containerColor = ReFocusColors.warningSoft
        )
    }

    val animatedContainerColor by animateColorAsState(
        targetValue = statusTheme.containerColor,
        animationSpec = tween(durationMillis = 350),
        label = "container_color_anim"
    )

    val animatedIconColor by animateColorAsState(
        targetValue = statusTheme.iconColor,
        animationSpec = tween(durationMillis = 350),
        label = "icon_color_anim"
    )

    val remainingText = if (isNotSet || isNoLimit) "--" else if (remaining == 0) "0 Reels" else "$remaining Reels"
    val streakText = "${streakStats.currentStreak} Days"

    val helperNote = when {
        isNotSet -> "Set a daily limit above to protect your time."
        isNoLimit -> "Mind your screen time across scrolling sessions."
        currentReels > dailyIntent -> "Limit reached. Close reel apps to protect focus."
        ratio == 1.0f -> "Daily limit reached. Wrap up scrolling for today."
        ratio >= 0.75f -> "Approaching limit. Mind your remaining reels."
        ratio >= 0.50f -> "Halfway through limit. Pace your sessions."
        ratio > 0f -> "Healthy pacing. Well within your daily target."
        else -> "Off to a focused, disciplined start today."
    }

    val statusCardShape = RoundedCornerShape(20.dp)
    Card(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 2.dp,
                shape = statusCardShape,
                ambientColor = ReFocusColors.shadowAmbient,
                spotColor = ReFocusColors.shadowSpot
            ),
        shape = statusCardShape,
        colors = CardDefaults.cardColors(containerColor = ReFocusColors.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Top Section (Status Icon, Title & Subtitle)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = animatedContainerColor,
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        AnimatedContent(
                            targetState = statusTheme,
                            transitionSpec = {
                                (fadeIn(animationSpec = tween(300)) + scaleIn(initialScale = 0.8f, animationSpec = tween(300))) togetherWith
                                (fadeOut(animationSpec = tween(200)) + scaleOut(targetScale = 0.8f, animationSpec = tween(200)))
                            },
                            label = "icon_transition"
                        ) { targetTheme ->
                            Icon(
                                imageVector = targetTheme.icon,
                                contentDescription = "Status Icon",
                                tint = animatedIconColor,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }

                AnimatedContent(
                    targetState = statusTheme,
                    transitionSpec = {
                        (fadeIn(animationSpec = tween(300)) + slideInVertically { height -> height / 2 }) togetherWith
                        (fadeOut(animationSpec = tween(200)) + slideOutVertically { height -> -height / 2 })
                    },
                    modifier = Modifier.weight(1f),
                    label = "text_transition"
                ) { targetTheme ->
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = targetTheme.title,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = ReFocusColors.textPrimary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = targetTheme.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = ReFocusColors.textSecondary
                        )
                    }
                }
            }

            if (dailyIntent > 0) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = ReFocusColors.surfaceElevated
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Remaining Today",
                            style = MaterialTheme.typography.labelMedium,
                            color = ReFocusColors.textSecondary
                        )
                        Text(
                            text = remainingText,
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = ReFocusColors.textPrimary
                        )
                    }
                }
            }

            HorizontalDivider(
                color = ReFocusColors.divider,
                thickness = 1.dp
            )

            // Bottom Helper Note (Single clean line, no wordy paragraph)
            AnimatedContent(
                targetState = helperNote,
                transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) },
                label = "note_anim"
            ) { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
