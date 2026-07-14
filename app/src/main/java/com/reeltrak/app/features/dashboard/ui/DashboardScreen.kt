package com.reeltrak.app.features.dashboard.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.foundation.background
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.reeltrak.app.features.dashboard.ui.components.*
import com.reeltrak.app.features.dashboard.viewmodel.DashboardViewModel
import com.reeltrak.app.services.tracking.SessionState
import com.reeltrak.app.features.dashboard.viewmodel.DashboardViewModel.TimeBlock

import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val dailyIntentState = viewModel.dailyIntent.collectAsStateWithLifecycle()
    val reelsScrolledTodayState = viewModel.reelsScrolledToday.collectAsStateWithLifecycle()
    val totalLifetimeReels by viewModel.totalLifetimeReels.collectAsStateWithLifecycle()
    val avgReelsPerDay by viewModel.avgReelsPerDay.collectAsStateWithLifecycle()
    
    val weeklyActivityData by viewModel.weeklyActivityData.collectAsStateWithLifecycle()
    val habitTrackerData by viewModel.habitTrackerData.collectAsStateWithLifecycle()
    val liveState by viewModel.liveSessionState.collectAsStateWithLifecycle()

    val isStrictMode by viewModel.isStrictModeEnabled.collectAsStateWithLifecycle()
    val isStrictModeLocked by viewModel.isStrictModeLocked.collectAsStateWithLifecycle()
    val isDailyLimitLocked by viewModel.isDailyLimitLocked.collectAsStateWithLifecycle()

    var showStrictModeDialog by remember { mutableStateOf(false) }
    var showDailyLimitDialog by remember { mutableStateOf<Int?>(null) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedSession by remember { mutableStateOf<TimeBlock?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp)
    ) {
        item {
            HeroProgressCard(
                dailyIntentProvider = { dailyIntentState.value }, 
                currentReelsProvider = { reelsScrolledTodayState.value }
            )
        }
        item {
            StrictModeCard(
                isStrictModeEnabled = isStrictMode,
                isLocked = isStrictModeLocked,
                onToggleStrictMode = { 
                    if (it) {
                        showStrictModeDialog = true
                    } else {
                        // Logic already prevents turning it off if locked
                        viewModel.setStrictMode(false)
                    }
                }
            )
        }
        item {
            TodayGoalCard(
                currentSavedGoalProvider = { dailyIntentState.value },
                isLocked = isDailyLimitLocked,
                onSaveGoal = { newGoal ->
                    showDailyLimitDialog = newGoal
                }
            )
        }
        item {
            if (liveState.sessionState == SessionState.ACTIVE || liveState.sessionState == SessionState.ENTERING_REELS || liveState.sessionState == SessionState.EXITING) {
                LiveSessionCard(
                    currentApp = liveState.currentApp,
                    sessionStartTime = liveState.sessionStartTime,
                    reelsScrolled = liveState.currentSessionReels
                )
            }
        }
        item {
            WeeklyActivityGraph(
                weeklyData = weeklyActivityData,
                onSessionClick = { session ->
                    selectedSession = session
                }
            )
        }
        item {
            GoalCalendar(dailyStats = habitTrackerData)
        }
        item {
            LifetimeStatsGrid(
                lifetimeReels = totalLifetimeReels,
                avgReels = avgReelsPerDay
            )
        }
    }
    
    // --- Confirmation Dialogs ---

    if (showStrictModeDialog) {
        AlertDialog(
            onDismissRequest = { showStrictModeDialog = false },
            title = { Text("Enable Strict Mode?") },
            text = { Text("Once enabled, Strict Mode cannot be turned off until today's reset at 12:00 AM.\n\nAre you sure you want to continue?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.setStrictMode(true)
                        showStrictModeDialog = false
                    }
                ) {
                    Text("Enable")
                }
            },
            dismissButton = {
                TextButton(onClick = { showStrictModeDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    showDailyLimitDialog?.let { goal ->
        AlertDialog(
            onDismissRequest = { showDailyLimitDialog = null },
            title = { Text("Set Today's Reel Limit?") },
            text = { Text("Once saved, today's reel limit cannot be changed until the daily reset at 12:00 AM.\n\nAre you sure you want to continue?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.confirmAndSetDailyGoal(goal)
                        showDailyLimitDialog = null
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDailyLimitDialog = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    selectedSession?.let { session ->
        ModalBottomSheet(
            onDismissRequest = { selectedSession = null },
            sheetState = sheetState
        ) {
            SessionDetailSheet(session = session)
        }
    }
}
