package com.reeltrak.app.features.analytics.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.BorderStroke
import com.reeltrak.app.features.dashboard.ui.components.GoalCalendar
import com.reeltrak.app.features.dashboard.ui.components.AchievementBadge
import com.reeltrak.app.features.dashboard.viewmodel.DashboardViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.reeltrak.app.core.ui.components.ReFocusTopBar
import com.reeltrak.app.core.ui.theme.ReFocusColors
import com.reeltrak.app.core.ui.theme.ReFocusTheme

import com.reeltrak.app.core.ui.components.LockedFeature
import com.reeltrak.app.core.ui.components.LockedFeatureScreen
import com.reeltrak.app.domain.models.AccessState
import com.reeltrak.app.features.settings.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    viewModel: DashboardViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel()
) {
    val accessState by settingsViewModel.accessState.collectAsStateWithLifecycle()
    val isAppUnlocked = accessState == AccessState.TRIAL || accessState == AccessState.ACTIVE

    if (!isAppUnlocked) {
        LockedFeatureScreen(
            feature = LockedFeature.CALENDAR,
            onUpgradeClick = {
                settingsViewModel.openSubscriptionPaywall()
            }
        )
        return
    }

    val habitTrackerData by viewModel.habitTrackerData.collectAsStateWithLifecycle()
    val calendarMonthData by viewModel.calendarMonthData.collectAsStateWithLifecycle()
    val streakStats by viewModel.streakStats.collectAsStateWithLifecycle()
    val hasHistory by viewModel.hasAnyHistory.collectAsStateWithLifecycle()

    var showDisciplineHub by remember { mutableStateOf(false) }

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
            if (!hasHistory) {
            item {
                EmptyStateIllustration()
            }
        } else {
            // 1. EDITORIAL STREAK & DISCIPLINE HERO (Directly on canvas)
            item {
                StreakEditorialHeader(
                    stats = streakStats,
                    onOpenDisciplineHub = { showDisciplineHub = true }
                )
            }

            // 2. THE CALENDAR (Primary physical surface)
            item {
                GoalCalendar(
                    dailyStats = calendarMonthData,
                    onMonthChanged = { viewModel.setCalendarMonth(it) }
                )
            }

            // 4. STATS GRID
            item {
                StreakStatsGrid(streakStats)
            }
            
            // 5. SUCCESS TIMELINE (Last 14 Days)
            item {
                SuccessTimeline(habitTrackerData.takeLast(14))
            }
        }
        }

        ReFocusTopBar(
            title = "Calendar",
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(
                    start = ReFocusTheme.Dimens.TopBarHorizontalMargin,
                    end = ReFocusTheme.Dimens.TopBarHorizontalMargin,
                    top = ReFocusTheme.Dimens.TopBarTopMargin
                )
        )
    }

    if (showDisciplineHub) {
        DisciplineHubBottomSheet(
            stats = streakStats,
            onDismiss = { showDisciplineHub = false }
        )
    }
}

@Composable
private fun EmptyStateIllustration() {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Icon(
                Icons.Default.History, 
                contentDescription = null, 
                modifier = Modifier.size(80.dp),
                tint = ReFocusColors.accent.copy(alpha = 0.4f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text("Start your first streak today", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Complete a day within your limit to see your progress here.", 
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun StreakEditorialHeader(
    stats: DashboardViewModel.StreakStats,
    onOpenDisciplineHub: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "fire")
    val scale = infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Dominant Streak Metric (Directly on canvas)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "CURRENT FOCUS STREAK",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                    color = ReFocusColors.accent
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = "${stats.currentStreak}",
                        fontSize = 54.sp,
                        lineHeight = 56.sp,
                        fontWeight = FontWeight.Black,
                        color = ReFocusColors.textPrimary
                    )
                    Text(
                        text = if (stats.currentStreak == 1) " day" else " days",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = ReFocusColors.textSecondary,
                        modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
                    )
                }
            }

            // Flame Disc with Authentic Warm Fire Animation
            val isStreakActive = stats.currentStreak > 0
            val flameColor = ReFocusColors.streakFlame
            val flameGlowBg = if (isStreakActive) ReFocusColors.streakFlameGlow else ReFocusColors.surfaceStrong
            val flameIconTint = if (isStreakActive) flameColor else flameColor.copy(alpha = 0.45f)

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(64.dp)
                    .graphicsLayer {
                        if (isStreakActive) {
                            scaleX = scale.value
                            scaleY = scale.value
                        }
                    }
                    .background(color = flameGlowBg, shape = CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.LocalFireDepartment,
                    contentDescription = "Streak Fire",
                    modifier = Modifier.size(38.dp),
                    tint = flameIconTint
                )
            }
        }

        // 2. Secondary Discipline Information (Clean horizontal bar, border removed)
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .clickable(onClick = onOpenDisciplineHub),
            shape = RoundedCornerShape(14.dp),
            color = ReFocusColors.surfaceElevated
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.EmojiEvents,
                            contentDescription = null,
                            tint = ReFocusColors.accent,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Level: ${stats.disciplineLevel}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = ReFocusColors.textPrimary
                        )
                    }

                    Text(
                        text = "Discipline Hub ›",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = ReFocusColors.accent
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                LinearProgressIndicator(
                    progress = { stats.progressToNextLevel },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(CircleShape),
                    color = ReFocusColors.accent,
                    trackColor = ReFocusColors.progressTrack
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "Next milestone at ${getNextMilestone(stats.longestStreak)} days (Best: ${stats.longestStreak}d)",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 11.sp,
                    color = ReFocusColors.textMuted
                )
            }
        }
    }
}

