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
import com.reeltrak.app.core.tour.LocalTourCoordinator
import com.reeltrak.app.core.tour.TourTargets
import com.reeltrak.app.core.tour.tourTarget

import android.app.Activity
import android.content.ContextWrapper
import android.widget.Toast
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import com.reeltrak.app.core.ui.components.DailyLimitEntitlementDialog
import com.reeltrak.app.core.ui.components.ReFocusTopBar
import com.reeltrak.app.core.ui.theme.ReFocusColors
import com.reeltrak.app.core.ui.theme.ReFocusTheme
import com.reeltrak.app.data.security.authorization.OperationalMode

import kotlinx.coroutines.Job

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel = hiltViewModel(),
    onStreakClick: () -> Unit = {},
    onOpenSubscription: () -> Unit = {},
    onNavigateToLoginForUpgrade: () -> Unit = {},
    onProfileClick: () -> Unit = {},
    userProfilePictureUri: String? = null,
    userDisplayName: String? = null
) {
    val context = LocalContext.current
    val activity = remember(context) {
        var ctx = context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) break
            ctx = ctx.baseContext
        }
        ctx as? Activity
    }

    val isAppUnlocked by viewModel.isAppUnlocked.collectAsStateWithLifecycle()
    val isAdLoading by viewModel.isAdLoading.collectAsStateWithLifecycle()
    val operationalMode by viewModel.operationalMode.collectAsStateWithLifecycle()
    val isDailyLimitEntitled by viewModel.isDailyLimitEntitled.collectAsStateWithLifecycle()

    var isAdExecuting by remember { mutableStateOf(false) }
    var activeAdJob by remember { mutableStateOf<Job?>(null) }
    var showDailyLimitEntitlementDialog by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            activeAdJob?.cancel()
            activeAdJob = null
            isAdExecuting = false
        }
    }

    val dailyIntentState = viewModel.dailyIntent.collectAsStateWithLifecycle()
    val reelsScrolledTodayState = viewModel.reelsScrolledToday.collectAsStateWithLifecycle()
    
    val liveState by viewModel.liveSessionState.collectAsStateWithLifecycle()

    val isStrictMode: Boolean? by viewModel.isStrictModeEnabled.collectAsStateWithLifecycle()
    val isStrictModeLocked by viewModel.isStrictModeLocked.collectAsStateWithLifecycle()
    val strictModeDailyState by viewModel.strictModeDailyState.collectAsStateWithLifecycle()
    val isLimitChangeRestricted by viewModel.isLimitChangeRestricted.collectAsStateWithLifecycle(initialValue = false)

    var showStrictModeInfoDialog by remember { mutableStateOf(false) }
    var showTurnOffStrictModeDialog by remember { mutableStateOf(false) }
    var turnOffAdErrorMessage by remember { mutableStateOf<String?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedSession by remember { mutableStateOf<TimeBlock?>(null) }

    val streakStats by viewModel.streakStats.collectAsStateWithLifecycle()

    val tourCoordinator = LocalTourCoordinator.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ReFocusColors.background)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(top = ReFocusTheme.Dimens.TopBarContentTopOffset, bottom = ReFocusTheme.Dimens.ScreenBottomContentPadding)
        ) {
            item {
                HeroProgressCard(
                    dailyIntentProvider = { dailyIntentState.value }, 
                    currentReelsProvider = { reelsScrolledTodayState.value },
                    streakStatsProvider = { streakStats },
                    isLimitLocked = isLimitChangeRestricted,
                    isSaving = isAdExecuting,
                    isEntitled = isDailyLimitEntitled,
                    onDailyLimitLockedClick = {
                        showDailyLimitEntitlementDialog = true
                    },
                    onSaveGoal = { newGoal ->
                        // FREE USER BEHAVIOR:
                        // If user is not entitled (UNAUTHENTICATED_FREE / AUTHENTICATED_FREE), Daily Limit remains locked.
                        // Show subscription paywall / login required dialog, DO NOT play rewarded ad.
                        if (!isDailyLimitEntitled) {
                            showDailyLimitEntitlementDialog = true
                            return@HeroProgressCard
                        }

                        // TRIAL / PREMIUM USER BEHAVIOR:
                        // Directly trigger Rewarded Ad without any intermediate confirmation or explanation popup.
                        val currentActivity = activity
                        if (currentActivity == null) {
                            Toast.makeText(context, "Unable to present ad. Please try again.", Toast.LENGTH_SHORT).show()
                            return@HeroProgressCard
                        }

                        if (isAdExecuting) return@HeroProgressCard
                        isAdExecuting = true

                        activeAdJob = viewModel.showRewardedAd(
                            activity = currentActivity,
                            onRewardEarned = {
                                activeAdJob = null
                                isAdExecuting = false
                                viewModel.confirmAndSetDailyGoal(newGoal)
                                Toast.makeText(context, "Daily Limit updated successfully!", Toast.LENGTH_SHORT).show()
                            },
                            onDismissedWithoutReward = {
                                activeAdJob = null
                                isAdExecuting = false
                                Toast.makeText(context, "Watch the full video to save your daily limit.", Toast.LENGTH_SHORT).show()
                            },
                            onError = { _ ->
                                activeAdJob = null
                                isAdExecuting = false
                                Toast.makeText(context, "Ad unavailable right now. Please try again later.", Toast.LENGTH_LONG).show()
                            }
                        )
                    },
                    onStreakClick = onStreakClick,
                    modifier = Modifier.tourTarget(TourTargets.HERO_PROGRESS_CARD, tourCoordinator)
                )
            }
        item {
            StrictModeCard(
                dailyState = strictModeDailyState,
                isEntitled = isAppUnlocked,
                isLoading = (isAppUnlocked && (isStrictMode == null)) || isAdExecuting,
                onTurnOffClick = {
                    if (!isAppUnlocked) {
                        showStrictModeInfoDialog = true
                        return@StrictModeCard
                    }
                    turnOffAdErrorMessage = null
                    showTurnOffStrictModeDialog = true
                },
                onReactivateClick = {
                    // FREE USER BEHAVIOR:
                    if (!isAppUnlocked) {
                        showStrictModeInfoDialog = true
                        return@StrictModeCard
                    }

                    // TRIAL / PREMIUM USER BEHAVIOR:
                    // Directly trigger Rewarded Ad without any intermediate confirmation or explanation popup.
                    val currentActivity = activity
                    if (currentActivity == null) {
                        Toast.makeText(context, "Unable to present ad. Please try again.", Toast.LENGTH_SHORT).show()
                        return@StrictModeCard
                    }

                    if (isAdExecuting) return@StrictModeCard
                    isAdExecuting = true

                    activeAdJob = viewModel.turnOnStrictModeWithAd(
                        activity = currentActivity,
                        onSuccess = {
                            activeAdJob = null
                            isAdExecuting = false
                            Toast.makeText(context, "Strict Mode reactivated!", Toast.LENGTH_SHORT).show()
                        },
                        onDismissedWithoutReward = {
                            activeAdJob = null
                            isAdExecuting = false
                            Toast.makeText(context, "Strict Mode was not reactivated.", Toast.LENGTH_SHORT).show()
                        },
                        onError = { _ ->
                            activeAdJob = null
                            isAdExecuting = false
                            Toast.makeText(context, "Unable to reactivate right now. Please try again later.", Toast.LENGTH_LONG).show()
                        }
                    )
                },
                onLockedCardClick = {
                    showStrictModeInfoDialog = true
                },
                modifier = Modifier.tourTarget(TourTargets.STRICT_MODE_CARD, tourCoordinator)
            )
        }
        item {
            TodaysStatusCard(
                dailyIntentProvider = { dailyIntentState.value },
                currentReelsProvider = { reelsScrolledTodayState.value },
                streakStatsProvider = { streakStats },
                modifier = Modifier.tourTarget(TourTargets.LIVE_TODAYS_STATUS, tourCoordinator)
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

        ReFocusTopBar(
            title = "Dashboard",
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(
                    start = ReFocusTheme.Dimens.TopBarHorizontalMargin,
                    end = ReFocusTheme.Dimens.TopBarHorizontalMargin,
                    top = ReFocusTheme.Dimens.TopBarTopMargin
                )
        )
    }
    
    // --- Confirmation Dialogs ---

    if (showDailyLimitEntitlementDialog) {
        val isUnauthenticated = operationalMode == OperationalMode.UNAUTHENTICATED_FREE
        DailyLimitEntitlementDialog(
            isUnauthenticated = isUnauthenticated,
            onDismiss = {
                showDailyLimitEntitlementDialog = false
            },
            onPrimaryAction = {
                showDailyLimitEntitlementDialog = false
                if (isUnauthenticated) {
                    onNavigateToLoginForUpgrade()
                } else {
                    onOpenSubscription()
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

    if (showStrictModeInfoDialog) {
        val isUnauthenticated = operationalMode == OperationalMode.UNAUTHENTICATED_FREE
        StrictModeInfoDialog(
            onDismissRequest = { showStrictModeInfoDialog = false },
            onGetPremiumClick = {
                showStrictModeInfoDialog = false
                if (isUnauthenticated) {
                    onNavigateToLoginForUpgrade()
                } else {
                    onOpenSubscription()
                }
            }
        )
    }

    if (showTurnOffStrictModeDialog) {
        val currentActivity = activity
        StrictModeTurnOffDialog(
            isLoading = isAdExecuting,
            errorMessage = turnOffAdErrorMessage,
            onDismissRequest = {
                activeAdJob?.cancel()
                activeAdJob = null
                isAdExecuting = false
                showTurnOffStrictModeDialog = false
                turnOffAdErrorMessage = null
            },
            onWatchAdClick = {
                if (currentActivity == null) {
                    Toast.makeText(context, "Unable to present ad. Please try again.", Toast.LENGTH_SHORT).show()
                    return@StrictModeTurnOffDialog
                }
                if (isAdExecuting) return@StrictModeTurnOffDialog
                isAdExecuting = true
                turnOffAdErrorMessage = null
                activeAdJob = viewModel.turnOffStrictModeWithAd(
                    activity = currentActivity,
                    onSuccess = {
                        activeAdJob = null
                        isAdExecuting = false
                        showTurnOffStrictModeDialog = false
                        turnOffAdErrorMessage = null
                        Toast.makeText(context, "Strict Mode turned off for today.", Toast.LENGTH_SHORT).show()
                    },
                    onDismissedWithoutReward = {
                        activeAdJob = null
                        isAdExecuting = false
                        Toast.makeText(context, "Watch the full video to turn off Strict Mode.", Toast.LENGTH_SHORT).show()
                    },
                    onError = { error ->
                        activeAdJob = null
                        isAdExecuting = false
                        turnOffAdErrorMessage = error
                    }
                )
            }
        )
    }
}

