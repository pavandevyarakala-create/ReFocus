package com.reeltrak.app.features.main.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
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
import com.reeltrak.app.services.ReelTrackerService
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reeltrak.app.features.auth.LoginViewModel
import com.reeltrak.app.features.auth.LoginState
import com.reeltrak.app.core.auth.UserAuthData
import com.reeltrak.app.features.auth.components.LogoutDialog
import androidx.compose.ui.res.stringResource
import com.reeltrak.app.R

private fun isAccessibilityServiceEnabled(context: android.content.Context): Boolean {
    val expectedComponentName = android.content.ComponentName(context, ReelTrackerService::class.java)
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    
    return enabledServices.split(":").any {
        val componentName = android.content.ComponentName.unflattenFromString(it)
        componentName != null && componentName == expectedComponentName
    }
}

@Composable
fun MainScreen(
    onNavigateToLogin: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    viewModel: LoginViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val bottomNavController = rememberNavController()
    
    // Permission States
    var hasOverlayPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var hasAccessibilityPermission by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }

    // Re-check permissions when the app comes back to foreground
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                hasOverlayPermission = Settings.canDrawOverlays(context)
                hasAccessibilityPermission = isAccessibilityServiceEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    if (!hasOverlayPermission || !hasAccessibilityPermission) {
        PermissionRequiredScreen(
            hasOverlay = hasOverlayPermission,
            hasAccessibility = hasAccessibilityPermission,
            onOpenOverlaySettings = {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
                context.startActivity(intent)
            },
            onOpenAccessibilitySettings = {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                context.startActivity(intent)
            }
        )
    } else {
        MainContent(
            bottomNavController = bottomNavController,
            onNavigateToLogin = onNavigateToLogin,
            viewModel = viewModel
        )
    }
}

@Composable
fun PermissionRequiredScreen(
    hasOverlay: Boolean,
    hasAccessibility: Boolean,
    onOpenOverlaySettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Permissions Required",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(id = R.string.app_name) + " needs the following permissions to work correctly:",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(32.dp))

            PermissionItem(
                title = "Display Over Other Apps",
                description = "Required to show the reel counter widget.",
                isGranted = hasOverlay,
                onClick = onOpenOverlaySettings
            )
            
            Spacer(modifier = Modifier.height(16.dp))

            PermissionItem(
                title = "Accessibility Service",
                description = "Required to track reels and usage time.",
                isGranted = hasAccessibility,
                onClick = onOpenAccessibilitySettings
            )
            
            Spacer(modifier = Modifier.height(48.dp))
            
            Text(
                text = "The app will start automatically once both are enabled.",
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray
            )
        }
    }
}

@Composable
fun PermissionItem(
    title: String,
    description: String,
    isGranted: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isGranted) 
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f) 
            else 
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, fontWeight = FontWeight.Bold)
                Text(text = description, style = MaterialTheme.typography.bodySmall)
            }
            if (isGranted) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Granted",
                    tint = Color(0xFF2E7D32)
                )
            } else {
                Button(onClick = onClick) {
                    Text("Enable")
                }
            }
        }
    }
}

@Composable
fun MainContent(
    bottomNavController: androidx.navigation.NavHostController,
    onNavigateToLogin: () -> Unit,
    viewModel: LoginViewModel
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val showLogoutDialog by viewModel.showLogoutDialog.collectAsStateWithLifecycle()
    val showOfflineWarning by viewModel.showOfflineWarning.collectAsStateWithLifecycle()
    val logoutCountdown by viewModel.logoutCountdown.collectAsStateWithLifecycle()

    LaunchedEffect(uiState) {
        if (uiState is LoginState.Idle) {
            onNavigateToLogin()
        }
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
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { viewModel.cancelLogout() },
            title = { androidx.compose.material3.Text("No Internet Connection") },
            text = { androidx.compose.material3.Text("You have unsynced progress. Logging out now will permanently delete this data. Please connect to the internet to back up your data before signing out.") },
            confirmButton = {
                androidx.compose.material3.Button(onClick = { viewModel.cancelLogout() }) {
                    androidx.compose.material3.Text("Okay")
                }
            }
        )
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by bottomNavController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination

                NavigationBarItem(
                    icon = { Icon(Icons.Filled.Home, contentDescription = "Dashboard") },
                    label = { Text("Dashboard") },
                    selected = currentDestination?.hierarchy?.any { it.route == Routes.DASHBOARD } == true,
                    onClick = {
                        bottomNavController.navigate(Routes.DASHBOARD) {
                            popUpTo(bottomNavController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )

                NavigationBarItem(
                    icon = { Icon(Icons.Filled.BarChart, contentDescription = "Analytics") },
                    label = { Text("Analytics") },
                    selected = currentDestination?.hierarchy?.any { it.route == Routes.ANALYTICS } == true,
                    onClick = {
                        bottomNavController.navigate(Routes.ANALYTICS) {
                            popUpTo(bottomNavController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )

                NavigationBarItem(
                    icon = { Icon(Icons.Filled.DateRange, contentDescription = "Calendar") },
                    label = { Text("Calendar") },
                    selected = currentDestination?.hierarchy?.any { it.route == Routes.CALENDAR } == true,
                    onClick = {
                        bottomNavController.navigate(Routes.CALENDAR) {
                            popUpTo(bottomNavController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )

                NavigationBarItem(
                    icon = { Icon(Icons.Filled.Settings, contentDescription = "Settings") },
                    label = { Text("Settings") },
                    selected = currentDestination?.hierarchy?.any { it.route == Routes.SETTINGS } == true,
                    onClick = {
                        bottomNavController.navigate(Routes.SETTINGS) {
                            popUpTo(bottomNavController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = bottomNavController,
            startDestination = Routes.DASHBOARD,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Routes.DASHBOARD) { 
                DashboardScreen(
                    onStreakClick = {
                        bottomNavController.navigate(Routes.CALENDAR) {
                            popUpTo(bottomNavController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
            composable(Routes.ANALYTICS) { AnalyticsScreen() }
            composable(Routes.CALENDAR) { CalendarScreen() }
            composable(Routes.SETTINGS) { 
                val userData = (uiState as? LoginState.Success)?.userData 
                    ?: UserAuthData(
                        idToken = "",
                        email = "user@example.com",
                        displayName = "User",
                        profilePictureUri = ""
                    )
                
                val settingsViewModel: com.reeltrak.app.features.settings.viewmodel.SettingsViewModel = hiltViewModel()
                
                SettingsScreen(
                    userData = userData,
                    onSignOutClick = {
                        viewModel.initiateLogout()
                    },
                    onRestoreBackupClick = {
                        settingsViewModel.restoreBackup()
                    }
                )
            }
            composable(Routes.DEBUG_OVERLAY) { com.reeltrak.app.features.debug.ui.DebugOverlayScreen(onNavigateBack = { bottomNavController.popBackStack() }) }
        }
    }
}
