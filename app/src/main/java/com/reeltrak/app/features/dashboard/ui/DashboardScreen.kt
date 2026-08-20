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
    viewModel: DashboardViewModel = hiltViewModel(),
    onStreakClick: () -> Unit = {}
) {
    val dailyIntentState = viewModel.dailyIntent.collectAsStateWithLifecycle()
    val reelsScrolledTodayState = viewModel.reelsScrolledToday.collectAsStateWithLifecycle()
    
    val liveState by viewModel.liveSessionState.collectAsStateWithLifecycle()

    val isStrictMode: Boolean? by viewModel.isStrictModeEnabled.collectAsStateWithLifecycle()
    val isStrictModeLocked by viewModel.isStrictModeLocked.collectAsStateWithLifecycle()
    val isLimitChangeRestricted by viewModel.isLimitChangeRestricted.collectAsStateWithLifecycle(initialValue = false)

    var showDailyLimitDialog by remember { mutableStateOf<Int?>(null) }
    var showStrictModeConfirmation by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedSession by remember { mutableStateOf<TimeBlock?>(null) }

    val streakStats by viewModel.streakStats.collectAsStateWithLifecycle()

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
                currentReelsProvider = { reelsScrolledTodayState.value },
                streakStatsProvider = { streakStats },
                isLimitLocked = isLimitChangeRestricted,
                onSaveGoal = { newGoal ->
                    showDailyLimitDialog = newGoal
                },
                onStreakClick = onStreakClick
            )
        }
        item {
            StrictModeCard(
                isStrictModeEnabled = isStrictMode ?: false,
                isLocked = isStrictModeLocked || isStrictMode == true || isStrictMode == null,
                isLoading = isStrictMode == null,
                onToggleStrictMode = { enabled ->
                    if (enabled) {
                        showStrictModeConfirmation = true
                    } else {
                        viewModel.setStrictMode(false)
                    }
                }
            )
        }
        item {
            TodaysStatusCard(
                dailyIntentProvider = { dailyIntentState.value },
                currentReelsProvider = { reelsScrolledTodayState.value },
                streakStatsProvider = { streakStats }
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
    }
    
    // --- Confirmation Dialogs ---

    showDailyLimitDialog?.let { goal ->
        AlertDialog(
            onDismissRequest = { showDailyLimitDialog = null },
            title = { Text("Set Daily Reel Limit?") },
            text = { Text("This will become your default daily limit and will automatically apply every day. You won't need to set it again unless you decide to change it.\n\nLimit changes are allowed only once per calendar day.") },
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

    if (showStrictModeConfirmation) {
        AlertDialog(
            onDismissRequest = { showStrictModeConfirmation = false },
            title = { Text("Enable Strict Mode?") },
            text = {
                Text(
                    "Once enabled, Strict Mode cannot be turned off until the current day ends (daily reset).\n\n" +
                    "• You will not be able to disable it manually.\n" +
                    "• When the daily reel limit is reached, access to Reels/Shorts will be prevented by simulating a Back action.\n\n" +
                    "Only enable this if you are fully committed to your goals."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.setStrictMode(true)
                        showStrictModeConfirmation = false
                    }
                ) {
                    Text("Enable Strict Mode")
                }
            },
            dismissButton = {
                TextButton(onClick = { showStrictModeConfirmation = false }) {
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

    val pendingBadge by viewModel.pendingCelebrationBadge.collectAsStateWithLifecycle()
    pendingBadge?.let { milestone ->
        CelebrationDialog(
            milestone = milestone,
            onDismiss = {
                viewModel.dismissCelebration(milestone)
            },
            onViewCollection = {
                viewModel.dismissCelebration(milestone)
                onStreakClick()
            }
        )
    }
}
