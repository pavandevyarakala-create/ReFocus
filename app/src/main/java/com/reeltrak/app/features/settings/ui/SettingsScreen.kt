package com.reeltrak.app.features.settings.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reeltrak.app.core.auth.UserAuthData
import com.reeltrak.app.core.ui.components.DailyLimitEntitlementDialog
import com.reeltrak.app.core.ui.components.ReFocusTopBar
import com.reeltrak.app.data.security.authorization.OperationalMode
import com.reeltrak.app.core.ui.theme.ReFocusBrandTheme
import com.reeltrak.app.core.ui.theme.ReFocusTheme
import com.reeltrak.app.domain.models.ThemeMode
import com.reeltrak.app.features.settings.ui.components.*
import com.reeltrak.app.features.settings.viewmodel.BackupUiState
import com.reeltrak.app.features.settings.viewmodel.RestoreUiState
import com.reeltrak.app.features.settings.viewmodel.SettingsViewModel

/**
 * Re:Focus Intentional Settings Screen.
 *
 * Design Language: "RE:FOCUS — Intentional Digital Discipline"
 * Principles:
 * - Unified Grouped Containers with 3D Specular Highlight Borders
 * - Squircle Icon Tiles & Indented Hairlines
 * - Less UI + Better Hierarchy + Generous Whitespace + Zero Redundant Settings
 * - Zero technical leakages
 */
