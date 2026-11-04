package com.reeltrak.app.core.permission

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.reeltrak.app.services.ReelTrackerService

/**
 * Granular state for a permission or system access requirement.
 */
enum class PermissionState {
    GRANTED,
    NOT_GRANTED,
    RESTRICTED_BY_ANDROID,
    NOT_APPLICABLE
}

/**
 * Type of permission or setup capability needed by Re:Focus.
 */
enum class SetupRequirementType {
    RESTRICTED_SETTINGS,
    OVERLAY,
    ACCESSIBILITY,
    NOTIFICATIONS
}

/**
 * Model representing a permission requirement with adaptive metadata.
 */
data class SetupRequirement(
    val type: SetupRequirementType,
    val title: String,
    val description: String,
    val whyNeeded: String,
    val state: PermissionState
)

object PermissionSetupManager {

    private const val PLAY_STORE_PACKAGE = "com.android.vending"

    /**
     * Detects if the app was installed from outside Google Play Store (sideloaded APK).
     * On Android 13+ (API 33+), sideloaded apps have sensitive permissions restricted by default.
     */
    fun isSideloadedInstallation(context: Context): Boolean {
        return try {
            val installerPackageName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getInstallerPackageName(context.packageName)
            }
            // Sideloaded / PackageInstaller / browser / adb installs have installer != "com.android.vending"
            installerPackageName != PLAY_STORE_PACKAGE
        } catch (e: Exception) {
            true // Default to sideloaded if detection fails safely
        }
    }

    /**
     * Determines whether Android's "Restricted Settings" step is potentially active.
     * On Android 13+ (API 33+), sideloaded apps require the user to allow restricted settings
     * before sensitive toggles like Accessibility or Overlay are unlocked.
     */
    fun isRestrictedSettingsApplicable(context: Context): Boolean {
        // Only applicable on Android 13+ for sideloaded installations when core permissions are not yet granted
        val isAndroid13Plus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        val isSideloaded = isSideloadedInstallation(context)
        val permissionsMissing = getOverlayPermissionState(context) != PermissionState.GRANTED ||
                getAccessibilityServiceState(context) != PermissionState.GRANTED
        return isAndroid13Plus && isSideloaded && permissionsMissing
    }

    /**
     * Checks whether SYSTEM_ALERT_WINDOW (Display over other apps) is enabled.
     */
    fun getOverlayPermissionState(context: Context): PermissionState {
        return if (Settings.canDrawOverlays(context)) {
            PermissionState.GRANTED
        } else {
            PermissionState.NOT_GRANTED
        }
    }

    /**
     * Checks whether the ReelTrackerService Accessibility Service is enabled.
     */
    fun getAccessibilityServiceState(context: Context): PermissionState {
        val expectedComponentName = ComponentName(context, ReelTrackerService::class.java)
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return PermissionState.NOT_GRANTED

        val isEnabled = enabledServices.split(":").any {
            val componentName = ComponentName.unflattenFromString(it)
            componentName != null && componentName == expectedComponentName
        }

        return if (isEnabled) PermissionState.GRANTED else PermissionState.NOT_GRANTED
    }

    /**
     * Checks whether notification permission is granted (applicable on Android 13+).
     */
    fun getNotificationPermissionState(context: Context): PermissionState {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return PermissionState.NOT_APPLICABLE
        }
        val isGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        return if (isGranted) PermissionState.GRANTED else PermissionState.NOT_GRANTED
    }

    /**
     * Evaluates all setup requirements adaptively for the current device.
     */
    fun getAllRequirements(context: Context): List<SetupRequirement> {
        val overlayState = getOverlayPermissionState(context)
        val accessibilityState = getAccessibilityServiceState(context)
        val notificationState = getNotificationPermissionState(context)

        val list = mutableListOf<SetupRequirement>()

        // Step 0: Restricted Settings (if applicable on Android 13+ sideloaded builds)
        if (isRestrictedSettingsApplicable(context)) {
            list.add(
                SetupRequirement(
                    type = SetupRequirementType.RESTRICTED_SETTINGS,
                    title = "Android Security Step",
                    description = "Allow restricted settings in App Info.",
                    whyNeeded = "Because Re:Focus was installed outside Google Play, Android adds this standard confirmation to unlock sensitive settings.",
                    state = PermissionState.NOT_GRANTED
                )
            )
        }

        // Step 1: Overlay
        list.add(
            SetupRequirement(
                type = SetupRequirementType.OVERLAY,
                title = "Display Over Other Apps",
                description = "Display the floating reel counter while viewing supported apps.",
                whyNeeded = "Allows Re:Focus to display a non-intrusive floating tracker directly on screen while you browse Reels.",
                state = overlayState
            )
        )

        // Step 2: Accessibility
        list.add(
            SetupRequirement(
                type = SetupRequirementType.ACCESSIBILITY,
                title = "Accessibility Service",
                description = "Detect short-form video feeds and track time spent.",
                whyNeeded = "Enables Re:Focus to recognize when Instagram Reels or YouTube Shorts are actively playing to increment your session count.",
                state = accessibilityState
            )
        )

        // Step 3: Notifications
        if (notificationState != PermissionState.NOT_APPLICABLE) {
            list.add(
                SetupRequirement(
                    type = SetupRequirementType.NOTIFICATIONS,
                    title = "Notifications",
                    description = "Stay informed about your daily limit and habit streaks.",
                    whyNeeded = "Re:Focus sends timely reminders when you approach or reach your custom reel limits.",
                    state = notificationState
                )
            )
        }

        return list
    }

    /**
     * Checks if all mandatory core requirements (Overlay and Accessibility) are satisfied.
     */
    fun areCoreRequirementsSatisfied(context: Context): Boolean {
        return getOverlayPermissionState(context) == PermissionState.GRANTED &&
                getAccessibilityServiceState(context) == PermissionState.GRANTED
    }

    /**
     * Launches the most direct supported settings intent for Overlay.
     */
    fun openOverlaySettings(context: Context) {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
                val fallbackIntent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                context.startActivity(fallbackIntent)
            } catch (e2: Exception) {
                openAppDetailsSettings(context)
            }
        }
    }

    /**
     * Launches Accessibility settings screen.
     */
    fun openAccessibilitySettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            context.startActivity(intent)
        } catch (e: Exception) {
            openAppDetailsSettings(context)
        }
    }

    /**
     * Launches direct App Info page.
     */
    fun openAppDetailsSettings(context: Context) {
        try {
            val appDetailsIntent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")
            )
            context.startActivity(appDetailsIntent)
        } catch (e: Exception) {
            try {
                val fallback = Intent(Settings.ACTION_SETTINGS)
                context.startActivity(fallback)
            } catch (e2: Exception) {
                // Ignore safe fallback
            }
        }
    }
}
