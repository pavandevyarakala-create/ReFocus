package com.reeltrak.app.features.settings.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reeltrak.app.core.sync.SyncManager
import com.reeltrak.app.domain.managers.SubscriptionManager
import com.reeltrak.app.domain.models.SubscriptionInfo
import com.reeltrak.app.domain.models.SubscriptionPlan
import com.reeltrak.app.domain.models.SubscriptionState
import com.reeltrak.app.domain.repository.PreferencesRepository
import com.reeltrak.app.domain.services.CheckoutState
import com.reeltrak.app.domain.services.SubscriptionPurchaseService
import com.reeltrak.app.services.tracking.DayManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.reeltrak.app.data.security.authorization.AppAuthorizationManager
import com.reeltrak.app.data.security.authorization.OperationalMode
import com.reeltrak.app.domain.models.AccountAccessInfo
import com.reeltrak.app.domain.models.AccountStatus
import com.reeltrak.app.domain.models.ThemeMode
import com.reeltrak.app.domain.repository.AccountRepository
import com.reeltrak.app.domain.repository.OnboardingRepository
import javax.inject.Inject

enum class RestoreState { IDLE, LOADING, SUCCESS, ERROR }

sealed class RestoreUiState {
    object Idle : RestoreUiState()
    object InProgress : RestoreUiState()
    data class Success(val message: String = "Data restored successfully") : RestoreUiState()
    data class Error(val message: String = "Restore failed. Tap to retry.") : RestoreUiState()
}

