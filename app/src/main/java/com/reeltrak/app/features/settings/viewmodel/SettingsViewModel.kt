package com.reeltrak.app.features.settings.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reeltrak.app.domain.repository.PreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferencesRepository: PreferencesRepository
) : ViewModel() {

    val isStrictModeEnabled: StateFlow<Boolean> = preferencesRepository.isStrictModeEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val dailyIntent: StateFlow<Int> = preferencesRepository.dailyIntent
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun setStrictMode(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setStrictMode(enabled)
        }
    }

    fun setDailyIntent(intent: Int) {
        viewModelScope.launch {
            preferencesRepository.setDailyIntent(intent)
        }
    }
}
