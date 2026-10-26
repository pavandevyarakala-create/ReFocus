package com.reeltrak.app.features.analytics.ui

import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.reeltrak.app.core.ui.components.ReFocusTopBar
import com.reeltrak.app.core.ui.theme.ReFocusColors
import com.reeltrak.app.core.ui.theme.ReFocusTheme
import com.reeltrak.app.features.dashboard.ui.components.WeeklyActivityGraph
import com.reeltrak.app.features.dashboard.viewmodel.DashboardViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reeltrak.app.core.ui.components.LockedFeature
import com.reeltrak.app.core.ui.components.LockedFeatureScreen
import com.reeltrak.app.domain.models.AccessState
import com.reeltrak.app.features.settings.viewmodel.SettingsViewModel

@Composable
fun AnalyticsScreen(
    viewModel: DashboardViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel()
) {
    val accessState by settingsViewModel.accessState.collectAsStateWithLifecycle()
    val isAppUnlocked = accessState == AccessState.TRIAL || accessState == AccessState.ACTIVE

    if (!isAppUnlocked) {
        LockedFeatureScreen(
            feature = LockedFeature.ANALYTICS,
            onUpgradeClick = {
                settingsViewModel.openSubscriptionPaywall()
            }
        )
        return
    }

    val weeklyActivityData by viewModel.weeklyActivityData.collectAsStateWithLifecycle()
    val streakStats by viewModel.streakStats.collectAsStateWithLifecycle()
    val totalLifetimeReels by viewModel.totalLifetimeReels.collectAsStateWithLifecycle()
    val avgReelsPerDay by viewModel.avgReelsPerDay.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ReFocusColors.background)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = ReFocusTheme.Dimens.TopBarContentTopOffset, bottom = ReFocusTheme.Dimens.ScreenBottomContentPadding),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
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
            WeeklyActivityGraph(
                weeklyData = weeklyActivityData
            )
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
                        iconTint = ReFocusColors.warning
                    )
                    InsightCard(
                        title = "Lowest Single Day",
                        value = "${streakStats.lowestReelsInDay}",
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.ArrowDownward,
                        iconTint = ReFocusColors.success
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
                        iconTint = ReFocusColors.accent
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
                        iconTint = ReFocusColors.success
                    )
                    InsightCard(
                        title = "Limits Crossed",
                        value = "${streakStats.limitsCrossed}",
                        modifier = Modifier.weight(1f),
                        iconTint = ReFocusColors.warning
                    )
                }
            }
        }
        }

        ReFocusTopBar(
            title = "Analytics",
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(
                    start = ReFocusTheme.Dimens.TopBarHorizontalMargin,
                    end = ReFocusTheme.Dimens.TopBarHorizontalMargin,
                    top = ReFocusTheme.Dimens.TopBarTopMargin
                )
        )
    }
}

@Composable
fun SummaryCard(title: String, value: String, modifier: Modifier = Modifier) {
    val cardShape = RoundedCornerShape(22.dp)
    Card(
        modifier = modifier.shadow(
            elevation = 2.dp,
            shape = cardShape,
            ambientColor = ReFocusColors.shadowAmbient,
            spotColor = ReFocusColors.shadowSpot
        ),
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = ReFocusColors.surfaceElevated),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = ReFocusColors.accent,
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
    val cardShape = RoundedCornerShape(16.dp)
    Card(
        modifier = modifier.shadow(
            elevation = 1.dp,
            shape = cardShape,
            ambientColor = ReFocusColors.shadowAmbient,
            spotColor = ReFocusColors.shadowSpot
        ),
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = ReFocusColors.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
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
