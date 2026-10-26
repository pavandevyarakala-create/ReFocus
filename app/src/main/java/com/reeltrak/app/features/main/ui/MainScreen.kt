package com.reeltrak.app.features.main.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import com.reeltrak.app.core.ui.theme.NavDockItem
import com.reeltrak.app.core.ui.theme.ReFocusFloatingNavDock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.reeltrak.app.core.navigation.Routes
import com.reeltrak.app.features.analytics.ui.AnalyticsScreen
import com.reeltrak.app.features.analytics.ui.CalendarScreen
import com.reeltrak.app.features.dashboard.ui.DashboardScreen
import com.reeltrak.app.features.settings.ui.SettingsScreen
import com.reeltrak.app.core.ui.components.LockedFeatureScreen
import com.reeltrak.app.core.ui.components.LockedFeature
import com.reeltrak.app.services.ReelTrackerService
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reeltrak.app.features.auth.LoginViewModel
import com.reeltrak.app.features.auth.LoginState
import com.reeltrak.app.core.auth.UserAuthData
import com.reeltrak.app.domain.models.AccessState
import com.reeltrak.app.features.auth.components.LogoutDialog
import com.reeltrak.app.features.settings.viewmodel.SettingsViewModel
import com.reeltrak.app.features.subscription.ui.LockedAppScreen
import androidx.compose.ui.res.stringResource
import com.reeltrak.app.R
import com.reeltrak.app.core.tour.LocalTourCoordinator
import com.reeltrak.app.core.tour.TourCoordinator
import com.reeltrak.app.core.tour.TourTargets
import com.reeltrak.app.core.tour.tourTarget
import com.reeltrak.app.features.tour.ui.TourOverlay
import com.reeltrak.app.features.tour.ui.TourWelcomeDialog
import com.google.firebase.ktx.Firebase
import com.google.firebase.auth.ktx.auth
import kotlinx.coroutines.launch

