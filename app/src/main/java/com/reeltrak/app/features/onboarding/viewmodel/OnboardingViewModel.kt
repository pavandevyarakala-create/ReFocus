package com.reeltrak.app.features.onboarding.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reeltrak.app.data.security.authorization.AppAuthorizationManager
import com.reeltrak.app.domain.repository.PreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val preferencesRepository: PreferencesRepository,
    private val appAuthorizationManagerLazy: dagger.Lazy<AppAuthorizationManager>? = null
) : ViewModel() {

    fun saveDailyIntent(intent: Int) {
        viewModelScope.launch {
            val authMgr = appAuthorizationManagerLazy?.get()
            if (authMgr == null || authMgr.getOperationalMode().isEntitled) {
                preferencesRepository.setDailyIntent(intent)
            }
            if (preferencesRepository.firstRunDate.first() == 0L) {
                preferencesRepository.setFirstRunDate(System.currentTimeMillis())
            }
        }
    }
}
