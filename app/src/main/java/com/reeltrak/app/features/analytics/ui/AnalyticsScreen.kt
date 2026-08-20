package com.reeltrak.app.features.analytics.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.reeltrak.app.features.dashboard.ui.components.WeeklyActivityGraph
import com.reeltrak.app.features.dashboard.viewmodel.DashboardViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun AnalyticsScreen(
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val weeklyActivityData by viewModel.weeklyActivityData.collectAsStateWithLifecycle()
    val streakStats by viewModel.streakStats.collectAsStateWithLifecycle()
    val totalLifetimeReels by viewModel.totalLifetimeReels.collectAsStateWithLifecycle()
    val avgReelsPerDay by viewModel.avgReelsPerDay.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        item {
            Text(
                text = "Analytics",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
        }

        // Summary Section
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                SummaryCard(
                    title = "Lifetime Reels",
                    value = totalLifetimeReels.toString(),
                    modifier = Modifier.weight(1f)
                )
                SummaryCard(
                    title = "Daily Average",
                    value = avgReelsPerDay.toString(),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Redesigned Weekly Graph
        item {
            WeeklyActivityGraph(weeklyData = weeklyActivityData)
        }

        // Additional Stats Grid
        item {
            Text(
                text = "Detailed Insights",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    InsightCard(
                        title = "Current Streak",
                        value = "${streakStats.currentStreak} Days",
                        modifier = Modifier.weight(1f)
                    )
                    InsightCard(
                        title = "Longest Streak",
                        value = "${streakStats.longestStreak} Days",
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    InsightCard(
                        title = "Highest Single Day",
                        value = "${streakStats.highestReelsInDay}",
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.ArrowUpward,
                        iconTint = MaterialTheme.colorScheme.error
                    )
                    InsightCard(
                        title = "Lowest Single Day",
                        value = "${streakStats.lowestReelsInDay}",
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.ArrowDownward,
                        iconTint = Color(0xFF2E7D32)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    InsightCard(
                        title = "Weekly Total",
                        value = "${streakStats.weeklyTotalReels}",
                        modifier = Modifier.weight(1f)
                    )
                    InsightCard(
                        title = "Weekly Trend",
                        value = streakStats.weeklyTrend,
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.TrendingUp,
                        iconTint = MaterialTheme.colorScheme.primary
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    InsightCard(
                        title = "Success Rate",
                        value = "${(streakStats.successRate * 100).toInt()}%",
                        modifier = Modifier.weight(1f)
                    )
                    InsightCard(
                        title = "Days Tracked",
                        value = "${streakStats.totalDaysTracked}",
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    InsightCard(
                        title = "Limits Followed",
                        value = "${streakStats.limitsFollowed}",
                        modifier = Modifier.weight(1f),
                        iconTint = Color(0xFF2E7D32)
                    )
                    InsightCard(
                        title = "Limits Crossed",
                        value = "${streakStats.limitsCrossed}",
                        modifier = Modifier.weight(1f),
                        iconTint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        item { Spacer(modifier = Modifier.height(32.dp)) }
    }
}

@Composable
fun SummaryCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black
            )
        }
    }
}

@Composable
fun InsightCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = iconTint
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