@Composable
fun MainScreen(
    onNavigateToLogin: () -> Unit = {},
    onNavigateToLoginForUpgrade: () -> Unit = onNavigateToLogin,
    onNavigateToPrivacy: () -> Unit = {},
    onNavigateToTerms: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    viewModel: LoginViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val bottomNavController = rememberNavController()
    
    // Permission State Evaluation via Centralized Engine
    var coreRequirementsMet by remember {
        mutableStateOf(com.reeltrak.app.core.permission.PermissionSetupManager.areCoreRequirementsSatisfied(context))
    }

    // Live re-check on app resume
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                coreRequirementsMet = com.reeltrak.app.core.permission.PermissionSetupManager.areCoreRequirementsSatisfied(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    if (!coreRequirementsMet) {
        com.reeltrak.app.features.permission.ui.PermissionSetupWizardScreen(
            onSetupCompleted = {
                coreRequirementsMet = com.reeltrak.app.core.permission.PermissionSetupManager.areCoreRequirementsSatisfied(context)
            }
        )
    } else {
        MainContent(
            bottomNavController = bottomNavController,
            onNavigateToLogin = onNavigateToLogin,
            onNavigateToLoginForUpgrade = onNavigateToLoginForUpgrade,
            onNavigateToPrivacy = onNavigateToPrivacy,
            onNavigateToTerms = onNavigateToTerms,
            viewModel = viewModel,
            settingsViewModel = settingsViewModel
        )
    }
}


@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainContent(
    bottomNavController: androidx.navigation.NavHostController,
    onNavigateToLogin: () -> Unit,
    onNavigateToLoginForUpgrade: () -> Unit = onNavigateToLogin,
    onNavigateToPrivacy: () -> Unit = {},
    onNavigateToTerms: () -> Unit = {},
    viewModel: LoginViewModel,
    settingsViewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val showLogoutDialog by viewModel.showLogoutDialog.collectAsStateWithLifecycle()
    val showOfflineWarning by viewModel.showOfflineWarning.collectAsStateWithLifecycle()
    val logoutCountdown by viewModel.logoutCountdown.collectAsStateWithLifecycle()
    val logoutSyncError by viewModel.logoutSyncError.collectAsStateWithLifecycle()

    val accountStatus by settingsViewModel.accountStatus.collectAsStateWithLifecycle()
    val accountAccessInfo by settingsViewModel.accountAccessInfo.collectAsStateWithLifecycle()
    val subscriptionInfo by settingsViewModel.subscriptionInfo.collectAsStateWithLifecycle()
    val accessState by settingsViewModel.accessState.collectAsStateWithLifecycle()
    val isTourCompleted by settingsViewModel.isTourCompleted.collectAsStateWithLifecycle()
    val isAppUnlocked = accessState == AccessState.TRIAL || accessState == AccessState.ACTIVE
    val isAppLocked = accessState == AccessState.LOCKED || accessState == AccessState.FINAL_SYNC_GRACE

    val currentUserId: String? = if (uiState is LoginState.Success) {
        Firebase.auth.currentUser?.uid
    } else null

    LaunchedEffect(accessState, isAppLocked, isAppUnlocked, subscriptionInfo) {
        android.util.Log.d(
            "SUB_DEBUG_MAINSCREEN",
            "MainScreen State: accessState=$accessState, isAppLocked=$isAppLocked, isAppUnlocked=$isAppUnlocked, " +
            "status=${subscriptionInfo.status}, plan=${subscriptionInfo.plan}, " +
            "currentPeriodEnd=${subscriptionInfo.currentPeriodEnd}, trialEnd=${subscriptionInfo.trialEnd}, " +
            "isConfirmedLocked=${subscriptionInfo.isConfirmedLocked}"
        )
    }

    // REAL-TIME MASTER ACCESS ENFORCEMENT:
    // If accountStatus transitions from ACTIVE -> RESTRICTED while using the app,
    // immediately terminate session, wipe state, and redirect to restricted flow.
    LaunchedEffect(accountStatus) {
        if (accountStatus == com.reeltrak.app.domain.models.AccountStatus.RESTRICTED) {
            android.util.Log.w("MasterAccess", "Active session terminated: Account has become RESTRICTED.")
            viewModel.dismissRestrictedState()
            onNavigateToLogin()
        }
    }

    // Check account-aware onboarding state upon login
    LaunchedEffect(currentUserId) {
        if (!currentUserId.isNullOrBlank()) {
            settingsViewModel.checkTourStatus(currentUserId)
        }
    }

    // Tour Coordinator
    val tourCoordinator = remember { com.reeltrak.app.core.tour.TourCoordinator() }
    var showWelcomeDialog by remember { mutableStateOf(false) }

    // Trigger tour on first run only for genuinely new accounts
    LaunchedEffect(isTourCompleted, isAppUnlocked, currentUserId) {
        if (!isTourCompleted && isAppUnlocked && !currentUserId.isNullOrBlank()) {
            showWelcomeDialog = true
        }
    }

    // If app becomes locked while tour is active, safely end tour
    LaunchedEffect(isAppUnlocked) {
        if (!isAppUnlocked) {
            tourCoordinator.endTour()
            showWelcomeDialog = false
        }
    }

    val authState by viewModel.startupAuthState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState, authState) {
        if ((uiState is LoginState.Idle || uiState is LoginState.AccountRestricted) && authState == com.reeltrak.app.features.auth.StartupAuthState.UNAUTHENTICATED) {
            onNavigateToLogin()
        }
    }

    if (logoutSyncError != null) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelLogout() },
            title = {
                Text("Sync Failed", fontWeight = FontWeight.Bold)
            },
            text = {
                Text(logoutSyncError ?: "Failed to back up today's data. Please check your internet connection and try again.")
            },
            confirmButton = {
                Button(onClick = { viewModel.retryLogoutSync() }) {
                    Text("Retry")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelLogout() }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showLogoutDialog) {
        LogoutDialog(
            countdown = logoutCountdown,
            onConfirm = {
                viewModel.signOut()
            },
            onCancel = {
                viewModel.cancelLogout()
            }
        )
    }

    if (showOfflineWarning) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelLogout() },
            title = { Text("No Internet Connection", fontWeight = FontWeight.Bold) },
            text = { Text("You have unsynced progress. Logging out now will permanently delete this data. Please connect to the internet to back up your data before signing out.") },
            confirmButton = {
                Button(onClick = { viewModel.retryLogoutSync() }) {
                    Text("Retry")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelLogout() }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showWelcomeDialog && isAppUnlocked) {
        TourWelcomeDialog(
            onStartTour = {
                showWelcomeDialog = false
                bottomNavController.navigate(Routes.DASHBOARD) {
                    popUpTo(bottomNavController.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                }
                tourCoordinator.startTour()
            },
            onSkipTour = {
                showWelcomeDialog = false
                settingsViewModel.setHasCompletedTour(currentUserId, true)
            }
        )
    }

    // Paywall BottomSheet for when user clicks Subscribe / Start in Locked screen or Settings
    val showSubscriptionPaywall by settingsViewModel.showSubscriptionPaywall.collectAsStateWithLifecycle()
    val subscriptionState by settingsViewModel.subscriptionState.collectAsStateWithLifecycle()
    val checkoutState by settingsViewModel.checkoutState.collectAsStateWithLifecycle()
    val isRefreshingSubscription by settingsViewModel.isRefreshingSubscription.collectAsStateWithLifecycle()
    val remainingDurationMs by settingsViewModel.remainingDurationMs.collectAsStateWithLifecycle()

    val isCancellingSubscription by settingsViewModel.isCancellingSubscription.collectAsStateWithLifecycle()

    if (showSubscriptionPaywall) {
        com.reeltrak.app.features.settings.ui.components.SubscriptionBottomSheet(
            subscriptionState = subscriptionState,
            checkoutState = checkoutState,
            isRefreshing = isRefreshingSubscription,
            isCancelling = isCancellingSubscription,
            remainingDurationMs = remainingDurationMs,
            onSubscribeClick = { settingsViewModel.subscribe(it) },
            onRefreshClick = { settingsViewModel.refreshSubscription() },
            onCancelSubscriptionClick = { settingsViewModel.cancelSubscription() },
            onResetCheckout = { settingsViewModel.resetCheckoutState() },
            onDismiss = { settingsViewModel.closeSubscriptionPaywall() }
        )
    }

    val showLoginRequiredDialog by settingsViewModel.showLoginRequiredDialog.collectAsStateWithLifecycle()
    val pendingSubscriptionAfterLogin by viewModel.pendingSubscriptionAfterLogin.collectAsStateWithLifecycle()

    // Automatically navigate toward Subscription/Premium page after successful login
    LaunchedEffect(pendingSubscriptionAfterLogin, uiState) {
        if (pendingSubscriptionAfterLogin && uiState is LoginState.Success) {
            viewModel.clearPendingSubscriptionAfterLogin()
            settingsViewModel.openSubscriptionPaywall()
        }
    }

    if (showLoginRequiredDialog) {
        com.reeltrak.app.core.ui.components.LoginRequiredDialog(
            onDismiss = {
                settingsViewModel.dismissLoginRequiredDialog()
            },
            onLoginAndContinue = {
                settingsViewModel.dismissLoginRequiredDialog()
                viewModel.setPendingSubscriptionAfterLogin(true)
                onNavigateToLoginForUpgrade()
            }
        )
    }

    CompositionLocalProvider(LocalTourCoordinator provides tourCoordinator) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (accessState == AccessState.INITIALIZING) {
                // Subscription is actively being verified with the authoritative server/cache.
                // Display neutral loading UI to prevent flashing the paywall or premature unlock.
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(32.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(48.dp),
                                strokeWidth = 4.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Text(
                                text = "Checking subscription...",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Verifying your account with the server",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                val primaryTabs = remember {
                    listOf(
                        Routes.DASHBOARD,
                        Routes.ANALYTICS,
                        Routes.CALENDAR,
                        Routes.SETTINGS
                    )
                }
                val pagerState = androidx.compose.foundation.pager.rememberPagerState(
                    initialPage = 0,
                    pageCount = { primaryTabs.size }
                )
                val coroutineScope = rememberCoroutineScope()

                BackHandler(enabled = pagerState.currentPage != 0) {
                    coroutineScope.launch {
                        pagerState.scrollToPage(0)
                    }
                }

                val navItems = remember(tourCoordinator, isAppUnlocked) {
                    listOf(
                        NavDockItem(
                            title = "Dashboard",
                            selectedIcon = Icons.Filled.Home,
                            unselectedIcon = Icons.Outlined.Home
                        ),
                        NavDockItem(
                            title = "Analytics",
                            selectedIcon = Icons.Filled.BarChart,
                            unselectedIcon = Icons.Outlined.BarChart,
                            isLocked = !isAppUnlocked,
                            modifier = Modifier.tourTarget(TourTargets.ANALYTICS_TAB, tourCoordinator)
                        ),
                        NavDockItem(
                            title = "Calendar",
                            selectedIcon = Icons.Filled.DateRange,
                            unselectedIcon = Icons.Outlined.DateRange,
                            isLocked = !isAppUnlocked,
                            modifier = Modifier.tourTarget(TourTargets.CALENDAR_TAB, tourCoordinator)
                        ),
                        NavDockItem(
                            title = "Settings",
                            selectedIcon = Icons.Filled.Settings,
                            unselectedIcon = Icons.Outlined.Settings,
                            modifier = Modifier.tourTarget(TourTargets.SETTINGS_TAB, tourCoordinator)
                        )
                    )
                }

                Scaffold(
                    containerColor = Color.Transparent,
                    bottomBar = {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            ReFocusFloatingNavDock(
                                selectedIndex = pagerState.currentPage,
                                onSelectTab = { index ->
                                    coroutineScope.launch {
                                        pagerState.scrollToPage(index)
                                    }
                                },
                                items = navItems
                            )
                        }
                    }
                ) { innerPadding ->
                    NavHost(
                        navController = bottomNavController,
                        startDestination = "main_tabs",
                        modifier = Modifier.padding(innerPadding)
                    ) {
                        composable("main_tabs") {
                            // Horizontal Swipe Pager for 4 Primary Tabs (Dashboard -> Analytics -> Calendar -> Settings)
                            HorizontalPager(
                                state = pagerState,
                                modifier = Modifier.fillMaxSize(),
                                beyondBoundsPageCount = 0,
                                userScrollEnabled = !tourCoordinator.isTourActive.collectAsStateWithLifecycle().value
                            ) { page ->
                                when (page) {
                                    0 -> {
                                        val currentAuthUser = (uiState as? LoginState.Success)?.userData
                                        DashboardScreen(
                                            onStreakClick = {
                                                coroutineScope.launch {
                                                    pagerState.scrollToPage(2)
                                                }
                                            },
                                            onOpenSubscription = {
                                                settingsViewModel.openSubscriptionPaywall()
                                            },
                                            onNavigateToLoginForUpgrade = {
                                                viewModel.setPendingSubscriptionAfterLogin(true)
                                                onNavigateToLoginForUpgrade()
                                            },
                                            onProfileClick = {
                                                coroutineScope.launch {
                                                    pagerState.scrollToPage(3)
                                                }
                                            },
                                            userProfilePictureUri = currentAuthUser?.profilePictureUri,
                                            userDisplayName = currentAuthUser?.displayName ?: currentAuthUser?.email
                                        )
                                    }
                                    1 -> if (isAppUnlocked) {
                                        AnalyticsScreen()
                                    } else {
                                        LockedFeatureScreen(
                                            feature = LockedFeature.ANALYTICS,
                                            onUpgradeClick = {
                                                settingsViewModel.openSubscriptionPaywall()
                                            }
                                        )
                                    }
                                    2 -> if (isAppUnlocked) {
                                        CalendarScreen()
                                    } else {
                                        LockedFeatureScreen(
                                            feature = LockedFeature.CALENDAR,
                                            onUpgradeClick = {
                                                settingsViewModel.openSubscriptionPaywall()
                                            }
                                        )
                                    }
                                    3 -> {
                                        val userData = (uiState as? LoginState.Success)?.userData
                                            ?: UserAuthData(
                                                email = "user@example.com",
                                                displayName = "User",
                                                profilePictureUri = ""
                                            )

                                        SettingsScreen(
                                            userData = userData,
                                            onSignOutClick = {
                                                viewModel.initiateLogout()
                                            },
                                            onNavigateToLoginForUpgrade = {
                                                viewModel.setPendingSubscriptionAfterLogin(true)
                                                onNavigateToLoginForUpgrade()
                                            },
                                            onNavigateToPrivacy = onNavigateToPrivacy,
                                            onNavigateToTerms = onNavigateToTerms,
                                            onReplayTour = {
                                                coroutineScope.launch {
                                                    pagerState.scrollToPage(0)
                                                }
                                                tourCoordinator.startTour()
                                            },
                                            viewModel = settingsViewModel
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Interactive Tour Overlay
            TourOverlay(
                coordinator = tourCoordinator,
                onFinishTour = {
                    settingsViewModel.setHasCompletedTour(currentUserId, true)
                }
            )
        }
    }
}