private fun getNextMilestone(longest: Int): Int = when {
    longest < 7 -> 7
    longest < 30 -> 30
    longest < 50 -> 50
    longest < 100 -> 100
    else -> 365
}

@Composable
private fun StreakStatsGrid(stats: DashboardViewModel.StreakStats) {
    // Sits directly on background canvas - not another bordered card
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "SUCCESS DAYS",
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 1.2.sp,
                fontWeight = FontWeight.Bold,
                color = ReFocusColors.textMuted
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${stats.totalSuccessfulDays}",
                fontSize = 28.sp,
                lineHeight = 30.sp,
                fontWeight = FontWeight.ExtraBold,
                color = ReFocusColors.textPrimary
            )
        }

        Box(
            modifier = Modifier
                .width(1.dp)
                .height(32.dp)
                .background(ReFocusColors.border.copy(alpha = 0.5f))
        )

        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "DRIFTED DAYS",
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 1.2.sp,
                fontWeight = FontWeight.Bold,
                color = ReFocusColors.textMuted
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${stats.totalDriftedDays}",
                fontSize = 28.sp,
                lineHeight = 30.sp,
                fontWeight = FontWeight.ExtraBold,
                color = if (stats.totalDriftedDays > 0) ReFocusColors.error else ReFocusColors.textPrimary
            )
        }
    }
}

@Composable
private fun SuccessTimeline(recentData: List<DashboardViewModel.DailyBlocks>) {
    Column {
        Text("Success Timeline", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            recentData.forEach { day ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(30.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            if (day.isDriftedDay) ReFocusColors.error
                            else if (day.isUnrestrictedDay) ReFocusColors.warning
                            else if (day.totalReelsScrolled > 0 || day.dailyGoal > 0) ReFocusColors.accent
                            else ReFocusColors.chartTrack
                        )
                )
            }
        }
    }
}

