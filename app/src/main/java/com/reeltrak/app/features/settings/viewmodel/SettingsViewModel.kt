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

import com.reeltrak.app.core.sync.SyncManager
import com.reeltrak.app.services.tracking.DayManager
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import java.time.LocalDate

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferencesRepository: PreferencesRepository,
    private val dayManager: DayManager,
    private val syncManager: SyncManager
) : ViewModel() {

    val isStrictModeEnabled: StateFlow<Boolean?> = dayManager.currentDayStats
        .map { it?.isStrictModeOn }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val isStrictModeLocked: StateFlow<Boolean> = dayManager.currentDayStats
        .map { it?.isStrictModeLocked ?: false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val dailyIntent: StateFlow<Int> = preferencesRepository.dailyIntent
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun setStrictMode(enabled: Boolean) {
        dayManager.setStrictMode(enabled)
    }

    fun setDailyIntent(intent: Int) {
        viewModelScope.launch {
            preferencesRepository.setDailyIntent(intent)
        }
    }

    fun restoreBackup() {
        viewModelScope.launch {
            syncManager.restoreFromCloud()
        }
    }
}