@Composable
fun SettingsScreen(
    userData: UserAuthData,
    onSignOutClick: () -> Unit,
    onNavigateToLoginForUpgrade: () -> Unit = {},
    onNavigateToPrivacy: () -> Unit = {},
    onNavigateToTerms: () -> Unit = {},
    onReplayTour: () -> Unit = {},
    viewModel: SettingsViewModel
) {
    val scrollState = rememberScrollState()

    val notificationsEnabled by viewModel.notificationsEnabled.collectAsStateWithLifecycle()
    val remainingDurationMs by viewModel.remainingDurationMs.collectAsStateWithLifecycle()
    val accessState by viewModel.accessState.collectAsStateWithLifecycle()
    val subscriptionInfo by viewModel.subscriptionInfo.collectAsStateWithLifecycle()
    val hasActiveEntitlement by viewModel.hasActiveEntitlement.collectAsStateWithLifecycle()
    val backupUiState by viewModel.backupUiState.collectAsStateWithLifecycle()
    val lastBackupTime by viewModel.lastBackupTime.collectAsStateWithLifecycle()
    val isBackedUpToday by viewModel.isBackedUpToday.collectAsStateWithLifecycle()
    val isBackupRestricted by viewModel.isBackupRestricted.collectAsStateWithLifecycle()
    val nextEligibleBackupTimeMs by viewModel.nextEligibleBackupTimeMs.collectAsStateWithLifecycle()
    val restoreUiState by viewModel.restoreUiState.collectAsStateWithLifecycle()
    var showRestoreConfirmationDialog by remember { mutableStateOf(false) }

    val defaultDailyLimit by viewModel.defaultDailyLimit.collectAsStateWithLifecycle()
    val isDailyLimitEntitled by viewModel.isDailyLimitEntitled.collectAsStateWithLifecycle()
    val operationalMode by viewModel.operationalMode.collectAsStateWithLifecycle()
    val showDailyLimitDialog by viewModel.showDailyLimitDialog.collectAsStateWithLifecycle()
    val showDailyLimitEntitlementDialog by viewModel.showDailyLimitEntitlementDialog.collectAsStateWithLifecycle()

    var showDeleteAccountDialog by remember { mutableStateOf(false) }
    val isDeletingAccount by viewModel.isDeletingAccount.collectAsStateWithLifecycle()

    // Account Deletion Confirmation Dialog (7-day recovery grace period)
    if (showDeleteAccountDialog) {
        AccountDeletionDialog(
            isDeleting = isDeletingAccount,
            onDismiss = { showDeleteAccountDialog = false },
            onConfirmDelete = {
                viewModel.requestAccountDeletion(
                    onSuccess = {
                        showDeleteAccountDialog = false
                        onSignOutClick()
                    },
                    onFailure = {}
                )
            }
        )
    }

    // Restore Data Confirmation Dialog
    if (showRestoreConfirmationDialog) {
        RestoreConfirmationDialog(
            isRestoring = restoreUiState is RestoreUiState.InProgress,
            onDismiss = { showRestoreConfirmationDialog = false },
            onConfirmRestore = {
                showRestoreConfirmationDialog = false
                viewModel.restoreBackup()
            }
        )
    }

    if (showDailyLimitDialog) {
        DailyLimitSelectionDialog(
            initialLimit = if (defaultDailyLimit > 0) defaultDailyLimit else 50,
            onDismiss = { viewModel.dismissDailyLimitDialog() },
            onSaveLimit = { newLimit ->
                viewModel.dismissDailyLimitDialog()
                viewModel.saveDailyLimit(newLimit)
            }
        )
    }

    showDailyLimitEntitlementDialog?.let { mode ->
        val isUnauthenticated = mode == OperationalMode.UNAUTHENTICATED_FREE
        DailyLimitEntitlementDialog(
            isUnauthenticated = isUnauthenticated,
            onDismiss = { viewModel.dismissDailyLimitEntitlementDialog() },
            onPrimaryAction = {
                viewModel.dismissDailyLimitEntitlementDialog()
                if (isUnauthenticated) {
                    onNavigateToLoginForUpgrade()
                } else {
                    viewModel.openSubscriptionPaywall()
                }
            }
        )
    }

    val counterThemeMode by viewModel.counterThemeMode.collectAsStateWithLifecycle()

    ReFocusBrandTheme(darkTheme = false) {
        val colors = ReFocusTheme.colors

        Scaffold(
            containerColor = colors.canvas,
            contentWindowInsets = WindowInsets(0, 0, 0, 0)
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(top = ReFocusTheme.Dimens.TopBarContentTopOffset)
                ) {
                    // 1. Cinematic Profile & Identity Hero
                    AccountProfileCard(
                    userData = userData,
                    accessState = accessState,
                    subscriptionInfo = subscriptionInfo,
                    remainingDurationMs = remainingDurationMs,
                    onMembershipClick = { viewModel.openSubscriptionPaywall() }
                )

                Spacer(modifier = Modifier.height(20.dp))

                // 3. Dedicated Membership & Premium Surface
                SettingsSectionHeader(title = "MEMBERSHIP")
                Spacer(modifier = Modifier.height(6.dp))
                MembershipSurface(
                    accessState = accessState,
                    subscriptionInfo = subscriptionInfo,
                    remainingDurationMs = remainingDurationMs,
                    onManageOrUpgradeClick = { viewModel.openSubscriptionPaywall() }
                )

                Spacer(modifier = Modifier.height(28.dp))

                // 3. Cloud Data Backup & Restore
                SettingsSectionHeader(title = "BACKUP & RESTORE")
                Spacer(modifier = Modifier.height(6.dp))
                SettingsGroupContainer {
                    val isOperationInProgress = backupUiState is BackupUiState.InProgress || restoreUiState is RestoreUiState.InProgress

                    BackupSettingItem(
                        backupState = backupUiState,
                        lastBackupTimeMs = lastBackupTime,
                        isBackedUpToday = isBackupRestricted || isBackedUpToday,
                        nextEligibleTimeMs = nextEligibleBackupTimeMs,
                        isEntitled = hasActiveEntitlement,
                        enabled = !isOperationInProgress,
                        onBackupClick = {
                            if (hasActiveEntitlement) {
                                viewModel.performBackup()
                            } else {
                                viewModel.openSubscriptionPaywall()
                            }
                        },
                        showDivider = true
                    )

                    RestoreSettingItem(
                        restoreState = restoreUiState,
                        isEntitled = hasActiveEntitlement,
                        enabled = !isOperationInProgress,
                        onRestoreClick = {
                            if (hasActiveEntitlement) {
                                showRestoreConfirmationDialog = true
                            } else {
                                viewModel.openSubscriptionPaywall()
                            }
                        },
                        showDivider = false
                    )
                }
                Spacer(modifier = Modifier.height(28.dp))

                // 4. User Preferences (Unified Grouped Container)
                SettingsSectionHeader(title = "PREFERENCES")
                Spacer(modifier = Modifier.height(6.dp))
                SettingsGroupContainer {
                    // Daily Reel Limit Setting Row
                    SettingsItemRow(
                        title = "Daily Reel Limit",
                        subtitle = if (isDailyLimitEntitled) {
                            if (defaultDailyLimit > 0) "Daily threshold: $defaultDailyLimit reels" else "Set maximum daily reels threshold"
                        } else {
                            "Trial & Premium feature • Limit daily usage"
                        },
                        icon = Icons.Outlined.Timer,
                        trailing = {
                            if (isDailyLimitEntitled && defaultDailyLimit > 0) {
                                Text(
                                    text = "$defaultDailyLimit",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = colors.inkPrimary
                                    )
                                )
                            } else if (!isDailyLimitEntitled) {
                                Icon(
                                    imageVector = Icons.Outlined.Lock,
                                    contentDescription = "Premium Only",
                                    tint = colors.inkMuted,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        },
                        onClick = {
                            viewModel.onDailyLimitSettingClicked()
                        },
                        showDivider = true
                    )

                    // Dedicated In-Line Counter Appearance Control: [ Light ] [ Dark ]
                    AppearanceSettingControl(
                        title = "Counter Appearance",
                        subtitle = when (counterThemeMode) {
                            ThemeMode.LIGHT -> "Overlay counter • Light styling"
                            ThemeMode.DARK -> "Overlay counter • Dark styling"
                        },
                        icon = Icons.Outlined.Widgets,
                        currentMode = counterThemeMode,
                        onSelectMode = { viewModel.setCounterThemeMode(it) },
                        showDivider = true
                    )

                    SettingsItemRow(
                        title = "Notifications",
                        subtitle = "Daily usage alerts and limit threshold warnings",
                        icon = Icons.Outlined.Notifications,
                        showDivider = false,
                        trailing = {
                            Switch(
                                checked = notificationsEnabled,
                                onCheckedChange = { viewModel.setNotificationsEnabled(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = colors.textInverse,
                                    checkedTrackColor = colors.accentLime,
                                    uncheckedThumbColor = colors.textMuted,
                                    uncheckedTrackColor = colors.surfaceSoft
                                )
                            )
                        }
                    )
                }

                Spacer(modifier = Modifier.height(28.dp))

                // 4. Privacy & Legal (Play Store compliance & security test assertion)
                SettingsSectionHeader(title = "PRIVACY & LEGAL")
                Spacer(modifier = Modifier.height(6.dp))
                SettingsGroupContainer {
                    SettingsItemRow(
                        title = "Privacy Policy",
                        subtitle = "Read our on-device processing and data policy",
                        icon = Icons.Outlined.Security,
                        onClick = onNavigateToPrivacy,
                        showDivider = true
                    )

                    SettingsItemRow(
                        title = "Terms of Service",
                        subtitle = "Review subscription and usage terms",
                        icon = Icons.Outlined.Description,
                        onClick = onNavigateToTerms,
                        showDivider = false
                    )
                }

                Spacer(modifier = Modifier.height(28.dp))

                // 5. Account Management
                SettingsSectionHeader(title = "ACCOUNT")
                Spacer(modifier = Modifier.height(6.dp))
                SettingsGroupContainer {
                    SettingsItemRow(
                        title = "Sign Out",
                        subtitle = "Safely end current session on this device",
                        icon = Icons.AutoMirrored.Outlined.Logout,
                        onClick = onSignOutClick,
                        showDivider = true
                    )

                    SettingsItemRow(
                        title = "Delete Account",
                        subtitle = "Schedule account deletion (7-day recovery grace period)",
                        icon = Icons.Outlined.DeleteOutline,
                        isDestructive = true,
                        onClick = { showDeleteAccountDialog = true },
                        showDivider = false
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // 7. Subtle technical metadata footer
                Text(
                    text = "v1.0.1 • Re:Focus",
                    style = ReFocusTheme.Typography.Metadata.copy(
                        color = colors.inkMuted,
                        fontSize = 11.sp
                    ),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))
            }

            ReFocusTopBar(
                title = "Settings",
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
}
}
