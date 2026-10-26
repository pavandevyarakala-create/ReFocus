package com.reeltrak.app.features.dashboard.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reeltrak.app.core.ui.theme.ReFocusColors
import com.reeltrak.app.features.dashboard.viewmodel.DashboardViewModel.StreakStats

@Composable
fun CurrentStreakCard(
    streakStatsProvider: () -> StreakStats,
    onStreakClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val streakStats = streakStatsProvider()
    val streakCardShape = RoundedCornerShape(16.dp)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 2.dp,
                shape = streakCardShape,
                ambientColor = ReFocusColors.shadowAmbient,
                spotColor = ReFocusColors.shadowSpot
            )
            .clickable(onClick = onStreakClick),
        shape = streakCardShape,
        colors = CardDefaults.cardColors(containerColor = ReFocusColors.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, ReFocusColors.border)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = "Current Streak",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.5.sp
                    ),
                    color = ReFocusColors.textSecondary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "${streakStats.currentStreak}",
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 24.sp
                        ),
                        color = ReFocusColors.textPrimary,
                        modifier = Modifier.alignByBaseline()
                    )
                    Text(
                        text = if (streakStats.currentStreak == 1) "Day" else "Days",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Medium
                        ),
                        color = ReFocusColors.textSecondary,
                        modifier = Modifier.alignByBaseline()
                    )
                }
            }

            val earnedMilestone = when {
                streakStats.disciplineLevel == "Legend" || streakStats.currentStreak >= 365 || streakStats.badge365UnlockTime > 0L -> 365
                streakStats.disciplineLevel == "Master" || streakStats.currentStreak >= 100 || streakStats.badge100UnlockTime > 0L -> 100
                streakStats.disciplineLevel == "Disciplined" || streakStats.currentStreak >= 50 || streakStats.badge50UnlockTime > 0L -> 50
                streakStats.disciplineLevel == "Consistent" || streakStats.currentStreak >= 30 || streakStats.badge30UnlockTime > 0L -> 30
                streakStats.disciplineLevel == "Focused" || streakStats.currentStreak >= 7 || streakStats.badge7UnlockTime > 0L -> 7
                else -> null
            }

            val displayMilestone = earnedMilestone ?: 7
            val isMilestoneUnlocked = earnedMilestone != null

            AchievementBadge(
                milestone = displayMilestone,
                isUnlocked = isMilestoneUnlocked,
                size = 48.dp
            )
        }
    }
}