@Composable
private fun BadgeCollectionSection(stats: DashboardViewModel.StreakStats) {
    val milestones = listOf(7, 30, 50, 100, 365)
    val unlockTimes = listOf(
        stats.badge7UnlockTime,
        stats.badge30UnlockTime,
        stats.badge50UnlockTime,
        stats.badge100UnlockTime,
        stats.badge365UnlockTime
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Achievement Badges",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(14.dp))
        
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val columns = if (maxWidth > 520.dp) 3 else 2
            val chunkedMilestones = remember(columns) { milestones.chunked(columns) }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                chunkedMilestones.forEach { rowItems ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Max),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        rowItems.forEach { milestone ->
                            val index = milestones.indexOf(milestone)
                            val unlockTime = unlockTimes[index]
                            val isUnlocked = unlockTime > 0L
                            val badgeName = when (milestone) {
                                7 -> "Focused"
                                30 -> "Consistent"
                                50 -> "Disciplined"
                                100 -> "Master"
                                365 -> "Legend"
                                else -> ""
                            }

                            Card(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isUnlocked) {
                                        ReFocusColors.surfaceElevated
                                    } else {
                                        ReFocusColors.surface
                                    }
                                ),
                                shape = RoundedCornerShape(16.dp),
                                border = if (isUnlocked) {
                                    BorderStroke(1.dp, ReFocusColors.accent.copy(alpha = 0.4f))
                                } else null
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    AchievementBadge(
                                        milestone = milestone,
                                        isUnlocked = isUnlocked,
                                        size = 64.dp
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text(
                                        text = badgeName,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center,
                                        color = if (isUnlocked) {
                                            MaterialTheme.colorScheme.onSurface
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                        }
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = if (isUnlocked) "Earned" else "$milestone days",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = if (isUnlocked) FontWeight.SemiBold else FontWeight.Normal,
                                        textAlign = TextAlign.Center,
                                        color = if (isUnlocked) {
                                            ReFocusColors.accent
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                        }
                                    )
                                    if (isUnlocked) {
                                        val formattedDate = remember(unlockTime) {
                                            val instant = Instant.ofEpochMilli(unlockTime)
                                            val formatter = DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneId.systemDefault())
                                            formatter.format(instant)
                                        }
                                        Text(
                                            text = formattedDate,
                                            style = MaterialTheme.typography.labelSmall,
                                            textAlign = TextAlign.Center,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
                                    }
                                }
                            }
                        }

                        repeat(columns - rowItems.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DisciplineHubBottomSheet(
    stats: DashboardViewModel.StreakStats,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = ReFocusColors.background,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Title Header
            Text(
                text = "Discipline Hub",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onSurface
            )

            // Section 1: Current Discipline Profile Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = ReFocusColors.surfaceElevated)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "YOUR STANDINGS",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = ReFocusColors.accent
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stats.disciplineLevel,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Black
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Column {
                            Text("Current Streak", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${stats.currentStreak} Days", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        Column {
                            Text("Longest Streak", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${stats.longestStreak} Days", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    LinearProgressIndicator(
                        progress = { stats.progressToNextLevel },
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
                        color = ReFocusColors.accent,
                        trackColor = ReFocusColors.progressTrack
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    val nextMilestone = getNextMilestone(stats.longestStreak)
                    val daysRemaining = nextMilestone - stats.longestStreak
                    val motivationText = if (daysRemaining > 0) {
                        "You're only $daysRemaining days away from becoming ${getLevelName(nextMilestone)}. Keep going!"
                    } else {
                        "You've reached the highest Discipline Level! Keep protecting your streak."
                    }
                    Text(
                        text = motivationText,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Section 2: How Streaks Work
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = ReFocusColors.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "HOW STREAKS WORK",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = ReFocusColors.accent
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "• Stay within your daily limit to build your streak.\n" +
                                "• A small grace zone is allowed for minor limit breaches.\n" +
                                "• Exceeding the grace zone makes the day a Drifted day.\n" +
                                "• Drifted days reset your current streak to 0.\n" +
                                "• Your highest streak is always remembered and determines your Level.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 22.sp
                    )
                }
            }

            // Section 3: Stepper/Timeline of Discipline Levels
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Discipline Levels",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(12.dp))
                val levelsList = listOf(
                    Triple("Seedling", "beginner", "Seedling"),
                    Triple("Focused", "7+ Days", "Focused"),
                    Triple("Consistent", "30+ Days", "Consistent"),
                    Triple("Disciplined", "50+ Days", "Disciplined"),
                    Triple("Master", "100+ Days", "Master"),
                    Triple("Legend", "365+ Days", "Legend")
                )
                levelsList.forEach { (label, range, name) ->
                    val isCurrent = stats.disciplineLevel == name
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .background(
                                color = if (isCurrent) ReFocusColors.accentSoft else Color.Transparent,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = range,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isCurrent) ReFocusColors.accent else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            // Section 4: Achievement Badges Collection
            BadgeCollectionSection(stats = stats)

            // Section 5: Why It Matters
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = ReFocusColors.surfaceElevated)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "WHY IT MATTERS",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = ReFocusColors.accent
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Small daily victories build long-term discipline. Every streak you protect makes it easier to stay in control tomorrow.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

private fun getLevelName(milestone: Int): String = when (milestone) {
    7 -> "Focused"
    30 -> "Consistent"
    50 -> "Disciplined"
    100 -> "Master"
    365 -> "Legend"
    else -> "Seedling"
}
