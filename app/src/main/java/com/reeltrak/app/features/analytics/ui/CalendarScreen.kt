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
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val habitTrackerData by viewModel.habitTrackerData.collectAsStateWithLifecycle()
    val streakStats by viewModel.streakStats.collectAsStateWithLifecycle()
    
    val hasHistory = remember(habitTrackerData) {
        habitTrackerData.any { it.totalReelsScrolled > 0 || it.dailyGoal > 0 }
    }

    var showDisciplineHub by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        item {
            Text("Calendar & Streaks", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }

        if (!hasHistory) {
            item {
                EmptyStateIllustration()
            }
        } else {
            // 1. TOP STREAK CARD
            item {
                StreakSummaryCard(streakStats)
            }

            // 2. DISCIPLINE LEVEL
            item {
                DisciplineLevelCard(streakStats, onClick = { showDisciplineHub = true })
            }

            // 3. THE CALENDAR
            item {
                GoalCalendar(dailyStats = habitTrackerData)
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
        
        item { Spacer(modifier = Modifier.height(16.dp)) }
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
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
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
private fun StreakSummaryCard(stats: DashboardViewModel.StreakStats) {
    val infiniteTransition = rememberInfiniteTransition(label = "fire")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
        )
    ) {
        Row(
            modifier = Modifier.padding(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("CURRENT STREAK", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${stats.currentStreak}", style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Black)
                    Text(" days", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 12.dp, start = 4.dp))
                }
            }
            
            Box(contentAlignment = Alignment.Center) {
                Surface(
                    modifier = Modifier.size(80.dp).graphicsLayer(scaleX = scale, scaleY = scale),
                    shape = CircleShape,
                    color = Color(0xFFFF5722).copy(alpha = 0.1f)
                ) {}
                Icon(
                    imageVector = Icons.Default.LocalFireDepartment,
                    contentDescription = "Streak",
                    modifier = Modifier.size(56.dp).graphicsLayer(scaleX = scale, scaleY = scale),
                    tint = Color(0xFFFF5722)
                )
            }
        }
    }
}

@Composable
private fun DisciplineLevelCard(stats: DashboardViewModel.StreakStats, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.EmojiEvents, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Discipline Level", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.weight(1f))
                Text(stats.disciplineLevel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            LinearProgressIndicator(
                progress = { stats.progressToNextLevel },
                modifier = Modifier.fillMaxWidth().height(10.dp).clip(CircleShape),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Level up at ${getNextMilestone(stats.longestStreak)} days (Best: ${stats.longestStreak})",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        SmallStatCard("Success Days", "${stats.totalSuccessfulDays}", Modifier.weight(1f))
        SmallStatCard("Skull Days", "💀 ${stats.totalSkullDays}", Modifier.weight(1f))
    }
}

@Composable
private fun SmallStatCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
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
                            if (day.isSkullDay) MaterialTheme.colorScheme.surfaceVariant
                            else if (day.totalReelsScrolled > 0 || day.dailyGoal > 0) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (day.isSkullDay) Text("💀", fontSize = 10.sp)
                }
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
        Spacer(modifier = Modifier.height(12.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            milestones.forEachIndexed { index, milestone ->
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
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isUnlocked) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    border = if (isUnlocked) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)) else null
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        AchievementBadge(
                            milestone = milestone,
                            isUnlocked = isUnlocked,
                            size = 52.dp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = badgeName,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isUnlocked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                        Text(
                            text = if (isUnlocked) "Earned" else "$milestone days",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isUnlocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                        if (isUnlocked) {
                            val formattedDate = remember(unlockTime) {
                                val instant = Instant.ofEpochMilli(unlockTime)
                                val formatter = DateTimeFormatter.ofPattern("d MMM yy").withZone(ZoneId.systemDefault())
                                formatter.format(instant)
                            }
                            Text(
                                text = formattedDate,
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 8.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.padding(top = 2.dp)
                            )
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
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "YOUR STANDINGS",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
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
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
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
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "HOW STREAKS WORK",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "• Stay within your daily limit to build your streak.\n" +
                                "• A small grace zone is allowed for minor limit breaches.\n" +
                                "• Going too far beyond your limit creates a Skull Day.\n" +
                                "• Skull Days reset your current streak to 0.\n" +
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
                                color = if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent,
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
                            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
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
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.15f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "WHY IT MATTERS",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Small daily victories build long-term discipline. Every streak you protect makes it easier to stay in control tomorrow.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
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
