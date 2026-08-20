package com.reeltrak.app.features.dashboard.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.res.stringResource
import com.reeltrak.app.R
import com.reeltrak.app.features.dashboard.viewmodel.DashboardViewModel.StreakStats

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
    streakStatsProvider: () -> StreakStats,
    modifier: Modifier = Modifier
) {
    val dailyIntent = dailyIntentProvider()
    val currentReels = currentReelsProvider()
    val streakStats = streakStatsProvider()

    val isNotSet = dailyIntent == -1
    val isNoLimit = dailyIntent == 0
    val remaining = if (dailyIntent > 0) (dailyIntent - currentReels).coerceAtLeast(0) else 0

    val ratio = if (dailyIntent > 0) currentReels.toFloat() / dailyIntent else 0f

    val statusTheme = when {
        isNotSet -> StatusTheme(
            id = "not_set",
            title = "Limit Not Set",
            subtitle = "Set a daily limit to track your status.",
            icon = Icons.Default.Warning,
            iconColor = MaterialTheme.colorScheme.error,
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
        isNoLimit -> StatusTheme(
            id = "no_limit",
            title = "No Limit Mode",
            subtitle = "Tracking reels without a strict daily limit.",
            icon = Icons.Default.CheckCircle,
            iconColor = MaterialTheme.colorScheme.primary,
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
        currentReels == 0 -> StatusTheme(
            id = "fresh_start",
            title = "Fresh Start",
            subtitle = "No reels watched yet today. Off to a great start!",
            icon = Icons.Default.Bedtime,
            iconColor = Color(0xFF4CAF50),
            containerColor = Color(0xFFE8F5E9)
        )
        ratio < 0.25f -> StatusTheme(
            id = "excellent_control",
            title = "Excellent Control",
            subtitle = "You're managing your reels exceptionally well today.",
            icon = Icons.Default.CheckCircle,
            iconColor = Color(0xFF2E7D32),
            containerColor = Color(0xFFC8E6C9)
        )
        ratio < 0.50f -> StatusTheme(
            id = "on_track",
            title = "On Track",
            subtitle = "Smooth scrolling pace, well within your safe zone.",
            icon = Icons.Default.SentimentSatisfied,
            iconColor = MaterialTheme.colorScheme.primary,
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
        ratio < 0.75f -> StatusTheme(
            id = "stay_focused",
            title = "Stay Focused",
            subtitle = "You've passed half your limit. Mind your time.",
            icon = Icons.AutoMirrored.Filled.DirectionsRun,
            iconColor = Color(0xFF0288D1),
            containerColor = Color(0xFFE1F5FE)
        )
        ratio < 1.0f -> StatusTheme(
            id = "slow_down",
            title = "Slow Down",
            subtitle = "Approaching your daily limit. Consider wrapping up.",
            icon = Icons.Default.Speed,
            iconColor = Color(0xFFED6C02),
            containerColor = Color(0xFFFFF3E0)
        )
        ratio == 1.0f -> StatusTheme(
            id = "limit_reached",
            title = "Limit Reached",
            subtitle = "You've hit your daily reel limit exactly.",
            icon = Icons.Default.Warning,
            iconColor = Color(0xFFD32F2F),
            containerColor = Color(0xFFFFEBEE)
        )
        else -> StatusTheme(
            id = "limit_crossed",
            title = "Limit Crossed",
            subtitle = "You have exceeded your intended reel limit for today.",
            icon = Icons.Default.Error,
            iconColor = Color(0xFFC62828),
            containerColor = Color(0xFFFFCDD2)
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

    val isFirstDay = streakStats.currentStreak <= 1 && streakStats.longestStreak <= 1

    val bodyMessage = when {
        isNotSet -> "Welcome to " + stringResource(id = R.string.app_name) + "! Set a daily reel limit above to start building healthy scrolling habits."
        isNoLimit -> "You've watched $currentReels reels today with no strict daily limit. Monitor your viewing time to stay mindful."
        statusTheme.id == "fresh_start" -> if (isFirstDay) {
            "Welcome! You haven't watched any reels today. Setting your daily goal of $dailyIntent reels is your first step toward mindful viewing."
        } else {
            "Your day is completely reel-free so far. Keep this momentum going to maximize your focus!"
        }
        statusTheme.id == "excellent_control" -> if (isFirstDay) {
            "Great start on day one! You've watched $currentReels of your $dailyIntent reel allowance (${(ratio * 100).toInt()}% of limit)."
        } else {
            "Outstanding control! You've used only ${(ratio * 100).toInt()}% of your daily limit ($currentReels of $dailyIntent reels watched)."
        }
        statusTheme.id == "on_track" -> if (isFirstDay) {
            "You've watched $currentReels reels today. You have $remaining reels left out of your $dailyIntent reel goal."
        } else {
            "You're pacing yourself well. You've used ${(ratio * 100).toInt()}% of your limit ($remaining reels remaining out of $dailyIntent)."
        }
        statusTheme.id == "stay_focused" -> "You've watched $currentReels reels today, using ${(ratio * 100).toInt()}% of your limit. You have $remaining reels left."
        statusTheme.id == "slow_down" -> "You're close to your limit with $currentReels reels watched (${(ratio * 100).toInt()}% used). Only $remaining reels remain today."
        statusTheme.id == "limit_reached" -> "You've reached your exact limit of $dailyIntent reels today (100% used). Great discipline in stopping here!"
        else -> {
            val overage = currentReels - dailyIntent
            "You've watched $currentReels reels today, exceeding your $dailyIntent reel limit by $overage ${if (overage == 1) "reel" else "reels"} (${(ratio * 100).toInt()}% used)."
        }
    }

    val remainingText = if (isNotSet || isNoLimit) "--" else "$remaining Reels"
    val streakText = "${streakStats.currentStreak} Days"

    val recommendationText = when {
        isNotSet -> "Set a daily limit above to protect your time and enable personalized status insights."
        isNoLimit -> "Keep an eye on your usage and set a target when you feel ready."
        statusTheme.id == "fresh_start" -> if (isFirstDay) {
            "Take your time exploring your limits. Taking your first step is key!"
        } else {
            "Enjoy your reel-free day or stick to your planned session limits!"
        }
        statusTheme.id == "excellent_control" -> if (isFirstDay) {
            "Keep up this light pace for the rest of the day to finish your first day strong."
        } else {
            "Keep your current pace. You're on track to finish well below today's limit."
        }
        statusTheme.id == "on_track" -> "Maintain your balanced viewing pace for the rest of the day."
        statusTheme.id == "stay_focused" -> "Be mindful during your next session to stay within your $dailyIntent reel goal."
        statusTheme.id == "slow_down" -> "Pace your remaining $remaining reels carefully for the rest of today."
        statusTheme.id == "limit_reached" -> "Consider wrapping up your scrolling for today to protect your streak."
        else -> "Close your reel apps now to prevent further overscrolling and rebuild momentum tomorrow."
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Top Section (Animated Header & Status Header)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = animatedContainerColor,
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(
                        contentAlignment = androidx.compose.ui.Alignment.Center,
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
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = targetTheme.subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Body Section (Main Message)
            AnimatedContent(
                targetState = bodyMessage,
                transitionSpec = {
                    fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(200))
                },
                label = "body_transition"
            ) { targetBody ->
                Text(
                    text = targetBody,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified
                )
            }

            // Stat Chips Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Chip 1: Remaining
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = "Remaining",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        AnimatedContent(
                            targetState = remainingText,
                            transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(200)) },
                            label = "remaining_anim"
                        ) { targetText ->
                            Text(
                                text = targetText,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                // Chip 2: Current Streak
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = "Current Streak",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        AnimatedContent(
                            targetState = streakText,
                            transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(200)) },
                            label = "streak_anim"
                        ) { targetText ->
                            Text(
                                text = targetText,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                thickness = 1.dp
            )

            // Bottom Section (Recommendation)
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Recommendation",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = animatedIconColor
                )
                AnimatedContent(
                    targetState = recommendationText,
                    transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) },
                    label = "recommendation_anim"
                ) { targetRec ->
                    Text(
                        text = targetRec,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
