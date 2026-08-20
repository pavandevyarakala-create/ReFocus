package com.reeltrak.app.features.dashboard.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reeltrak.app.data.local.dao.DailyReelDataDao
import com.reeltrak.app.data.local.entity.DailyReelData
import com.reeltrak.app.domain.repository.PreferencesRepository
import com.reeltrak.app.services.notifications.ReelNotificationManager
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
import java.util.Locale
import javax.inject.Inject

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val preferencesRepository: PreferencesRepository,
    private val dailyReelDataDao: DailyReelDataDao,
    overlayStateManager: OverlayStateManager,
    private val dayManager: DayManager,
    private val notificationManager: ReelNotificationManager
) : ViewModel() {

    val dailyIntent: StateFlow<Int> = dayManager.currentDayStats
        .map { it?.dailyGoal ?: -1 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), -1)

    val isStrictModeEnabled: StateFlow<Boolean?> = dayManager.currentDayStats
        .map { it?.isStrictModeOn }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    
    val isStrictModeLocked: StateFlow<Boolean> = dayManager.currentDayStats
        .map { it?.isStrictModeLocked ?: false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val isDailyLimitLocked: StateFlow<Boolean> = preferencesRepository.isDailyLimitLocked
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setStrictMode(enabled: Boolean) {
        dayManager.setStrictMode(enabled)
    }

    val defaultLimit: StateFlow<Int> = preferencesRepository.defaultDailyLimit
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), -1)

    val isLimitChangeRestricted: StateFlow<Boolean> = dayManager.currentDayStats
        .map { it?.isLimitLocked ?: false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setDailyGoal(goal: Int) {
        dayManager.setDailyGoal(goal)
    }

    fun confirmAndSetDailyGoal(goal: Int) {
        viewModelScope.launch {
            val currentStats = dayManager.currentDayStats.first()
            if (currentStats != null && !currentStats.isLimitLocked) {
                dayManager.setDailyGoal(goal)
            }
        }
    }

    init {
        // Handle initial first run date
        viewModelScope.launch {
            val currentFirstRun = preferencesRepository.firstRunDate.first()
            if (currentFirstRun == 0L) {
                preferencesRepository.setFirstRunDate(System.currentTimeMillis())
            }
        }
        
        // Midnight Reset & Unlock Logic
        viewModelScope.launch {
            dayManager.currentDateString.collect { 
                autoUnlockIfNewDay()
            }
        }
    }

    private suspend fun autoUnlockIfNewDay() {
        val todayEpoch = LocalDate.now().toEpochDay()
        val lastLockDay = preferencesRepository.lastLockEpochDay.first()
        if (todayEpoch > lastLockDay) {
            preferencesRepository.setDailyLimitLocked(false)
        }
    }

    val liveSessionState: StateFlow<OverlayState> = overlayStateManager.state

    private val currentDayFlow = dayManager.currentDateString

    val reelsScrolledToday: StateFlow<Int> = dayManager.currentDayStats
        .map { it?.totalReelsCount ?: 0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val allDailyData = dailyReelDataDao.getAllDailyDataFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val totalLifetimeReels: StateFlow<Long> = combine(allDailyData, liveSessionState) { dataList, live ->
        val historical = dataList.sumOf { it.totalReelsCount }.toLong()
        if (live.sessionState == SessionState.ACTIVE || live.sessionState == SessionState.ENTERING_REELS) {
            historical + live.currentSessionReels
        } else {
            historical
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val avgReelsPerDay: StateFlow<Int> = combine(totalLifetimeReels, allDailyData) { total, dataList ->
        val totalDays = dataList.size.coerceAtLeast(1).toDouble()
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
        val dateString: String = "",
        val dateEpochDay: Long = 0L, // Backwards compatibility for UI
        val percentage: Float = -1f, // -1f means no data or goal=0
        val isGraceDay: Boolean = false,
        val isSkullDay: Boolean = false
    )

    data class StreakStats(
        val currentStreak: Int = 0,
        val longestStreak: Int = 0,
        val totalSuccessfulDays: Int = 0,
        val totalSkullDays: Int = 0,
        val successRate: Float = 0f,
        val disciplineLevel: String = "Seedling",
        val progressToNextLevel: Float = 0f,
        val badge7UnlockTime: Long = 0L,
        val badge30UnlockTime: Long = 0L,
        val badge50UnlockTime: Long = 0L,
        val badge100UnlockTime: Long = 0L,
        val badge365UnlockTime: Long = 0L,
        val highestReelsInDay: Int = 0,
        val lowestReelsInDay: Int = 0,
        val weeklyTotalReels: Int = 0,
        val last7DaysAvg: Int = 0,
        val totalDaysTracked: Int = 0,
        val limitsFollowed: Int = 0,
        val limitsCrossed: Int = 0,
        val weeklyTrend: String = "Stable"
    )

    private val dayLabelFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())

    val habitTrackerData: StateFlow<List<DailyBlocks>> = combine(allDailyData, currentDayFlow) { dataList, todayStr ->
        val dataMap = dataList.associateBy { it.dateString }
        val result = ArrayList<DailyBlocks>(91)
        val todayEpoch = LocalDate.now().toEpochDay()
        val startDay = todayEpoch - 90
        
        for (i in 0..90) {
            val targetEpoch = startDay + i
            val date = LocalDate.ofEpochDay(targetEpoch)
            val dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
            val label = date.format(dayLabelFormatter)
            
            val stats = dataMap[dateStr]
            
            val isSkullDay = stats != null && stats.dailyGoal > 0 && stats.totalReelsCount > (stats.dailyGoal + 30)
            val isGraceDay = stats != null && stats.dailyGoal > 0 && stats.totalReelsCount > stats.dailyGoal && !isSkullDay

            val progress = if (stats != null && stats.dailyGoal > 0) {
                stats.totalReelsCount.toFloat() / stats.dailyGoal
            } else {
                -1f
            }
            
            val blocks = stats?.sessions?.map { session ->
                TimeBlock(0f, 0f, session.startTimeStamp, session.endTimeStamp, 
                          ((session.endTimeStamp - session.startTimeStamp)/60000).toInt(), 
                          session.reelsScrolled, session.appName)
            } ?: emptyList()

            result.add(DailyBlocks(
                dayLabel = label,
                blocks = blocks, 
                totalReelsScrolled = stats?.totalReelsCount ?: 0,
                dailyGoal = stats?.dailyGoal ?: 0,
                dateString = dateStr,
                dateEpochDay = targetEpoch,
                percentage = progress,
                isGraceDay = isGraceDay,
                isSkullDay = isSkullDay
            ))
        }
        result
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val streakStats: StateFlow<StreakStats> = combine(
        habitTrackerData,
        preferencesRepository.badge7UnlockTime,
        preferencesRepository.badge30UnlockTime,
        preferencesRepository.badge50UnlockTime,
        preferencesRepository.badge100UnlockTime,
        preferencesRepository.badge365UnlockTime
    ) { flows ->
        val data = flows[0] as List<DailyBlocks>
        val b7 = flows[1] as Long
        val b30 = flows[2] as Long
        val b50 = flows[3] as Long
        val b100 = flows[4] as Long
        val b365 = flows[5] as Long
        
        val stats = calculateStreakStats(data)
        checkAndPersistBadges(stats.longestStreak)
        stats.copy(
            badge7UnlockTime = b7,
            badge30UnlockTime = b30,
            badge50UnlockTime = b50,
            badge100UnlockTime = b100,
            badge365UnlockTime = b365
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StreakStats())

    private fun checkAndPersistBadges(longestStreak: Int) {
        viewModelScope.launch {
            val milestones = listOf(7, 30, 50, 100, 365)
            milestones.forEach { milestone ->
                if (longestStreak >= milestone) {
                    val currentUnlockTime = when (milestone) {
                        7 -> preferencesRepository.badge7UnlockTime.first()
                        30 -> preferencesRepository.badge30UnlockTime.first()
                        50 -> preferencesRepository.badge50UnlockTime.first()
                        100 -> preferencesRepository.badge100UnlockTime.first()
                        365 -> preferencesRepository.badge365UnlockTime.first()
                        else -> 0L
                    }
                    if (currentUnlockTime == 0L) {
                        preferencesRepository.setBadgeUnlockTime(milestone, System.currentTimeMillis())
                        notificationManager.showStreakMilestoneNotification(milestone)
                    }
                }
            }
        }
    }

    val pendingCelebrationBadge: StateFlow<Int?> = combine(
        preferencesRepository.badge7UnlockTime,
        preferencesRepository.badge30UnlockTime,
        preferencesRepository.badge50UnlockTime,
        preferencesRepository.badge100UnlockTime,
        preferencesRepository.badge365UnlockTime,
        preferencesRepository.badge7DialogShown,
        preferencesRepository.badge30DialogShown,
        preferencesRepository.badge50DialogShown,
        preferencesRepository.badge100DialogShown,
        preferencesRepository.badge365DialogShown
    ) { flows ->
        val unlock7 = flows[0] as Long
        val unlock30 = flows[1] as Long
        val unlock50 = flows[2] as Long
        val unlock100 = flows[3] as Long
        val unlock365 = flows[4] as Long
        val shown7 = flows[5] as Boolean
        val shown30 = flows[6] as Boolean
        val shown50 = flows[7] as Boolean
        val shown100 = flows[8] as Boolean
        val shown365 = flows[9] as Boolean

        when {
            unlock365 > 0L && !shown365 -> 365
            unlock100 > 0L && !shown100 -> 100
            unlock50 > 0L && !shown50 -> 50
            unlock30 > 0L && !shown30 -> 30
            unlock7 > 0L && !shown7 -> 7
            else -> null
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun dismissCelebration(milestone: Int) {
        viewModelScope.launch {
            preferencesRepository.setBadgeDialogShown(milestone, true)
        }
    }

    private fun calculateStreakStats(data: List<DailyBlocks>): StreakStats {
        if (data.isEmpty()) return StreakStats()

        var currentStreak = 0
        var longestStreak = 0
        var successfulDays = 0
        var skullDays = 0
        var activeDays = 0
        
        var highestReels = 0
        var lowestReels = Int.MAX_VALUE
        var weeklyTotal = 0
        var limitsFollowed = 0
        var limitsCrossed = 0

        val sortedData = data.sortedBy { it.dateString }
        val last7Days = sortedData.takeLast(7)
        
        sortedData.forEach { day ->
            if (day.totalReelsScrolled > 0 || day.dailyGoal > 0) {
                activeDays++
                
                if (day.totalReelsScrolled > highestReels) highestReels = day.totalReelsScrolled
                if (day.totalReelsScrolled > 0 && day.totalReelsScrolled < lowestReels) lowestReels = day.totalReelsScrolled
                
                if (day.dailyGoal > 0) {
                    if (day.totalReelsScrolled <= day.dailyGoal + 30) {
                        limitsFollowed++
                        successfulDays++
                        currentStreak++
                        if (currentStreak > longestStreak) {
                            longestStreak = currentStreak
                        }
                    } else {
                        limitsCrossed++
                        skullDays++
                        currentStreak = 0
                    }
                }
            }
        }
        
        last7Days.forEach { weeklyTotal += it.totalReelsScrolled }
        val last7Avg = if (last7Days.isNotEmpty()) weeklyTotal / 7 else 0

        val firstHalfAvg = last7Days.take(3).map { it.totalReelsScrolled }.average()
        val secondHalfAvg = last7Days.takeLast(3).map { it.totalReelsScrolled }.average()
        val trend = when {
            secondHalfAvg > firstHalfAvg * 1.1 -> "Increasing"
            secondHalfAvg < firstHalfAvg * 0.9 -> "Decreasing"
            else -> "Stable"
        }

        val successRate = if (activeDays > 0) successfulDays.toFloat() / activeDays else 0f
        
        val (level, progress) = when {
            longestStreak >= 365 -> "Legend" to 1f
            longestStreak >= 100 -> "Master" to (longestStreak - 100) / 265f
            longestStreak >= 50 -> "Disciplined" to (longestStreak - 50) / 50f
            longestStreak >= 30 -> "Consistent" to (longestStreak - 30) / 20f
            longestStreak >= 7 -> "Focused" to (longestStreak - 7) / 23f
            else -> "Seedling" to longestStreak / 7f
        }

        return StreakStats(
            currentStreak = currentStreak,
            longestStreak = longestStreak,
            totalSuccessfulDays = successfulDays,
            totalSkullDays = skullDays,
            successRate = successRate,
            disciplineLevel = level,
            progressToNextLevel = progress,
            highestReelsInDay = highestReels,
            lowestReelsInDay = if (lowestReels == Int.MAX_VALUE) 0 else lowestReels,
            weeklyTotalReels = weeklyTotal,
            last7DaysAvg = last7Avg,
            totalDaysTracked = activeDays,
            limitsFollowed = limitsFollowed,
            limitsCrossed = limitsCrossed,
            weeklyTrend = trend
        )
    }

    val weeklyActivityData: StateFlow<List<DailyBlocks>> = habitTrackerData.map { list ->
        if (list.size > 7) list.takeLast(7) else list
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val goalCalendarData: StateFlow<List<Int>> = habitTrackerData.map { list ->
        list.takeLast(84).map { day ->
            val scrolled = day.totalReelsScrolled
            when {
                scrolled == 0 -> 0
                scrolled < 20 -> 1
                scrolled < 50 -> 2
                else -> 3
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), List(84) { 0 })
}
