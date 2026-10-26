package com.reeltrak.app.features.auth

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.tasks.await
import com.reeltrak.app.core.auth.GoogleAuthException
import com.reeltrak.app.core.auth.GoogleAuthManager
import com.reeltrak.app.core.auth.UserAuthData
import com.reeltrak.app.core.sync.SyncManager
import com.reeltrak.app.domain.models.AccessState
import com.reeltrak.app.domain.models.AccountStatus
import com.reeltrak.app.domain.repository.AccountRepository
import com.reeltrak.app.domain.repository.PreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

import com.reeltrak.app.core.di.ApplicationScope
import com.reeltrak.app.core.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import androidx.work.WorkManager

enum class StartupAuthState {
    CHECKING,
    AUTHENTICATED,
    FREE_MODE,
    UNAUTHENTICATED
}

sealed class LoginState {
    object Checking : LoginState()
    object Idle : LoginState()
    object Loading : LoginState()
    object AccountRestricted : LoginState()
    object AccountDeleted : LoginState()
    data class PendingDeletion(val userData: UserAuthData, val deletionScheduledFor: Long?) : LoginState()
    data class Success(val userData: UserAuthData) : LoginState()
    object FreeMode : LoginState()
    data class Error(val message: String) : LoginState()
}

@HiltViewModel
class LoginViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val googleAuthManager: GoogleAuthManager,
    private val accountRepository: AccountRepository,
    private val preferencesRepository: PreferencesRepository,
    private val syncManager: SyncManager,
    private val subscriptionManager: com.reeltrak.app.domain.managers.SubscriptionManager,
    private val database: com.reeltrak.app.data.local.database.ReelTrakDatabase,
    private val dayManager: com.reeltrak.app.services.tracking.DayManager,
    @ApplicationScope private val applicationScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ViewModel() {

    private val isSigningOut = AtomicBoolean(false)

    private val _startupAuthState = MutableStateFlow<StartupAuthState>(StartupAuthState.CHECKING)
    val startupAuthState: StateFlow<StartupAuthState> = _startupAuthState.asStateFlow()

    private val _uiState = MutableStateFlow<LoginState>(LoginState.Checking)
    val uiState: StateFlow<LoginState> = _uiState.asStateFlow()

    private val _pendingSubscriptionAfterLogin = MutableStateFlow(false)
    val pendingSubscriptionAfterLogin: StateFlow<Boolean> = _pendingSubscriptionAfterLogin.asStateFlow()

    private val _showLogoutDialog = MutableStateFlow(false)
    val showLogoutDialog: StateFlow<Boolean> = _showLogoutDialog.asStateFlow()

    private val _showOfflineWarning = MutableStateFlow(false)
    val showOfflineWarning: StateFlow<Boolean> = _showOfflineWarning.asStateFlow()

    private val _isSyncingBeforeLogout = MutableStateFlow(false)
    val isSyncingBeforeLogout: StateFlow<Boolean> = _isSyncingBeforeLogout.asStateFlow()

    private val _logoutSyncError = MutableStateFlow<String?>(null)
    val logoutSyncError: StateFlow<String?> = _logoutSyncError.asStateFlow()

    private val _logoutCountdown = MutableStateFlow(10)
    val logoutCountdown: StateFlow<Int> = _logoutCountdown.asStateFlow()

    private var countdownJob: Job? = null
    private var syncBeforeLogoutJob: Job? = null

    private fun cancelPendingSyncWork() {
        try {
            WorkManager.getInstance(context).cancelUniqueWork("FirebaseSync")
        } catch (e: Exception) {
            Log.w("AuthCrashDebug", "Failed to cancel FirebaseSync work", e)
        }
    }

    private suspend fun performSecureTeardown(todayStr: String) {
        cancelPendingSyncWork()
        subscriptionManager.getAppAuthorizationManager()?.revokeAuthorization(
            com.reeltrak.app.data.security.authorization.AuthorizationLockReason.NO_AUTHENTICATED_ACCOUNT
        )
        database.dailyReelDataDao.clearHistoricalDataExcept(todayStr)
        val today = database.dailyReelDataDao.getDailyDataSync(todayStr)
        if (today != null) {
            database.dailyReelDataDao.insertOrReplace(
                today.copy(
                    dailyGoal = -1,
                    isLimitLocked = false,
                    isStrictModeOn = false,
                    isStrictModeLocked = false
                )
            )
        }
        preferencesRepository.clearAllPreferences()
        dayManager.resetAccountSession()
        _pendingSubscriptionAfterLogin.value = false
        googleAuthManager.signOut()
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = connectivityManager?.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    init {
        viewModelScope.launch {
            combine(
                preferencesRepository.authSession,
                preferencesRepository.isFreeMode
            ) { userData, isFree ->
                userData to isFree
            }.collect { (userData, isFree) ->
                if (userData != null) {
                    val currentUi = _uiState.value
                    if (currentUi is LoginState.Success && currentUi.userData.email == userData.email) {
                        Log.d("SUB_DEBUG_AUTH", "User session already active for email=${userData.email}; skipping redundant init check")
                        return@collect
                    }

                    var firebaseUser = Firebase.auth.currentUser
                    if (firebaseUser == null) {
                        // Allow brief moment for Firebase Auth local cache restoration if needed
                        delay(100)
                        firebaseUser = Firebase.auth.currentUser
                    }

                    Log.d("SUB_DEBUG_AUTH", "Session restoration check: email=${userData.email}, Firebase UID=${firebaseUser?.uid}")
                    if (firebaseUser != null) {
                        val accessInfoResult = accountRepository.checkAccountAccessInfo(firebaseUser.uid)
                        val accessInfo = accessInfoResult.getOrDefault(com.reeltrak.app.domain.models.AccountAccessInfo.DEFAULT)
                        when (accessInfo.accountStatus) {
                            AccountStatus.RESTRICTED -> {
                                Log.w("MasterAccess", "Saved session rejected: Account ${firebaseUser.uid} is RESTRICTED.")
                                applicationScope.launch(ioDispatcher) {
                                    withContext(NonCancellable) {
                                        val todayStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
                                        performSecureTeardown(todayStr)
                                    }
                                }
                                _uiState.value = LoginState.AccountRestricted
                                _startupAuthState.value = StartupAuthState.UNAUTHENTICATED
                            }
                            AccountStatus.DELETED -> {
                                Log.w("MasterAccess", "Saved session rejected: Account ${firebaseUser.uid} is DELETED.")
                                applicationScope.launch(ioDispatcher) {
                                    withContext(NonCancellable) {
                                        val todayStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
                                        performSecureTeardown(todayStr)
                                    }
                                }
                                _uiState.value = LoginState.AccountDeleted
                                _startupAuthState.value = StartupAuthState.UNAUTHENTICATED
                            }
                            AccountStatus.PENDING_DELETION -> {
                                Log.w("MasterAccess", "Saved session detected PENDING_DELETION for ${firebaseUser.uid}.")
                                _uiState.value = LoginState.PendingDeletion(userData, accessInfo.deletionScheduledFor)
                                _startupAuthState.value = StartupAuthState.UNAUTHENTICATED
                            }
                            AccountStatus.ACTIVE -> {
                                Log.i("RESTORE_TRACE", "app_start")
                                Log.i("RESTORE_TRACE", "auth_user=${firebaseUser.uid}")
                                Log.d("SUB_DEBUG_AUTH", "Session restored ACTIVE for UID=${firebaseUser.uid}")
                                android.util.Log.d("STARTUP_DEBUG", "LoginViewModel: Session restored ACTIVE for UID=${firebaseUser.uid}. Ensuring server subscription and refreshing.")
                                _uiState.value = LoginState.Success(userData)
                                _startupAuthState.value = StartupAuthState.AUTHENTICATED
                                applicationScope.launch(ioDispatcher) {
                                    executePostLoginSync(firebaseUser.uid)
                                }
                            }
                        }
                    } else {
                        // Ghost session: local data exists but Firebase is signed out.
                        Log.w("AuthCrashDebug", "Ghost session detected. Firebase is not authenticated.")
                        applicationScope.launch(ioDispatcher) {
                            withContext(NonCancellable) {
                                val todayStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
                                performSecureTeardown(todayStr)
                            }
                        }
                        if (isFree) {
                            Log.i("LoginViewModel", "Restoring deliberate Free Mode state from preferences (ghost session cleaned)")
                            _uiState.value = LoginState.FreeMode
                            _startupAuthState.value = StartupAuthState.FREE_MODE
                        } else {
                            _uiState.value = LoginState.Idle
                            _startupAuthState.value = StartupAuthState.UNAUTHENTICATED
                        }
                    }
                } else if (isFree) {
                    Log.i("LoginViewModel", "Restoring deliberate Free Mode state from preferences")
                    _uiState.value = LoginState.FreeMode
                    _startupAuthState.value = StartupAuthState.FREE_MODE
                } else {
                    _uiState.value = LoginState.Idle
                    _startupAuthState.value = StartupAuthState.UNAUTHENTICATED
                }
            }
        }
    }

    private val isPostLoginSyncRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    private var lastSignInTapTimestampMs: Long = 0L

    private suspend fun executePostLoginSync(uid: String) {
        if (!isPostLoginSyncRunning.compareAndSet(false, true)) {
            Log.d("AUTH_PERF", "[AUTH_PERF] Post-login sync already in progress, skipping duplicate call")
            return
        }
        val t0 = android.os.SystemClock.elapsedRealtime()
        val tapTimestampMs = lastSignInTapTimestampMs
        try {
            Log.d("AUTH_PERF", "[AUTH_PERF] executePostLoginSync started")
            Log.i("RESTORE_TRACE", "auth_user=$uid")
            Log.i("RESTORE_TRACE", "entitlement_check_start")
            val tSubStart = android.os.SystemClock.elapsedRealtime()
            val accessState = try {
                subscriptionManager.ensureAndAwaitEntitlement()
            } catch (e: Exception) {
                Log.w("AuthCrashDebug", "Failed to ensure and await entitlement during login sync: ${e.message}", e)
                subscriptionManager.currentAccessState()
            }
            val tSubDone = android.os.SystemClock.elapsedRealtime()
            Log.d("AUTH_PERF", "[AUTH_PERF] Stage 6 (Subscription & authorization checks): ${tSubDone - tSubStart}ms (accessState=$accessState)")

            Log.i("RESTORE_TRACE", "entitlement=$accessState")
            val isEntitled = accessState == AccessState.TRIAL || accessState == AccessState.ACTIVE
            Log.i("RESTORE_TRACE", "cloud_sync_allowed=$isEntitled")

            if (isEntitled) {
                // Strict manual-only policy: No automatic cloud migration or restore upon sign-in.
                // Local data remains strictly local until the user explicitly requests Back Up Data or Restore Data.
                val tSyncStart = android.os.SystemClock.elapsedRealtime()
                val restoredGoal = preferencesRepository.defaultDailyLimit.first()
                if (restoredGoal > 0) {
                    dayManager.restoreActiveDailyLimit(restoredGoal)
                }
                val tSyncDone = android.os.SystemClock.elapsedRealtime()
                Log.d("AUTH_PERF", "[AUTH_PERF] Stage 7c (Local preferences active): ${tSyncDone - tSyncStart}ms")
            }
            val totalFinished = android.os.SystemClock.elapsedRealtime()
            val totalPipelineStr = if (tapTimestampMs > 0L) " (Total end-to-end sign-in pipeline: ${totalFinished - tapTimestampMs}ms)" else ""
            Log.d("AUTH_PERF", "[AUTH_PERF] executePostLoginSync finished in ${totalFinished - t0}ms total$totalPipelineStr")
        } finally {
            isPostLoginSyncRunning.set(false)
        }
    }

    fun signIn(
        activityContext: Context? = null,
        tapTimestampMs: Long = android.os.SystemClock.elapsedRealtime()
    ) {
        if (_uiState.value is LoginState.Loading) {
            Log.d("LoginViewModel", "Sign in already in progress, ignoring duplicate tap")
            return
        }

        lastSignInTapTimestampMs = tapTimestampMs

        if (!isNetworkAvailable()) {
            _uiState.value = LoginState.Error("No internet connection. Please check your network and try again.")
            return
        }

        viewModelScope.launch {
            val t0 = tapTimestampMs
            Log.d("AUTH_PERF", "[AUTH_PERF] Google Sign-In initiated")
            try {
                _uiState.value = LoginState.Loading
                val contextToUse = activityContext ?: context
                googleAuthManager.signIn(contextToUse, t0).fold(
                    onSuccess = { userData ->
                        val tAuth = android.os.SystemClock.elapsedRealtime()
                        Log.d("AUTH_PERF", "[AUTH_PERF] Google + Firebase Auth succeeded in ${tAuth - t0}ms")
                        val firebaseUid = Firebase.auth.currentUser?.uid
                        if (firebaseUid != null) {
                            val tAccountStart = android.os.SystemClock.elapsedRealtime()
                            val accessInfoResult = accountRepository.checkAccountAccessInfo(firebaseUid)
                            val tAccountDone = android.os.SystemClock.elapsedRealtime()
                            if (accessInfoResult.isFailure) {
                                Log.e("MasterAccess", "Google login rejected: Failed to verify account access info for $firebaseUid", accessInfoResult.exceptionOrNull())
                                Log.d("AUTH_PERF", "[AUTH_PERF] Stage 5 (Account/profile data resolution): FAILED after ${tAccountDone - tAccountStart}ms (fail-closed)")
                                _uiState.value = LoginState.Error("Unable to verify account access. Please check your internet connection and try again.")
                                return@fold
                            }
                            val accessInfo = accessInfoResult.getOrThrow()
                            Log.d("AUTH_PERF", "[AUTH_PERF] Stage 5 (Account/profile data resolution): ${tAccountDone - tAccountStart}ms (status=${accessInfo.accountStatus})")
                            when (accessInfo.accountStatus) {
                                AccountStatus.RESTRICTED -> {
                                    Log.w("MasterAccess", "Login rejected: Account $firebaseUid is RESTRICTED.")
                                    applicationScope.launch(ioDispatcher) {
                                        withContext(NonCancellable) {
                                            val todayStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
                                            performSecureTeardown(todayStr)
                                        }
                                    }
                                    _uiState.value = LoginState.AccountRestricted
                                    return@fold
                                }
                                AccountStatus.DELETED -> {
                                    Log.w("MasterAccess", "Login rejected: Account $firebaseUid is DELETED.")
                                    applicationScope.launch(ioDispatcher) {
                                        withContext(NonCancellable) {
                                            val todayStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
                                            performSecureTeardown(todayStr)
                                        }
                                    }
                                    _uiState.value = LoginState.AccountDeleted
                                    return@fold
                                }
                                AccountStatus.PENDING_DELETION -> {
                                    Log.w("MasterAccess", "Login detected PENDING_DELETION: Account $firebaseUid.")
                                    _uiState.value = LoginState.PendingDeletion(userData, accessInfo.deletionScheduledFor)
                                    return@fold
                                }
                                AccountStatus.ACTIVE -> {
                                    // Proceed to normal flow below
                                }
                            }
                        } else {
                            Log.d("AUTH_PERF", "[AUTH_PERF] Stage 5 (Account/profile data resolution): skipped, no UID")
                        }

                        // Immediate transition to Success state navigates to Dashboard immediately
                        val tNavStart = android.os.SystemClock.elapsedRealtime()
                        _uiState.value = LoginState.Success(userData)
                        _startupAuthState.value = StartupAuthState.AUTHENTICATED
                        val tNavDone = android.os.SystemClock.elapsedRealtime()
                        Log.d("AUTH_PERF", "[AUTH_PERF] Stage 7a (UI navigation to Success state): ${tNavDone - tNavStart}ms (Total tap-to-dashboard time: ${tNavDone - t0}ms)")

                        val tSave = android.os.SystemClock.elapsedRealtime()
                        preferencesRepository.saveAuthSession(userData)
                        preferencesRepository.setFreeMode(false)
                        Log.d("AUTH_PERF", "[AUTH_PERF] Stage 7b (Auth session saved): ${android.os.SystemClock.elapsedRealtime() - tSave}ms")

                        val currentUid = Firebase.auth.currentUser?.uid ?: ""
                        Log.d("SUB_DEBUG_AUTH", "Google Sign in SUCCESS for UID=$currentUid, email=${userData.email}")

                        // Run non-critical cloud restoration concurrently in background
                        applicationScope.launch(ioDispatcher) {
                            executePostLoginSync(currentUid)
                        }
                    },
                    onFailure = { throwable ->
                        when (throwable) {
                            is GoogleAuthException.UserCancelled -> {
                                // User simply cancelled the account picker; revert to Idle without showing an error banner
                                Log.d("AuthCrashDebug", "User cancelled Google Sign In")
                                _uiState.value = LoginState.Idle
                            }
                            is GoogleAuthException.NoAccountsAvailable -> {
                                Log.w("AuthCrashDebug", "No Google accounts available on device")
                                _uiState.value = LoginState.Error("No Google account found on this device. Please add an account in Android Settings.")
                            }
                            is GoogleAuthException.NetworkError -> {
                                Log.e("AuthCrashDebug", "Network error during Google Sign In", throwable)
                                _uiState.value = LoginState.Error("Network error. Please check your internet connection and try again.")
                            }
                            is GoogleAuthException.ConfigurationError -> {
                                Log.e("AuthCrashDebug", "Configuration error during Google Sign In", throwable)
                                _uiState.value = LoginState.Error("Google Play Services is updating or unavailable. Please try again.")
                            }
                            is GoogleAuthException.FirebaseAuthFailed -> {
                                Log.e("AuthCrashDebug", "Firebase auth failed during Google Sign In", throwable)
                                _uiState.value = LoginState.Error(throwable.message ?: "Authentication failed. Please try again.")
                            }
                            else -> {
                                Log.e("AuthCrashDebug", "LoginViewModel: Sign in failed", throwable)
                                _uiState.value = LoginState.Error(throwable.message?.takeIf { it.isNotBlank() } ?: "Sign in failed. Please try again.")
                            }
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e("AuthCrashDebug", "LoginViewModel: Unexpected exception during sign in", e)
                _uiState.value = LoginState.Error("An unexpected error occurred during sign in.")
            }
        }
    }

    /**
     * Authenticates with Firebase using Email + Password.
     * Validated solely by Firebase Authentication without hardcoded values, credentials logging, or privilege escalations.
     */
    fun signInWithEmailPassword(emailInput: String, passwordInput: String) {
        val email = emailInput.trim()
        val password = passwordInput

        if (email.isBlank() || password.isBlank()) {
            _uiState.value = LoginState.Error("Please enter both email and password.")
            return
        }

        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            _uiState.value = LoginState.Error("Please enter a valid email address.")
            return
        }

        if (!isNetworkAvailable()) {
            _uiState.value = LoginState.Error("Unable to sign in. Please check your internet connection and try again.")
            return
        }

        viewModelScope.launch {
            val t0 = android.os.SystemClock.elapsedRealtime()
            Log.d("AUTH_PERF", "[AUTH_PERF] Email Sign-In initiated")
            try {
                _uiState.value = LoginState.Loading
                val authResult = Firebase.auth.signInWithEmailAndPassword(email, password).await()
                val firebaseUser = authResult.user
                val tAuth = android.os.SystemClock.elapsedRealtime()
                Log.d("AUTH_PERF", "[AUTH_PERF] Email Auth succeeded in ${tAuth - t0}ms")

                if (firebaseUser != null) {
                    val uid = firebaseUser.uid
                    val tAccessStart = android.os.SystemClock.elapsedRealtime()
                    val accessInfoResult = accountRepository.checkAccountAccessInfo(uid)
                    val tAccess = android.os.SystemClock.elapsedRealtime()
                    if (accessInfoResult.isFailure) {
                        Log.e("MasterAccess", "Email login rejected: Failed to verify account access info for $uid", accessInfoResult.exceptionOrNull())
                        Log.d("AUTH_PERF", "[AUTH_PERF] Account access check failed after ${tAccess - tAccessStart}ms (fail-closed)")
                        _uiState.value = LoginState.Error("Unable to verify account access. Please check your internet connection and try again.")
                        return@launch
                    }
                    val accessInfo = accessInfoResult.getOrThrow()
                    Log.d("AUTH_PERF", "[AUTH_PERF] Account access check completed in ${tAccess - tAccessStart}ms, status=${accessInfo.accountStatus}")
                    
                    val userData = UserAuthData(
                        email = firebaseUser.email ?: email,
                        displayName = firebaseUser.displayName ?: firebaseUser.email?.substringBefore("@") ?: "User",
                        profilePictureUri = firebaseUser.photoUrl?.toString() ?: ""
                    )

                    when (accessInfo.accountStatus) {
                        AccountStatus.RESTRICTED -> {
                            Log.w("MasterAccess", "Email login rejected: Account $uid is RESTRICTED.")
                            applicationScope.launch(ioDispatcher) {
                                withContext(NonCancellable) {
                                    val todayStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
                                    performSecureTeardown(todayStr)
                                }
                            }
                            _uiState.value = LoginState.AccountRestricted
                            return@launch
                        }
                        AccountStatus.DELETED -> {
                            Log.w("MasterAccess", "Email login rejected: Account $uid is DELETED.")
                            applicationScope.launch(ioDispatcher) {
                                withContext(NonCancellable) {
                                    val todayStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
                                    performSecureTeardown(todayStr)
                                }
                            }
                            _uiState.value = LoginState.AccountDeleted
                            return@launch
                        }
                        AccountStatus.PENDING_DELETION -> {
                            Log.w("MasterAccess", "Email login detected PENDING_DELETION: Account $uid.")
                            _uiState.value = LoginState.PendingDeletion(userData, accessInfo.deletionScheduledFor)
                            return@launch
                        }
                        AccountStatus.ACTIVE -> {
                            // Proceed to normal flow
                        }
                    }

                    // Immediate transition to Success state navigates to Dashboard immediately
                    _uiState.value = LoginState.Success(userData)
                    _startupAuthState.value = StartupAuthState.AUTHENTICATED
                    Log.d("AUTH_PERF", "[AUTH_PERF] Login state set to Success (ready to navigate) in ${android.os.SystemClock.elapsedRealtime() - t0}ms total")

                    val tSave = android.os.SystemClock.elapsedRealtime()
                    preferencesRepository.saveAuthSession(userData)
                    preferencesRepository.setFreeMode(false)
                    Log.d("AUTH_PERF", "[AUTH_PERF] Session saved in ${android.os.SystemClock.elapsedRealtime() - tSave}ms")

                    // Run non-critical cloud restoration concurrently in background
                    applicationScope.launch(ioDispatcher) {
                        executePostLoginSync(uid)
                    }
                } else {
                    _uiState.value = LoginState.Error("Unable to complete sign in. Please try again.")
                }
            } catch (e: com.google.firebase.auth.FirebaseAuthInvalidCredentialsException) {
                _uiState.value = LoginState.Error("Email or password is incorrect.")
            } catch (e: com.google.firebase.auth.FirebaseAuthInvalidUserException) {
                _uiState.value = LoginState.Error("This account is currently unavailable.")
            } catch (e: com.google.firebase.FirebaseNetworkException) {
                _uiState.value = LoginState.Error("Unable to sign in. Please check your internet connection and try again.")
            } catch (e: Exception) {
                Log.e("AuthCrashDebug", "LoginViewModel: Sign in with email failed", e)
                _uiState.value = LoginState.Error("Sign in failed. Please try again.")
            }
        }
    }

    fun restoreAccount(userData: UserAuthData) {
        viewModelScope.launch {
            val t0 = android.os.SystemClock.elapsedRealtime()
            Log.d("AUTH_PERF", "[AUTH_PERF] restoreAccount initiated")
            _uiState.value = LoginState.Loading
            val cancelResult = accountRepository.cancelAccountDeletion()
            cancelResult.fold(
                onSuccess = {
                    val newUid = Firebase.auth.currentUser?.uid ?: ""
                    _uiState.value = LoginState.Success(userData)
                    _startupAuthState.value = StartupAuthState.AUTHENTICATED
                    Log.d("AUTH_PERF", "[AUTH_PERF] Account restored, navigated in ${android.os.SystemClock.elapsedRealtime() - t0}ms")

                    preferencesRepository.saveAuthSession(userData)
                    preferencesRepository.setFreeMode(false)

                    applicationScope.launch(ioDispatcher) {
                        executePostLoginSync(newUid)
                    }
                },
                onFailure = { error ->
                    Log.e("MasterAccess", "Failed to cancel deletion", error)
                    if (error.message?.contains("410") == true) {
                        applicationScope.launch(ioDispatcher) {
                            withContext(NonCancellable) {
                                val todayStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
                                performSecureTeardown(todayStr)
                            }
                        }
                        _uiState.value = LoginState.AccountDeleted
                    } else {
                        _uiState.value = LoginState.Error("Failed to restore account. Please try again.")
                    }
                }
            )
        }
    }

    fun keepScheduledDeletion() {
        applicationScope.launch(ioDispatcher) {
            try {
                withContext(NonCancellable) {
                    val todayStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
                    performSecureTeardown(todayStr)
                }
            } catch (e: Exception) {
                Log.w("MasterAccess", "Error signing out on keepScheduledDeletion: ${e.message}")
            } finally {
                _uiState.value = LoginState.Idle
                _startupAuthState.value = StartupAuthState.UNAUTHENTICATED
            }
        }
    }

    fun dismissDeletedState() {
        applicationScope.launch(ioDispatcher) {
            try {
                withContext(NonCancellable) {
                    val todayStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
                    performSecureTeardown(todayStr)
                }
            } catch (e: Exception) {
                Log.w("MasterAccess", "Error signing out deleted user: ${e.message}")
            } finally {
                _uiState.value = LoginState.Idle
                _startupAuthState.value = StartupAuthState.UNAUTHENTICATED
            }
        }
    }

    fun initiateLogout() {
        // Prevent opening duplicate dialog or re-triggering during active flow
        if (_showLogoutDialog.value || isSigningOut.get()) {
            Log.d("AuthCrashDebug", "Logout flow already active, ignoring duplicate initiateLogout call")
            return
        }

        _logoutSyncError.value = null
        _showOfflineWarning.value = false
        _showLogoutDialog.value = true
        _logoutCountdown.value = 10

        // 1. Authoritative 10-second countdown loop (strictly one countdown)
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            for (i in 10 downTo 1) {
                _logoutCountdown.value = i
                delay(1000)
            }
            _logoutCountdown.value = 0
        }

        // 2. Strict manual-only policy: Never automatically upload habit data to the cloud on sign-out.
        // In-memory tracking is flushed directly to local Room DB to preserve local persistence.
        _isSyncingBeforeLogout.value = false
        syncBeforeLogoutJob?.cancel()
        syncBeforeLogoutJob = viewModelScope.launch {
            try {
                dayManager.flushPendingWritesSuspend()
            } catch (e: Exception) {
                Log.w("AuthCrashDebug", "Failed to flush pending writes before logout", e)
            }
        }
    }

    fun retryLogoutSync() {
        _logoutSyncError.value = null
        _showOfflineWarning.value = false
        initiateLogout()
    }

    fun cancelLogout() {
        _showLogoutDialog.value = false
        _showOfflineWarning.value = false
        _logoutSyncError.value = null
        countdownJob?.cancel()
        countdownJob = null
        _logoutCountdown.value = 10
    }

    fun signOut() {
        // Enforce the full 10-second requirement at ViewModel level
        if (_logoutCountdown.value > 0) {
            Log.w("AuthCrashDebug", "signOut rejected: countdown still active (${_logoutCountdown.value}s remaining)")
            return
        }

        if (!isSigningOut.compareAndSet(false, true)) {
            Log.d("AuthCrashDebug", "Sign out already in progress, skipping duplicate call")
            return
        }

        _showLogoutDialog.value = false
        countdownJob?.cancel()
        countdownJob = null

        Log.d("SUB_DEBUG_AUTH", "signOut() initiated for UID=${Firebase.auth.currentUser?.uid}")

        applicationScope.launch(ioDispatcher) {
            try {
                // Ensure local memory writes are flushed to disk before teardown
                syncBeforeLogoutJob?.join()
                dayManager.flushPendingWritesSuspend()

                withContext(NonCancellable) {
                    val todayStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
                    performSecureTeardown(todayStr)
                    _uiState.value = LoginState.Idle
                    _startupAuthState.value = StartupAuthState.UNAUTHENTICATED
                }
            } catch (e: Exception) {
                Log.e("AuthCrashDebug", "LoginViewModel: Unexpected exception during sign out", e)
                _uiState.value = LoginState.Error("An error occurred during sign out.")
            } finally {
                isSigningOut.set(false)
            }
        }
    }

    fun dismissRestrictedState() {
        applicationScope.launch(ioDispatcher) {
            try {
                withContext(NonCancellable) {
                    val todayStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
                    performSecureTeardown(todayStr)
                }
            } catch (e: Exception) {
                Log.w("MasterAccess", "Error signing out restricted user: ${e.message}")
            } finally {
                _uiState.value = LoginState.Idle
                _startupAuthState.value = StartupAuthState.UNAUTHENTICATED
            }
        }
    }

    fun resetState() {
        _uiState.value = LoginState.Idle
    }

    fun setPendingSubscriptionAfterLogin(pending: Boolean) {
        _pendingSubscriptionAfterLogin.value = pending
    }

    fun clearPendingSubscriptionAfterLogin() {
        _pendingSubscriptionAfterLogin.value = false
    }

    fun continueAsFree() {
        Log.i("LoginViewModel", "User opted to continue in deliberate Free Mode")
        _pendingSubscriptionAfterLogin.value = false
        _uiState.value = LoginState.FreeMode
        _startupAuthState.value = StartupAuthState.FREE_MODE
        viewModelScope.launch {
            preferencesRepository.setFreeMode(true)
        }
    }

    suspend fun isOnboardingNeeded(): Boolean {
        // 1. If firstRunDate has already been set, user has completed first launch on this device
        val firstRun = preferencesRepository.firstRunDate.first()
        if (firstRun > 0L) return false

        // 2. If a daily limit or daily intent is already configured, user is returning
        val defaultLimit = preferencesRepository.defaultDailyLimit.first()
        if (defaultLimit > 0) return false
        val intent = preferencesRepository.dailyIntent.first()
        if (intent > 0) return false

        // 3. If local database has records, user is an existing user
        try {
            val hasLocalData = database.dailyReelDataDao.getAllDailyDataSync().isNotEmpty()
            if (hasLocalData) return false
        } catch (_: Exception) {}

        return true
    }
}