sealed class BackupUiState {
    object Idle : BackupUiState()
    object InProgress : BackupUiState()
    data class Success(val message: String = "Backup completed") : BackupUiState()
    data class Error(val message: String = "Backup failed. Tap to retry.") : BackupUiState()
    data class AlreadyBackedUpToday(val message: String = "Your data is already backed up today.") : BackupUiState()
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferencesRepository: PreferencesRepository,
    private val accountRepository: AccountRepository,
    private val onboardingRepository: OnboardingRepository,
    private val subscriptionManager: SubscriptionManager,
    private val purchaseService: SubscriptionPurchaseService,
    private val dayManager: DayManager,
    private val syncManager: SyncManager,
    private val appAuthorizationManager: AppAuthorizationManager
) : ViewModel() {

    val isBackedUpToday: StateFlow<Boolean> = syncManager.isBackedUpToday
    val isBackupRestricted: StateFlow<Boolean> = syncManager.isBackupRestricted
    val nextEligibleBackupTimeMs: StateFlow<Long> = syncManager.nextEligibleBackupTimeMs
    val lastBackupServerTimeMs: StateFlow<Long> = syncManager.lastBackupServerTimeMs

    // OPT-005: Backup status is authoritatively streamed via SyncManager's real-time Firestore
    // listener (backupStatusRegistration). Redundant HTTP polling to /api/backup/status eliminated.

    val accountAccessInfo: StateFlow<AccountAccessInfo> = accountRepository.accountAccessInfo
        .stateIn(viewModelScope, SharingStarted.Eagerly, AccountAccessInfo.LOADING)

    val accountStatus: StateFlow<AccountStatus> = accountRepository.accountStatus
        .stateIn(viewModelScope, SharingStarted.Eagerly, AccountStatus.ACTIVE)


    val isStrictModeEnabled: StateFlow<Boolean?> = dayManager.currentDayStats
        .map { it?.isStrictModeOn }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val isStrictModeLocked: StateFlow<Boolean> = dayManager.currentDayStats
        .map { it?.isStrictModeLocked ?: false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val defaultDailyLimit: StateFlow<Int> = preferencesRepository.defaultDailyLimit
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), -1)

    val currentDailyGoal: StateFlow<Int?> = dayManager.currentDayStats
        .map { it?.dailyGoal }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val dailyIntent: StateFlow<Int> = preferencesRepository.dailyIntent
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val notificationsEnabled: StateFlow<Boolean> = preferencesRepository.notificationsEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val counterThemeMode: StateFlow<ThemeMode> = preferencesRepository.counterThemeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ThemeMode.LIGHT)

    private val _isTourCompleted = MutableStateFlow(true)
    val isTourCompleted: StateFlow<Boolean> = _isTourCompleted.asStateFlow()

    fun checkTourStatus(userId: String?) {
        viewModelScope.launch {
            _isTourCompleted.value = onboardingRepository.isAppTourCompleted(userId)
        }
    }

    fun setHasCompletedTour(userId: String?, completed: Boolean = true) {
        viewModelScope.launch {
            _isTourCompleted.value = completed
            onboardingRepository.setAppTourCompleted(userId, completed)
        }
    }

    // Centralized subscription state consumed via SubscriptionManager
    val subscriptionState: StateFlow<SubscriptionState> = subscriptionManager.state
    val subscriptionInfo: StateFlow<SubscriptionInfo> = subscriptionManager.subscriptionInfo
    val accessState: StateFlow<com.reeltrak.app.domain.models.AccessState> = subscriptionManager.accessState
    val isPremium: StateFlow<Boolean> = subscriptionManager.isPremium
    val remainingTrialDurationMs: StateFlow<Long> = subscriptionManager.remainingTrialDurationMs
    val remainingDurationMs: StateFlow<Long> = subscriptionManager.remainingSubscriptionDurationMs


    // Checkout service state
    val checkoutState: StateFlow<CheckoutState> = purchaseService.checkoutState

    // UI state for subscription paywall / sheet
    private val _showSubscriptionPaywall = MutableStateFlow(false)
    val showSubscriptionPaywall: StateFlow<Boolean> = _showSubscriptionPaywall.asStateFlow()

    // UI state for Login Required dialog
    private val _showLoginRequiredDialog = MutableStateFlow(false)
    val showLoginRequiredDialog: StateFlow<Boolean> = _showLoginRequiredDialog.asStateFlow()

    fun dismissLoginRequiredDialog() {
        _showLoginRequiredDialog.value = false
    }

    // UI state for Daily Limit dialog (for entitled users)
    private val _showDailyLimitDialog = MutableStateFlow(false)
    val showDailyLimitDialog: StateFlow<Boolean> = _showDailyLimitDialog.asStateFlow()

    // UI state for Daily Limit entitlement dialog (for Free / unentitled users)
    private val _showDailyLimitEntitlementDialog = MutableStateFlow<OperationalMode?>(null)
    val showDailyLimitEntitlementDialog: StateFlow<OperationalMode?> = _showDailyLimitEntitlementDialog.asStateFlow()

    val operationalMode: StateFlow<OperationalMode> = combine(
        subscriptionManager.subscriptionInfo,
        appAuthorizationManager.authorizationState
    ) { info, _ ->
        appAuthorizationManager.getOperationalMode(info)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = appAuthorizationManager.getOperationalMode()
    )

    val isDailyLimitEntitled: StateFlow<Boolean> = operationalMode
        .map { it.isEntitled }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = appAuthorizationManager.getOperationalMode().isEntitled
        )

    fun onDailyLimitSettingClicked() {
        val mode = appAuthorizationManager.getOperationalMode()
        if (!mode.isEntitled) {
            _showDailyLimitEntitlementDialog.value = mode
        } else {
            _showDailyLimitDialog.value = true
        }
    }

    fun dismissDailyLimitDialog() {
        _showDailyLimitDialog.value = false
    }

    fun dismissDailyLimitEntitlementDialog() {
        _showDailyLimitEntitlementDialog.value = null
    }

    private val _isRefreshingSubscription = MutableStateFlow(false)
    val isRefreshingSubscription: StateFlow<Boolean> = _isRefreshingSubscription.asStateFlow()

    private val _restoreState = MutableStateFlow(RestoreState.IDLE)
    val restoreState: StateFlow<RestoreState> = _restoreState

    private val _restoreUiState = MutableStateFlow<RestoreUiState>(RestoreUiState.Idle)
    val restoreUiState: StateFlow<RestoreUiState> = _restoreUiState.asStateFlow()

    fun setStrictMode(enabled: Boolean) {
        if (!subscriptionManager.hasActiveEntitlementNow()) return
        dayManager.setStrictMode(enabled)
    }

    fun saveDailyLimit(goal: Int) {
        val mode = appAuthorizationManager.getOperationalMode()
        if (!mode.isEntitled) {
            android.util.Log.w("SettingsViewModel", "saveDailyLimit rejected: Daily Limit requires Trial or Premium. Mode: $mode")
            _showDailyLimitEntitlementDialog.value = mode
            return
        }
        viewModelScope.launch {
            preferencesRepository.setDefaultDailyLimit(goal)
            preferencesRepository.setDailyIntent(goal)
            dayManager.setDailyGoal(goal, forceUpdate = true)
        }
    }

    fun setDailyIntent(intent: Int) {
        val mode = appAuthorizationManager.getOperationalMode()
        if (!mode.isEntitled) return
        viewModelScope.launch {
            preferencesRepository.setDailyIntent(intent)
        }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setNotificationsEnabled(enabled)
        }
    }

    fun setCounterThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            preferencesRepository.setCounterThemeMode(mode)
        }
    }

    fun openSubscriptionPaywall() {
        if (appAuthorizationManager.getOperationalMode() == OperationalMode.UNAUTHENTICATED_FREE) {
            _showLoginRequiredDialog.value = true
            return
        }
        purchaseService.resetCheckoutState()
        purchaseService.preload()
        _showSubscriptionPaywall.value = true
    }

    fun closeSubscriptionPaywall() {
        _showSubscriptionPaywall.value = false
        purchaseService.resetCheckoutState()
    }

    fun subscribe(plan: SubscriptionPlan = SubscriptionPlan.MONTHLY) {
        if (appAuthorizationManager.getOperationalMode() == OperationalMode.UNAUTHENTICATED_FREE) {
            _showSubscriptionPaywall.value = false
            _showLoginRequiredDialog.value = true
            return
        }
        viewModelScope.launch {
            purchaseService.initiatePurchase(plan)
        }
    }

    fun resetCheckoutState() {
        purchaseService.resetCheckoutState()
    }

    fun refreshSubscription() {
        if (appAuthorizationManager.getOperationalMode() == OperationalMode.UNAUTHENTICATED_FREE) {
            return
        }
        if (_isRefreshingSubscription.value) return
        viewModelScope.launch {
            _isRefreshingSubscription.value = true
            subscriptionManager.refreshSubscription()
            _isRefreshingSubscription.value = false
        }
    }

    private val _isCancellingSubscription = MutableStateFlow(false)
    val isCancellingSubscription: StateFlow<Boolean> = _isCancellingSubscription.asStateFlow()

    fun cancelSubscription(onSuccess: () -> Unit = {}, onFailure: (String) -> Unit = {}) {
        if (_isCancellingSubscription.value) return
        viewModelScope.launch {
            _isCancellingSubscription.value = true
            val result = subscriptionManager.cancelSubscription()
            _isCancellingSubscription.value = false
            result.fold(
                onSuccess = { onSuccess() },
                onFailure = { error -> onFailure(error.message ?: "Failed to cancel subscription") }
            )
        }
    }

    private val _deletionState = MutableStateFlow<Result<Long>?>(null)
    val deletionState: StateFlow<Result<Long>?> = _deletionState.asStateFlow()

    private val _isDeletingAccount = MutableStateFlow(false)
    val isDeletingAccount: StateFlow<Boolean> = _isDeletingAccount.asStateFlow()

    fun requestAccountDeletion(onSuccess: (Long) -> Unit, onFailure: (String) -> Unit) {
        if (_isDeletingAccount.value) return
        viewModelScope.launch {
            _isDeletingAccount.value = true
            val result = accountRepository.requestAccountDeletion()
            _isDeletingAccount.value = false
            _deletionState.value = result
            result.fold(
                onSuccess = { scheduledFor ->
                    onSuccess(scheduledFor)
                },
                onFailure = { error ->
                    onFailure(error.message ?: "Failed to schedule account deletion")
                }
            )
        }
    }

    fun resetDeletionState() {
        _deletionState.value = null
    }

    fun restoreBackup() {
        if (!subscriptionManager.hasActiveEntitlementNow()) {
            android.util.Log.w("Restore", "restoreBackup rejected: User has no active entitlement.")
            _restoreUiState.value = RestoreUiState.Error("Cloud Restore requires an active Trial or Premium subscription.")
            return
        }
        if (_restoreUiState.value is RestoreUiState.InProgress || _restoreState.value == RestoreState.LOADING) {
            android.util.Log.d("Restore", "restoreBackup already in progress, ignoring duplicate tap")
            return
        }
        if (_backupUiState.value is BackupUiState.InProgress) {
            android.util.Log.d("Restore", "performBackup currently in progress, ignoring restore")
            return
        }
        viewModelScope.launch {
            _restoreState.value = RestoreState.LOADING
            _restoreUiState.value = RestoreUiState.InProgress
            val result = syncManager.restoreFromCloud()
            if (result.isSuccess) {
                _restoreState.value = RestoreState.SUCCESS
                _restoreUiState.value = RestoreUiState.Success("Data restored successfully")
                kotlinx.coroutines.delay(3500)
                if (_restoreUiState.value is RestoreUiState.Success) {
                    _restoreUiState.value = RestoreUiState.Idle
                    _restoreState.value = RestoreState.IDLE
                }
            } else {
                _restoreState.value = RestoreState.ERROR
                val errorMsg = result.exceptionOrNull()?.message ?: "Restore failed. Tap to retry."
                _restoreUiState.value = RestoreUiState.Error(errorMsg)
            }
        }
    }

    fun resetRestoreState() {
        _restoreState.value = RestoreState.IDLE
        _restoreUiState.value = RestoreUiState.Idle
    }

    val hasActiveEntitlement: StateFlow<Boolean> = subscriptionManager.hasActiveEntitlement

    val lastBackupTime: StateFlow<Long> = preferencesRepository.lastBackupTime
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    private val _backupUiState = MutableStateFlow<BackupUiState>(BackupUiState.Idle)
    val backupUiState: StateFlow<BackupUiState> = _backupUiState.asStateFlow()

    fun performBackup() {
        if (!subscriptionManager.hasActiveEntitlementNow()) {
            android.util.Log.w("Backup", "performBackup rejected: User has no active entitlement.")
            return
        }
        if (_backupUiState.value is BackupUiState.InProgress) {
            android.util.Log.d("Backup", "performBackup already in progress, ignoring duplicate tap")
            return
        }
        if (_restoreUiState.value is RestoreUiState.InProgress || _restoreState.value == RestoreState.LOADING) {
            android.util.Log.d("Backup", "restoreBackup currently in progress, ignoring backup")
            return
        }
        if (isBackupRestricted.value || isBackedUpToday.value) {
            android.util.Log.d("Backup", "performBackup ignored: 24-hour backup restriction active")
            _backupUiState.value = BackupUiState.AlreadyBackedUpToday("Your data is already backed up. Next backup available in 24 hours.")
            return
        }
        viewModelScope.launch {
            _backupUiState.value = BackupUiState.InProgress
            val result = syncManager.performFullBackup()
            if (result.isSuccess) {
                _backupUiState.value = BackupUiState.Success("Backup completed")
                kotlinx.coroutines.delay(3500)
                if (_backupUiState.value is BackupUiState.Success) {
                    _backupUiState.value = BackupUiState.Idle
                }
            } else {
                val ex = result.exceptionOrNull()
                if (ex is com.reeltrak.app.core.sync.BackupAlreadyCompletedException) {
                    _backupUiState.value = BackupUiState.AlreadyBackedUpToday(ex.message ?: "Your data is already backed up today.")
                } else {
                    _backupUiState.value = BackupUiState.Error("Backup failed. Tap to retry.")
                }
            }
        }
    }

    fun resetBackupState() {
        _backupUiState.value = BackupUiState.Idle
    }
}
