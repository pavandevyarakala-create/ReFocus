package com.reeltrak.app.features.auth

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.reeltrak.app.core.auth.GoogleAuthManager
import com.reeltrak.app.core.auth.UserAuthData
import com.reeltrak.app.core.sync.SyncManager
import com.reeltrak.app.domain.repository.PreferencesRepository
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import javax.inject.Inject

sealed class LoginState {
    object Idle : LoginState()
    object Loading : LoginState()
    data class Success(val userData: UserAuthData) : LoginState()
    data class Error(val message: String) : LoginState()
}

@HiltViewModel
class LoginViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val googleAuthManager: GoogleAuthManager,
    private val preferencesRepository: PreferencesRepository,
    private val syncManager: SyncManager,
    private val database: com.reeltrak.app.data.local.database.ReelTrakDatabase
) : ViewModel() {

    private val _uiState = MutableStateFlow<LoginState>(LoginState.Idle)
    val uiState: StateFlow<LoginState> = _uiState.asStateFlow()

    private val _showLogoutDialog = MutableStateFlow(false)
    val showLogoutDialog: StateFlow<Boolean> = _showLogoutDialog.asStateFlow()

    private val _showOfflineWarning = MutableStateFlow(false)
    val showOfflineWarning: StateFlow<Boolean> = _showOfflineWarning.asStateFlow()

    private val _logoutCountdown = MutableStateFlow(15)
    val logoutCountdown: StateFlow<Int> = _logoutCountdown.asStateFlow()

    private var countdownJob: Job? = null

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    init {
        viewModelScope.launch {
            preferencesRepository.authSession.collect { userData ->
                if (userData != null) {
                    if (Firebase.auth.currentUser != null) {
                        _uiState.value = LoginState.Success(userData)
                    } else {
                        // Ghost session: local data exists but Firebase is signed out.
                        Log.w("AuthCrashDebug", "Ghost session detected. Firebase is not authenticated.")
                        preferencesRepository.clearAllPreferences()
                        _uiState.value = LoginState.Idle
                    }
                } else if (_uiState.value is LoginState.Success) {
                    _uiState.value = LoginState.Idle
                }
            }
        }
    }

    fun signIn() {
        viewModelScope.launch {
            try {
                _uiState.value = LoginState.Loading
                googleAuthManager.signIn().fold(
                    onSuccess = { userData ->
                        preferencesRepository.saveAuthSession(userData)
                        
                        val hasMigrated = preferencesRepository.hasMigratedToServer.first()
                        if (!hasMigrated) {
                            syncManager.performLegacyMigration()
                        } else {
                            syncManager.restoreFromCloud()
                        }

                        _uiState.value = LoginState.Success(userData)
                    },
                    onFailure = { throwable ->
                        val errorMsg = throwable.message ?: "Sign in failed"
                        Log.e("AuthCrashDebug", "LoginViewModel: Sign in failed", throwable)
                        _uiState.value = LoginState.Error(errorMsg)
                    }
                )
            } catch (e: Exception) {
                Log.e("AuthCrashDebug", "LoginViewModel: Unexpected exception during sign in", e)
                _uiState.value = LoginState.Error(e.message ?: "Unexpected error occurred")
            }
        }
    }

    fun initiateLogout() {
        if (!isNetworkAvailable()) {
            _showOfflineWarning.value = true
            return
        }

        _showLogoutDialog.value = true
        _logoutCountdown.value = 10
        
        // NonCancellable backup trigger - fire and forget
        GlobalScope.launch(Dispatchers.IO + NonCancellable) {
            syncManager.backupToday()
        }

        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            for (i in 10 downTo 1) {
                _logoutCountdown.value = i
                delay(1000)
            }
            if (_logoutCountdown.value > 0) _logoutCountdown.value = 0
        }
    }

    fun cancelLogout() {
        _showLogoutDialog.value = false
        _showOfflineWarning.value = false
        countdownJob?.cancel()
    }

    fun signOut() {
        _showLogoutDialog.value = false
        countdownJob?.cancel()
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // SECURE WIPE: Clear historical data first, preserving TODAY's tracking.
                val todayStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
                database.dailyReelDataDao.clearHistoricalDataExcept(todayStr)
                preferencesRepository.clearAllPreferences()
                
                // FINAL SIGN OUT: Only sign out after wiping historical data
                googleAuthManager.signOut().fold(
                    onSuccess = {
                        _uiState.value = LoginState.Idle
                    },
                    onFailure = { throwable ->
                        val errorMsg = throwable.message ?: "Sign out failed"
                        Log.e("AuthCrashDebug", "LoginViewModel: Sign out failed", throwable)
                        _uiState.value = LoginState.Error(errorMsg)
                    }
                )
            } catch (e: Exception) {
                Log.e("AuthCrashDebug", "LoginViewModel: Unexpected exception during sign out", e)
                _uiState.value = LoginState.Error(e.message ?: "Unexpected error occurred")
            }
        }
    }

    fun resetState() {
        _uiState.value = LoginState.Idle
    }
}
