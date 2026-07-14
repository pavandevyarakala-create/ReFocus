package com.reeltrak.app.features.dashboard.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reeltrak.app.data.local.dao.DailyStatisticsDao
import com.reeltrak.app.data.local.dao.GlobalStatisticsDao
import com.reeltrak.app.data.local.dao.SessionDao
import com.reeltrak.app.data.local.entity.SessionEntity
import com.reeltrak.app.domain.repository.PreferencesRepository
import com.reeltrak.app.services.overlay.OverlayState
import com.reeltrak.app.services.overlay.OverlayStateManager
import com.reeltrak.app.services.tracking.DayManager
import com.reeltrak.app.services.tracking.SessionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val preferencesRepository: PreferencesRepository,
    sessionDao: SessionDao,
    private val dailyStatisticsDao: DailyStatisticsDao,
    private val globalStatisticsDao: GlobalStatisticsDao,
    overlayStateManager: OverlayStateManager,
    private val dayManager: DayManager
) : ViewModel() {

    val dailyIntent: StateFlow<Int> = dayManager.currentDayStats
        .map { it?.dailyGoal ?: -1 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), -1)

    val isStrictModeEnabled: StateFlow<Boolean> = preferencesRepository.isStrictModeEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val isStrictModeLocked: StateFlow<Boolean> = preferencesRepository.isStrictModeLocked
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val isDailyLimitLocked: StateFlow<Boolean> = preferencesRepository.isDailyLimitLocked
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setStrictMode(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) {
                preferencesRepository.setStrictMode(true)
                preferencesRepository.setStrictModeLocked(true)
                preferencesRepository.setLastLockEpochDay(dayManager.currentEpochDay.value)
            } else {
                // If it's already locked, we ignore attempts to turn it off
                // (The UI should also be disabled)
                val isLocked = preferencesRepository.isStrictModeLocked.first()
                if (!isLocked) {
                    preferencesRepository.setStrictMode(false)
                }
            }
        }
    }

    fun setDailyGoal(goal: Int) {
        dayManager.setDailyGoal(goal)
    }

    fun confirmAndSetDailyGoal(goal: Int) {
        viewModelScope.launch {
            dayManager.setDailyGoal(goal)
            preferencesRepository.setDailyLimitLocked(true)
            preferencesRepository.setLastLockEpochDay(dayManager.currentEpochDay.value)
        }
    }

    init {
        // Handle initial first run date
        viewModelScope.launch {
            val currentFirstRun = preferencesRepository.firstRunDate.first()
            if (currentFirstRun == 0L) {
                val earliest = sessionDao.getEarliestSessionTime().first() ?: System.currentTimeMillis()
                preferencesRepository.setFirstRunDate(earliest)
            }
        }
        
        // Midnight Reset & Unlock Logic
        viewModelScope.launch {
            dayManager.currentEpochDay.collect { today ->
                autoUnlockIfNewDay(today)
            }
        }
    }

    private suspend fun autoUnlockIfNewDay(today: Long) {
        val lastLockDay = preferencesRepository.lastLockEpochDay.first()
        if (today > lastLockDay) {
            // New day detected: Clear all locks and disable Strict Mode automatically
            preferencesRepository.setStrictMode(false)
            preferencesRepository.setStrictModeLocked(false)
            preferencesRepository.setDailyLimitLocked(false)
            // Note: We don't necessarily update lastLockEpochDay here, 
            // it will be updated when the next lock happens.
        }
    }

    // 1. Live State Flow
    val liveSessionState: StateFlow<OverlayState> = overlayStateManager.state

    // Combined Source of Truth for "Today" provided centrally by DayManager
    private val currentDayFlow = dayManager.currentEpochDay

    val reelsScrolledToday: StateFlow<Int> = dayManager.currentDayStats
        .map { it?.totalReelsScrolled ?: 0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // Historical Database State
    private val allSessions: Flow<List<SessionEntity>> = sessionDao.getAllSessions()
    private val globalStats: Flow<com.reeltrak.app.data.local.entity.GlobalStatisticsEntity?> = globalStatisticsDao.getGlobalStatistics()

    // Combined Flow: Historical Sessions + Active Live Session
    private val blendedSessions = combine(allSessions, liveSessionState) { historical, live ->
        val list = historical.toMutableList()
        // If there's an active session, mock a SessionEntity so it appears in the graphs instantly
        if ((live.sessionState == SessionState.ACTIVE || live.sessionState == SessionState.ENTERING_REELS || live.sessionState == SessionState.EXITING) && live.sessionStartTime > 0 && live.currentSessionReels > 0) {
            val liveSessionEntity = SessionEntity(
                id = -1, // Mock ID
                appName = live.currentApp ?: "Unknown",
                startTimeStamp = live.sessionStartTime,
                endTimeStamp = System.currentTimeMillis(),
                reelsScrolled = live.currentSessionReels
            )
            list.add(0, liveSessionEntity)
        }
        list
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Calculated Live Analytics (Combining Historical + Active Session efficiently)
    val totalLifetimeReels: StateFlow<Long> = combine(globalStats, liveSessionState) { global, live ->
        val historical = global?.lifetimeReels ?: 0L
        if (live.sessionState == SessionState.ACTIVE || live.sessionState == SessionState.ENTERING_REELS) {
            historical + live.currentSessionReels
        } else {
            historical
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    // Average Reels/Day: (Total Reels including live session) / (Completed Days + 1 for today)
    // Rounded to nearest whole number
    val avgReelsPerDay: StateFlow<Int> = combine(totalLifetimeReels, globalStats) { total, global ->
        val completedDays = global?.completedDays ?: 0
        // We include today in the average calculation
        val totalDays = (completedDays + 1).toDouble()
        Math.round(total.toDouble() / totalDays).toInt()
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    @androidx.compose.runtime.Immutable
    data class TimeBlock(
        val startFraction: Float, 
        val endFraction: Float,
        val startTimeStamp: Long,
        val endTimeStamp: Long,
        val durationMinutes: Int,
        val reelsCount: Int,
        val appName: String
    )
    @androidx.compose.runtime.Immutable
    data class DailyBlocks(
        val dayLabel: String, 
        val blocks: List<TimeBlock>,
        val totalReelsScrolled: Int = 0,
        val dailyGoal: Int = 0,
        val dateEpochDay: Long = 0,
        val colorLevel: Int = 0 // ADDED
    )

    // Habit Tracker Data (Last 90 days for statistics and current month for UI)
    private val dayLabelFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())

    val habitTrackerData: StateFlow<List<DailyBlocks>> = currentDayFlow.flatMapLatest { todayEpoch ->
        val startDay = todayEpoch - 90
        dailyStatisticsDao.getStatisticsRangeFlow(startDay, todayEpoch)
            .map { statsList ->
                val statsMap = statsList.associateBy { it.dateEpochDay }
                val result = ArrayList<DailyBlocks>(91)
                
                for (i in 0..90) {
                    val targetEpoch = startDay + i
                    val date = LocalDate.ofEpochDay(targetEpoch)
                    val label = date.format(dayLabelFormatter)
                    
                    val stats = statsMap[targetEpoch]
                    
                    // Live Calculation for "Today" ONLY.
                    // Historical days use the finalized colorLevel from the DB.
                    val displayLevel = if (targetEpoch == todayEpoch) {
                        if (stats == null || stats.dailyGoal <= 0) 0
                        else {
                            val progress = stats.totalReelsScrolled.toFloat() / stats.dailyGoal.coerceAtLeast(1)
                            when {
                                progress == 0f -> 0
                                progress <= 0.50f -> 1
                                progress <= 0.85f -> 2
                                progress <= 1.00f -> 3
                                progress <= 1.17f -> 4
                                else -> 5
                            }
                        }
                    } else {
                        stats?.colorLevel ?: 0
                    }

                    result.add(DailyBlocks(
                        dayLabel = label,
                        blocks = emptyList(), 
                        totalReelsScrolled = stats?.totalReelsScrolled ?: 0,
                        dailyGoal = stats?.dailyGoal ?: 0,
                        dateEpochDay = targetEpoch,
                        colorLevel = displayLevel
                    ))
                }
                result
            }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Weekly Activity Graph (Dynamic 7-Day Window)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val weeklyActivityData: StateFlow<List<DailyBlocks>> = habitTrackerData.map { list ->
        if (list.size > 7) list.takeLast(7) else list
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Goal Calendar (Last 84 Days Intensity: 0 to 3)
    val goalCalendarData: StateFlow<List<Int>> = blendedSessions.map { sessions ->
        val historyMap = mutableMapOf<Long, Int>() // epochDay -> totalScrolled
        sessions.forEach { session ->
            val epochDay = session.startTimeStamp / (1000 * 60 * 60 * 24)
            historyMap[epochDay] = (historyMap[epochDay] ?: 0) + session.reelsScrolled
        }

        val todayEpoch = System.currentTimeMillis() / (1000 * 60 * 60 * 24)
        val result = ArrayList<Int>(84)
        for (i in 83 downTo 0) {
            val targetDay = todayEpoch - i
            val scrolled = historyMap[targetDay] ?: 0
            val intensity = when {
                scrolled == 0 -> 0
                scrolled < 20 -> 1
                scrolled < 50 -> 2
                else -> 3
            }
            result.add(intensity)
        }
        result
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), List(84) { 0 })
}
