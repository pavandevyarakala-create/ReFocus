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
import com.reeltrak.app.domain.models.StrictModeDailyState

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reeltrak.app.core.tour.LocalTourCoordinator
import com.reeltrak.app.core.tour.TourTargets
import com.reeltrak.app.core.tour.tourTarget

import android.widget.Toast
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import com.reeltrak.app.core.ui.components.DailyLimitEntitlementDialog
import com.reeltrak.app.core.ui.components.ReFocusTopBar
import com.reeltrak.app.core.ui.theme.ReFocusColors
import com.reeltrak.app.core.ui.theme.ReFocusTheme
import com.reeltrak.app.data.security.authorization.OperationalMode

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

    val isAppUnlocked by viewModel.isAppUnlocked.collectAsStateWithLifecycle()
    val operationalMode by viewModel.operationalMode.collectAsStateWithLifecycle()
    val isDailyLimitEntitled by viewModel.isDailyLimitEntitled.collectAsStateWithLifecycle()

    var showDailyLimitEntitlementDialog by remember { mutableStateOf(false) }

    val dailyIntentState = viewModel.dailyIntent.collectAsStateWithLifecycle()
    val reelsScrolledTodayState = viewModel.reelsScrolledToday.collectAsStateWithLifecycle()
    
    val isStrictMode: Boolean? by viewModel.isStrictModeEnabled.collectAsStateWithLifecycle()
    val isStrictModeLocked by viewModel.isStrictModeLocked.collectAsStateWithLifecycle()
    val strictModeDailyState by viewModel.strictModeDailyState.collectAsStateWithLifecycle()
    val isLimitChangeRestricted by viewModel.isLimitChangeRestricted.collectAsStateWithLifecycle(initialValue = false)

    var showStrictModeInfoDialog by remember { mutableStateOf(false) }
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
                    isSaving = false,
                    isEntitled = isDailyLimitEntitled,
                    onDailyLimitLockedClick = {
                        if (!isDailyLimitEntitled) {
                            showDailyLimitEntitlementDialog = true
                        } else {
                            Toast.makeText(context, "Daily Limit can only be changed once per day. It unlocks tomorrow.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onSaveGoal = { newGoal ->
                        if (!isDailyLimitEntitled) {
                            showDailyLimitEntitlementDialog = true
                            return@HeroProgressCard
                        }
                        if (isLimitChangeRestricted) {
                            Toast.makeText(context, "Daily Limit has already been updated for today. Resets tomorrow.", Toast.LENGTH_SHORT).show()
                            return@HeroProgressCard
                        }

                        viewModel.confirmAndSetDailyGoal(newGoal) { success ->
                            if (success) {
                                Toast.makeText(context, "Daily Limit updated! Further changes locked until tomorrow.", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Daily Limit can only be changed once per day. Resets tomorrow.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onStreakClick = onStreakClick,
                    modifier = Modifier.tourTarget(TourTargets.HERO_PROGRESS_CARD, tourCoordinator)
                )
            }
            item {
                StrictModeCard(
                    dailyState = strictModeDailyState,
                    isEntitled = isAppUnlocked,
                    isLoading = isAppUnlocked && (isStrictMode == null),
                    onTurnOffClick = {
                        if (!isAppUnlocked) {
                            showStrictModeInfoDialog = true
                            return@StrictModeCard
                        }
                        if (strictModeDailyState.isLocked) {
                            Toast.makeText(context, "Strict Mode can only be turned off once per day. Locked until tomorrow.", Toast.LENGTH_SHORT).show()
                            return@StrictModeCard
                        }
                        viewModel.setStrictMode(false) { success ->
                            if (success) {
                                Toast.makeText(context, "Strict Mode turned off for today. You can turn it back on once.", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Strict Mode can only be turned off once per day. Locked until tomorrow.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onReactivateClick = {
                        if (!isAppUnlocked) {
                            showStrictModeInfoDialog = true
                            return@StrictModeCard
                        }
                        viewModel.setStrictMode(true) { success ->
                            if (success) {
                                Toast.makeText(context, "Strict Mode enabled and locked until tomorrow.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onLockedCardClick = {
                        if (!isAppUnlocked) {
                            showStrictModeInfoDialog = true
                        } else if (strictModeDailyState.isLocked) {
                            Toast.makeText(context, "Strict Mode can only be turned off once per day. Locked until tomorrow.", Toast.LENGTH_SHORT).show()
                        }
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
}

