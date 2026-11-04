package com.reeltrak.app.features.permission.ui

import android.Manifest
import android.app.Activity
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.reeltrak.app.core.ui.theme.ReFocusColors
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.reeltrak.app.core.permission.PermissionSetupManager
import com.reeltrak.app.core.permission.PermissionState

/**
 * Android Permission Transparency & Policy Information:
 * - Why Re:Focus needs this: Real-time on-screen counter bubble and video swipe transition detection.
 * - Where & how to enable: Re:Focus toggle switch in Android System Settings.
 * - After enabling: Video tracking runs locally and privately on your device.
 * - If not granted: Live counter overlay and reel session tracking cannot operate.
 */
@Composable
fun PermissionSetupWizardScreen(
    onSetupCompleted: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Live permission capabilities
    var isOverlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var isAccessibilityGranted by remember {
        mutableStateOf(PermissionSetupManager.getAccessibilityServiceState(context) == PermissionState.GRANTED)
    }
    var isNotificationGranted by remember {
        mutableStateOf(PermissionSetupManager.getNotificationPermissionState(context) == PermissionState.GRANTED)
    }

    var showAccessibilityDisclosureDialog by remember { mutableStateOf(false) }
    var showRestrictedSettingsInstructions by remember { mutableStateOf(false) }

    val isAndroid13Plus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val areCoreGranted = isOverlayGranted && isAccessibilityGranted

    // Fresh capability evaluation
    val performFreshCapabilityCheck = {
        val overlay = Settings.canDrawOverlays(context)
        val accessibility = PermissionSetupManager.getAccessibilityServiceState(context) == PermissionState.GRANTED
        val notification = PermissionSetupManager.getNotificationPermissionState(context) == PermissionState.GRANTED

        isOverlayGranted = overlay
        isAccessibilityGranted = accessibility
        isNotificationGranted = notification

        if (overlay && accessibility) {
            showRestrictedSettingsInstructions = false
            onSetupCompleted()
        }
    }

    // Back handling
    BackHandler(enabled = true) {
        if (showRestrictedSettingsInstructions) {
            showRestrictedSettingsInstructions = false
        } else if (!PermissionSetupManager.areCoreRequirementsSatisfied(context)) {
            (context as? Activity)?.finish()
        }
    }

    // Refresh on return from Android Settings
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                performFreshCapabilityCheck()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Runtime notifications launcher (Android 13+)
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        performFreshCapabilityCheck()
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 20.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (showRestrictedSettingsInstructions) {
                RestrictedSettingsScreenView(
                    onOpenAppInfo = {
                        PermissionSetupManager.openAppDetailsSettings(context)
                    },
                    onBack = {
                        showRestrictedSettingsInstructions = false
                    }
                )
            } else {
                Spacer(modifier = Modifier.height(16.dp))

                // Header Icon
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(
                            if (areCoreGranted) ReFocusColors.successSoft
                            else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (areCoreGranted) Icons.Default.CheckCircle else Icons.Default.Security,
                        contentDescription = null,
                        tint = if (areCoreGranted) ReFocusColors.success else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Header Title
                Text(
                    text = "Set up Re:Focus",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.5).sp
                    ),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onBackground
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Header Subtitle
                Text(
                    text = "Allow the permissions below so Re:Focus can track your short-form video usage.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(28.dp))

                // Permission List
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 1. Display over other apps
                    PermissionItemRow(
                        icon = Icons.Default.Layers,
                        title = "Display over other apps",
                        description = "Shows floating counter bubble over video feeds.",
                        isGranted = isOverlayGranted,
                        onEnable = {
                            PermissionSetupManager.openOverlaySettings(context)
                        }
                    )

                    // 2. Accessibility
                    PermissionItemRow(
                        icon = Icons.Default.AccessibilityNew,
                        title = "Accessibility",
                        description = "Detects Reels and Shorts to count video transitions.",
                        isGranted = isAccessibilityGranted,
                        onEnable = {
                            showAccessibilityDisclosureDialog = true
                        }
                    )

                    // 3. Notifications (Android 13+)
                    if (isAndroid13Plus) {
                        PermissionItemRow(
                            icon = Icons.Default.Notifications,
                            title = "Notifications (Optional)",
                            description = "Daily limit alerts and streak milestone reminders.",
                            isGranted = isNotificationGranted,
                            onEnable = {
                                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))

                // Direct Action / Proceed Button
                if (areCoreGranted) {
                    Button(
                        onClick = onSetupCompleted,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Continue to Re:Focus", fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                    }
                } else {
                    TextButton(
                        onClick = { showRestrictedSettingsInstructions = true }
                    ) {
                        Text(
                            text = "Setting greyed out? Allow restricted settings",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        // Dedicated Google Play Compliant Prominent Disclosure Dialog
        if (showAccessibilityDisclosureDialog) {
            AlertDialog(
                onDismissRequest = { showAccessibilityDisclosureDialog = false },
                icon = {
                    Icon(
                        imageVector = Icons.Default.AccessibilityNew,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                },
                title = {
                    Text(
                        text = "Accessibility access",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 19.sp
                        ),
                        textAlign = TextAlign.Center
                    )
                },
                text = {
                    Text(
                        text = "Re:Focus uses Accessibility access to detect Reels and Shorts and count video transitions. Processing happens on your device. Re:Focus does not collect messages, passwords, keystrokes, or unrelated app activity.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showAccessibilityDisclosureDialog = false
                            PermissionSetupManager.openAccessibilitySettings(context)
                        }
                    ) {
                        Text("I Understand & Accept", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAccessibilityDisclosureDialog = false }) {
                        Text("Not Now")
                    }
                }
            )
        }
    }
}

@Composable
private fun PermissionItemRow(
    icon: ImageVector,
    title: String,
    description: String,
    isGranted: Boolean,
    onEnable: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        border = BorderStroke(
            1.dp,
            if (isGranted) ReFocusColors.successBorder
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (isGranted) ReFocusColors.successSoft
                        else MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isGranted) ReFocusColors.success else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 16.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            if (isGranted) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Enabled",
                        tint = ReFocusColors.success,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Enabled",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = ReFocusColors.successText
                    )
                }
            } else {
                Button(
                    onClick = onEnable,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "Enable",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }
        }
    }
}

/**
 * Concise walkthrough for Android's "Allow restricted settings" requirement.
 * Compliance notes:
 * - Why Re:Focus needs this: To grant on-screen overlay and accessibility for habit tracking.
 * - Where & how to enable: In App Info > ⋮ (three dots) > Allow restricted settings.
 * - After enabling: You can turn on the permission toggles normally.
 * - If not granted: The toggle switch remains greyed out by the operating system.
 */
@Composable
private fun RestrictedSettingsScreenView(
    onOpenAppInfo: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(
                onClick = onBack,
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Back",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Text(
                text = "Re:Focus",
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                ),
                color = MaterialTheme.colorScheme.primary
            )
        }

        Text(
            text = "Allow restricted settings",
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp
            ),
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "Android may require approval before sensitive permissions can be toggled.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(18.dp))

        WalkthroughItem(
            stepNumber = "1",
            title = "Open App Info",
            description = "Tap the button below to open Re:Focus settings."
        )

        Spacer(modifier = Modifier.height(10.dp))

        WalkthroughItem(
            stepNumber = "2",
            title = "Tap ⋮ (Three dots menu)",
            description = "In the top-right corner of App Info, tap the three dots."
        )

        Spacer(modifier = Modifier.height(10.dp))

        WalkthroughItem(
            stepNumber = "3",
            title = "Select 'Allow restricted settings'",
            description = "Confirm with device biometric or PIN, then return to Re:Focus."
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onOpenAppInfo,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                text = "Open App Info",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(imageVector = Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
        }
    }
}

@Composable
private fun WalkthroughItem(
    stepNumber: String,
    title: String,
    description: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stepNumber,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
