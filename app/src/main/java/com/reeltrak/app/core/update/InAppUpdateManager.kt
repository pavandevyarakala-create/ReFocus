package com.reeltrak.app.core.update

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.ActivityResult
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import com.google.android.play.core.ktx.requestAppUpdateInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centralized manager for Google Play In-App Updates API.
 *
 * Requirements enforced:
 * - Google Play is the sole source of truth for version detection (NO Firebase/Remote Config).
 * - Mandatory Immediate Update flow (AppUpdateType.IMMEDIATE).
 * - Lock state (UPDATE_REQUIRED / UPDATE_FAILED) cannot be bypassed by cancellation or failure.
 * - Resumes DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS upon onResume / foreground transition.
 * - Safe handling of sideloaded / debug development builds without false crashes.
 */
@Singleton
class InAppUpdateManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "InAppUpdateManager"
        private const val UPDATE_CHECK_COOLDOWN_MS = 15 * 60 * 1000L // 15 minutes cooldown
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val appUpdateManager: AppUpdateManager = AppUpdateManagerFactory.create(context)

    private val _updateStatus = MutableStateFlow(AppUpdateStatus.UPDATE_NOT_REQUIRED)
    val updateStatus: StateFlow<AppUpdateStatus> = _updateStatus.asStateFlow()

    val isUpdateRequired: StateFlow<Boolean> = _updateStatus
        .map { it == AppUpdateStatus.UPDATE_REQUIRED || it == AppUpdateStatus.UPDATE_FAILED || it == AppUpdateStatus.UPDATE_IN_PROGRESS }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val currentInstalledVersionCode: Long = try {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionCode.toLong()
        }
    } catch (e: Exception) {
        1L
    }

    private var cachedAppUpdateInfo: AppUpdateInfo? = null
    @Volatile
    private var lastUpdateCheckTimestampMs: Long = 0L

    /**
     * Checks Google Play for pending immediate updates.
     * Google Play is the sole authority for update availability.
     * Checks are throttled by [UPDATE_CHECK_COOLDOWN_MS] unless [force] is true.
     */
    suspend fun checkForUpdate(force: Boolean = false): AppUpdateStatus = withContext(Dispatchers.IO) {
        val currentElapsed = android.os.SystemClock.elapsedRealtime()
        val currentStatus = _updateStatus.value
        if (!force &&
            currentStatus != AppUpdateStatus.UPDATE_REQUIRED &&
            currentStatus != AppUpdateStatus.UPDATE_IN_PROGRESS &&
            lastUpdateCheckTimestampMs > 0L &&
            (currentElapsed - lastUpdateCheckTimestampMs) < UPDATE_CHECK_COOLDOWN_MS
        ) {
            Log.d(TAG, "Skipping Google Play update check (cooldown active: ${currentElapsed - lastUpdateCheckTimestampMs}ms < ${UPDATE_CHECK_COOLDOWN_MS}ms)")
            return@withContext currentStatus
        }

        try {
            Log.d(TAG, "Requesting AppUpdateInfo from Google Play (installed versionCode: $currentInstalledVersionCode)...")
            val info = appUpdateManager.requestAppUpdateInfo()
            lastUpdateCheckTimestampMs = android.os.SystemClock.elapsedRealtime()
            cachedAppUpdateInfo = info

            val availability = info.updateAvailability()
            val isImmediateAllowed = info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)

            Log.d(TAG, "Play updateAvailability: $availability, isImmediateAllowed: $isImmediateAllowed, availableVersionCode: ${info.availableVersionCode()}, installedVersionCode: $currentInstalledVersionCode")

            when {
                availability == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS -> {
                    _updateStatus.value = AppUpdateStatus.UPDATE_IN_PROGRESS
                }
                availability == UpdateAvailability.UPDATE_AVAILABLE && isImmediateAllowed -> {
                    _updateStatus.value = AppUpdateStatus.UPDATE_REQUIRED
                }
                availability == UpdateAvailability.UPDATE_NOT_AVAILABLE -> {
                    // Update successfully completed or no update available
                    _updateStatus.value = AppUpdateStatus.UPDATE_NOT_REQUIRED
                }
                else -> {
                    // If we were previously locked, remain locked until Play confirms UPDATE_NOT_AVAILABLE
                    if (_updateStatus.value != AppUpdateStatus.UPDATE_REQUIRED && _updateStatus.value != AppUpdateStatus.UPDATE_FAILED) {
                        _updateStatus.value = AppUpdateStatus.UPDATE_NOT_REQUIRED
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query Google Play AppUpdateInfo: ${e.message}")
            // Development / debug safety:
            // Sideloaded or local debug builds may throw InstallException (e.g., app not owned or store unavailable).
            // Do NOT lock the app permanently on initial check if Google Play cannot provide update info.
            // If already locked in UPDATE_REQUIRED / UPDATE_FAILED, remain locked.
        }
        _updateStatus.value
    }

    /**
     * Starts the official Google Play Immediate Update flow using ActivityResultLauncher.
     */
    fun startImmediateUpdate(
        activity: Activity,
        launcher: ActivityResultLauncher<IntentSenderRequest>
    ): Boolean {
        val info = cachedAppUpdateInfo
        if (info == null) {
            Log.w(TAG, "Cannot start update: cachedAppUpdateInfo is null. Refreshing...")
            scope.launch {
                checkForUpdate(force = true)
                val refreshed = cachedAppUpdateInfo
                if (refreshed != null && refreshed.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {
                    val options = AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build()
                    try {
                        appUpdateManager.startUpdateFlowForResult(refreshed, launcher, options)
                        _updateStatus.value = AppUpdateStatus.UPDATE_IN_PROGRESS
                    } catch (e: Exception) {
                        Log.e(TAG, "Error starting immediate update after refresh: ${e.message}", e)
                        _updateStatus.value = AppUpdateStatus.UPDATE_FAILED
                    }
                } else {
                    _updateStatus.value = AppUpdateStatus.UPDATE_FAILED
                }
            }
            return false
        }

        return try {
            val options = AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build()
            val launched = appUpdateManager.startUpdateFlowForResult(info, launcher, options)
            _updateStatus.value = AppUpdateStatus.UPDATE_IN_PROGRESS
            Log.d(TAG, "startUpdateFlowForResult result: $launched")
            launched
        } catch (e: Exception) {
            Log.e(TAG, "Error starting immediate update: ${e.message}", e)
            _updateStatus.value = AppUpdateStatus.UPDATE_FAILED
            false
        }
    }

    /**
     * Handles Activity Result from the Google Play In-App Update flow.
     */
    fun handleActivityResult(resultCode: Int) {
        Log.d(TAG, "handleActivityResult with resultCode: $resultCode")
        when (resultCode) {
            Activity.RESULT_OK -> {
                Log.i(TAG, "Update flow finished with RESULT_OK. Play Store will restart the application.")
            }
            Activity.RESULT_CANCELED -> {
                Log.w(TAG, "Update cancelled by user (RESULT_CANCELED). Enforcing mandatory update lock.")
                _updateStatus.value = AppUpdateStatus.UPDATE_FAILED
            }
            ActivityResult.RESULT_IN_APP_UPDATE_FAILED -> {
                Log.e(TAG, "Update flow failed (RESULT_IN_APP_UPDATE_FAILED). Enforcing mandatory update lock.")
                _updateStatus.value = AppUpdateStatus.UPDATE_FAILED
            }
            else -> {
                Log.w(TAG, "Update flow returned unexpected resultCode ($resultCode). Enforcing mandatory update lock.")
                _updateStatus.value = AppUpdateStatus.UPDATE_FAILED
            }
        }
    }

    /**
     * Resumes an in-progress immediate update if the user returns to the app while an update is pending.
     */
    fun resumeImmediateUpdateIfInProgress(
        activity: Activity,
        launcher: ActivityResultLauncher<IntentSenderRequest>
    ) {
        scope.launch {
            try {
                val info = appUpdateManager.requestAppUpdateInfo()
                cachedAppUpdateInfo = info
                if (info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
                    Log.i(TAG, "DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS detected on resume. Resuming immediate update.")
                    _updateStatus.value = AppUpdateStatus.UPDATE_IN_PROGRESS
                    val options = AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build()
                    appUpdateManager.startUpdateFlowForResult(info, launcher, options)
                } else if (info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE 
                           && info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {
                    _updateStatus.value = AppUpdateStatus.UPDATE_REQUIRED
                } else if (info.updateAvailability() == UpdateAvailability.UPDATE_NOT_AVAILABLE) {
                    _updateStatus.value = AppUpdateStatus.UPDATE_NOT_REQUIRED
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error during resume update check: ${e.message}")
            }
        }
    }
}
