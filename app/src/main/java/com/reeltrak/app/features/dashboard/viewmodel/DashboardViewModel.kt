package com.reeltrak.app.features.dashboard.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reeltrak.app.data.local.dao.DailyReelDataDao
import com.reeltrak.app.data.local.entity.DailyReelData
import com.reeltrak.app.domain.models.StrictModeDailyState
import com.reeltrak.app.domain.repository.PreferencesRepository
import com.reeltrak.app.services.notifications.ReelNotificationManager
import com.reeltrak.app.services.overlay.OverlayState
import com.reeltrak.app.services.overlay.OverlayStateManager
import com.reeltrak.app.services.tracking.DayManager
import com.reeltrak.app.services.tracking.SessionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import com.reeltrak.app.data.security.authorization.AppAuthorizationManager
import com.reeltrak.app.data.security.authorization.OperationalMode
import java.util.Locale
import javax.inject.Inject

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val preferencesRepository: PreferencesRepository,
    private val dailyReelDataDao: DailyReelDataDao,
    overlayStateManager: OverlayStateManager,
    private val dayManager: DayManager,
    private val notificationManager: ReelNotificationManager,
    private val subscriptionManager: com.reeltrak.app.domain.managers.SubscriptionManager,
    private val rewardedAdManager: com.reeltrak.app.core.ads.RewardedAdManager,
    private val appAuthorizationManager: AppAuthorizationManager
) : ViewModel() {

    val isAppUnlocked: StateFlow<Boolean> = subscriptionManager.isAppUnlocked

    val isAdReady: StateFlow<Boolean> = rewardedAdManager.isAdReady
    val isAdLoading: StateFlow<Boolean> = rewardedAdManager.isLoading

    fun preloadAd() {
        rewardedAdManager.preloadAd()
    }

    fun showRewardedAd(
        activity: android.app.Activity,
        onRewardEarned: () -> Unit,
        onDismissedWithoutReward: () -> Unit = {},
        onError: (String) -> Unit = {}
    ): Job {
        return rewardedAdManager.showRewardedAd(
            activity = activity,
            onRewardEarned = onRewardEarned,
            onDismissedWithoutReward = onDismissedWithoutReward,
            onError = onError
        )
    }

    val dailyIntent: StateFlow<Int> = dayManager.currentDayStats
        .map { it?.dailyGoal ?: -1 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), -1)

    val isStrictModeEnabled: StateFlow<Boolean?> = combine(
        dayManager.currentDayStats,
        subscriptionManager.hasActiveEntitlement
    ) { stats, isEntitled ->
        if (!isEntitled) false else stats?.isStrictModeOn
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    
    val isStrictModeLocked: StateFlow<Boolean> = combine(
        dayManager.currentDayStats,
        subscriptionManager.hasActiveEntitlement
    ) { stats, isEntitled ->
        if (isEntitled) stats?.isStrictModeLocked ?: false else false
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val strictModeDailyState: StateFlow<StrictModeDailyState> = combine(
        dayManager.strictModeDailyState,
        subscriptionManager.hasActiveEntitlement
    ) { dailyState, isEntitled ->
        if (isEntitled) dailyState else StrictModeDailyState.ACTIVE_INTERACTIVE
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StrictModeDailyState.ACTIVE_INTERACTIVE)

    val isDailyLimitLocked: StateFlow<Boolean> = preferencesRepository.isDailyLimitLocked
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun disableStrictModeForToday() {
        if (!subscriptionManager.hasActiveEntitlementNow()) return
        dayManager.disableStrictModeForToday()
    }

    fun turnOffStrictModeWithAd(
        activity: android.app.Activity,
        onSuccess: () -> Unit,
        onDismissedWithoutReward: () -> Unit = {},
        onError: (String) -> Unit = {}
    ): Job {
        if (!subscriptionManager.hasActiveEntitlementNow()) {
            onError("Active Trial or Premium subscription required.")
            return viewModelScope.launch { /* no-op */ }
        }

        val hasRewarded = java.util.concurrent.atomic.AtomicBoolean(false)

        return rewardedAdManager.showRewardedAd(
            activity = activity,
            onRewardEarned = {
                if (hasRewarded.compareAndSet(false, true)) {
                    disableStrictModeForToday()
                    onSuccess()
                }
            },
            onDismissedWithoutReward = onDismissedWithoutReward,
            onError = onError
        )
    }

    fun reactivateStrictModeWithAd() {
        if (!subscriptionManager.hasActiveEntitlementNow()) return
        dayManager.reactivateStrictModeWithAd()
    }

    fun turnOnStrictModeWithAd(
        activity: android.app.Activity,
        onSuccess: () -> Unit,
        onDismissedWithoutReward: () -> Unit = {},
        onError: (String) -> Unit = {}
    ): Job {
        if (!subscriptionManager.hasActiveEntitlementNow()) {
            onError("Active Trial or Premium subscription required.")
            return viewModelScope.launch { /* no-op */ }
        }

        val hasRewarded = java.util.concurrent.atomic.AtomicBoolean(false)

        return rewardedAdManager.showRewardedAd(
            activity = activity,
            onRewardEarned = {
                if (hasRewarded.compareAndSet(false, true)) {
                    reactivateStrictModeWithAd()
                    onSuccess()
                }
            },
            onDismissedWithoutReward = onDismissedWithoutReward,
            onError = onError
        )
    }

    fun setStrictMode(enabled: Boolean) {
        if (!subscriptionManager.hasActiveEntitlementNow()) return
        dayManager.setStrictMode(enabled)
    }

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

    fun getOperationalMode(): OperationalMode = appAuthorizationManager.getOperationalMode()

    val defaultLimit: StateFlow<Int> = preferencesRepository.defaultDailyLimit
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), -1)

    val isLimitChangeRestricted: StateFlow<Boolean> = dayManager.currentDayStats
        .map { it?.isLimitLocked ?: false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setDailyGoal(goal: Int) {
        val mode = appAuthorizationManager.getOperationalMode()
        if (!mode.isEntitled) {
            android.util.Log.w("DashboardViewModel", "setDailyGoal rejected: Daily Limit is Trial/Premium-only. Mode: $mode")
            return
        }
        dayManager.setDailyGoal(goal)
    }

    fun confirmAndSetDailyGoal(goal: Int) {
        val mode = appAuthorizationManager.getOperationalMode()
        if (!mode.isEntitled) {
            android.util.Log.w("DashboardViewModel", "confirmAndSetDailyGoal rejected: Daily Limit is Trial/Premium-only. Mode: $mode")
            return
        }
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

    private val allDailyData = subscriptionManager.hasActiveEntitlement.flatMapLatest { hasEntitlement ->
        if (hasEntitlement) {
            dailyReelDataDao.getAllDailyDataFlow()
        } else {
            flowOf(emptyList())
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val totalLifetimeReels: StateFlow<Long> = combine(
        allDailyData,
        currentDayFlow,
        dayManager.currentDayStats,
        liveSessionState,
        subscriptionManager.hasActiveEntitlement
    ) { dataList, todayStr, todayStats, live, isEntitled ->
        if (isEntitled) {
            calculateLifetimeReels(dataList, todayStr, todayStats, live)
        } else {
            0L
        }
    }.distinctUntilChanged()
    .flowOn(Dispatchers.Default)
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val avgReelsPerDay: StateFlow<Int> = combine(
        totalLifetimeReels,
        allDailyData,
        currentDayFlow,
        dayManager.currentDayStats,
        subscriptionManager.hasActiveEntitlement
    ) { total, dataList, todayStr, todayStats, isEntitled ->
        if (isEntitled) {
            calculateAvgReelsPerDay(total, dataList, todayStr, todayStats)
        } else {
            0
        }
    }.distinctUntilChanged()
    .flowOn(Dispatchers.Default)
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

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
        val isDriftedDay: Boolean = false,
        val isUnrestrictedDay: Boolean = false,
        val isSkullDay: Boolean = isDriftedDay,
        val isToday: Boolean = false
    )

    data class StreakStats(
        val currentStreak: Int = 0,
        val longestStreak: Int = 0,
        val totalSuccessfulDays: Int = 0,
        val totalDriftedDays: Int = 0,
        val totalSkullDays: Int = totalDriftedDays,
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

    val habitTrackerData: StateFlow<List<DailyBlocks>> = combine(
        allDailyData,
        currentDayFlow,
        dayManager.currentDayStats,
        subscriptionManager.hasActiveEntitlement
    ) { dataList, todayStr, todayStats, isEntitled ->
        if (isEntitled) {
            generateHabitTrackerData(dataList, todayStr, todayStats)
        } else {
            emptyList()
        }
    }.distinctUntilChanged()
    .flowOn(Dispatchers.Default)
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _calendarMonth = MutableStateFlow(YearMonth.now())
    val calendarMonth: StateFlow<YearMonth> = _calendarMonth.asStateFlow()

    fun setCalendarMonth(yearMonth: YearMonth) {
        _calendarMonth.value = yearMonth
    }

    val calendarMonthData: StateFlow<List<DailyBlocks>> = combine(
        _calendarMonth,
        subscriptionManager.hasActiveEntitlement
    ) { month, isEntitled ->
        month to isEntitled
    }.flatMapLatest { (month, isEntitled) ->
        if (isEntitled) {
            val startDate = month.atDay(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
            val endDate = month.atEndOfMonth().format(DateTimeFormatter.ISO_LOCAL_DATE)
            combine(
                dailyReelDataDao.getDailyDataRangeFlow(startDate, endDate),
                currentDayFlow,
                dayManager.currentDayStats
            ) { dataList, todayStr, todayStats ->
                generateMonthCalendarData(month, dataList, todayStr, todayStats)
            }
        } else {
            flowOf(emptyList())
        }
    }.distinctUntilChanged()
    .flowOn(Dispatchers.Default)
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val hasAnyHistory: StateFlow<Boolean> = combine(
        allDailyData,
        dayManager.currentDayStats
    ) { list, today ->
        list.any { it.totalReelsCount > 0 || it.dailyGoal > 0 } ||
        ((today?.totalReelsCount ?: 0) > 0 || (today?.dailyGoal ?: 0) > 0)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val streakStats: StateFlow<StreakStats> = combine(
        habitTrackerData,
        preferencesRepository.badge7UnlockTime,
        preferencesRepository.badge30UnlockTime,
        preferencesRepository.badge50UnlockTime,
        preferencesRepository.badge100UnlockTime,
        preferencesRepository.badge365UnlockTime,
        subscriptionManager.hasActiveEntitlement
    ) { flows ->
        val isEntitled = flows[6] as Boolean
        if (!isEntitled) {
            StreakStats()
        } else {
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
        }
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
        preferencesRepository.badge365DialogShown,
        subscriptionManager.hasActiveEntitlement
    ) { flows ->
        val isEntitled = flows[10] as Boolean
        if (!isEntitled) return@combine null

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

    companion object {
        fun formatDayLabel(date: LocalDate): String {
            return date.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
        }

        fun generateHabitTrackerData(
            dataList: List<DailyReelData>,
            todayStr: String,
            todayStats: DailyReelData?
        ): List<DailyBlocks> {
            val dataMap = dataList.associateBy { it.dateString }
            val result = ArrayList<DailyBlocks>(91)
            val todayDate = try {
                LocalDate.parse(todayStr, DateTimeFormatter.ISO_LOCAL_DATE)
            } catch (e: Exception) {
                LocalDate.now()
            }
            val todayEpoch = todayDate.toEpochDay()
            val startDay = todayEpoch - 90
            
            for (i in 0..90) {
                val targetEpoch = startDay + i
                val date = LocalDate.ofEpochDay(targetEpoch)
                val dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
                val label = formatDayLabel(date)
                
                val stats = dataMap[dateStr]
                val isToday = (dateStr == todayStr)
                
                // Coalesce with today's live in-memory stats for seamless real-time consistency
                val count = if (isToday) {
                    val roomCount = stats?.totalReelsCount ?: 0
                    val memCount = if (todayStats?.dateString == todayStr) todayStats.totalReelsCount else 0
                    maxOf(roomCount, memCount)
                } else {
                    stats?.totalReelsCount ?: 0
                }
                
                val goal = if (isToday && todayStats != null && todayStats.dateString == todayStr) {
                    todayStats.dailyGoal
                } else {
                    stats?.dailyGoal ?: 0
                }

                // Deterministic Day-Status Evaluation:
                // 1. If count == 0: No activity / empty
                // 2. If count > 0 && goal <= 0: UNRESTRICTED (Reels watched with no configured limit)
                // 3. If count > 0 && goal > 0: Evaluate within-limit / grace / drifted
                val isUnrestrictedDay = (stats != null || (isToday && todayStats != null)) && count > 0 && goal <= 0
                val isDriftedDay = (stats != null || (isToday && todayStats != null)) && goal > 0 && count > (goal + 30)
                val isGraceDay = (stats != null || (isToday && todayStats != null)) && goal > 0 && count > goal && !isDriftedDay

                val progress = if ((stats != null || (isToday && todayStats != null)) && goal > 0) {
                    count.toFloat() / goal
                } else {
                    -1f
                }
                
                val rawSessions = if (isToday && todayStats != null && todayStats.dateString == todayStr && todayStats.sessions.isNotEmpty()) {
                    todayStats.sessions
                } else {
                    stats?.sessions ?: emptyList()
                }
                val blocks = rawSessions.map { session ->
                    TimeBlock(0f, 0f, session.startTimeStamp, session.endTimeStamp, 
                              ((session.endTimeStamp - session.startTimeStamp)/60000).toInt(), 
                              session.reelsScrolled, session.appName)
                }

                result.add(DailyBlocks(
                    dayLabel = label,
                    blocks = blocks, 
                    totalReelsScrolled = count,
                    dailyGoal = goal,
                    dateString = dateStr,
                    dateEpochDay = targetEpoch,
                    percentage = progress,
                    isGraceDay = isGraceDay,
                    isDriftedDay = isDriftedDay,
                    isUnrestrictedDay = isUnrestrictedDay,
                    isSkullDay = isDriftedDay,
                    isToday = isToday
                ))
            }
            return result
        }

        fun calculateStreakStats(data: List<DailyBlocks>): StreakStats {
            if (data.isEmpty()) return StreakStats()

            var currentStreak = 0
            var longestStreak = 0
            var successfulDays = 0
            var driftedDays = 0
            var activeDays = 0
            
            var highestReels = 0
            var lowestReels = Int.MAX_VALUE
            var weeklyTotal = 0
            var limitsFollowed = 0
            var limitsCrossed = 0

            val sortedData = data.filter { it.dateString.isNotBlank() }.sortedBy { it.dateString }
            val last7Days = sortedData.takeLast(7)
            
            var lastDate: LocalDate? = null

            sortedData.forEach { day ->
                val currDate = try {
                    LocalDate.parse(day.dateString)
                } catch (e: Exception) {
                    null
                }

                if (lastDate != null && currDate != null) {
                    val daysBetween = ChronoUnit.DAYS.between(lastDate, currDate)
                    if (daysBetween > 1L) {
                        // A gap in dates indicates missed day(s)
                        currentStreak = 0
                    }
                }
                if (currDate != null) {
                    lastDate = currDate
                }

                val hasActivity = day.totalReelsScrolled > 0
                val hasGoal = day.dailyGoal > 0

                if (day.isToday) {
                    if (hasActivity) {
                        activeDays++
                        if (day.totalReelsScrolled > highestReels) highestReels = day.totalReelsScrolled
                        if (day.totalReelsScrolled < lowestReels) lowestReels = day.totalReelsScrolled

                        if (hasGoal) {
                            if (day.totalReelsScrolled <= day.dailyGoal + 30) {
                                limitsFollowed++
                                successfulDays++
                                currentStreak++
                                if (currentStreak > longestStreak) {
                                    longestStreak = currentStreak
                                }
                            } else {
                                limitsCrossed++
                                driftedDays++
                                currentStreak = 0
                            }
                        }
                    }
                    // Incomplete current day with zero reels:
                    // Preserve current streak without incrementing or treating as completed day.
                } else {
                    if (hasActivity || hasGoal) {
                        activeDays++
                        if (day.totalReelsScrolled > highestReels) highestReels = day.totalReelsScrolled
                        if (day.totalReelsScrolled > 0 && day.totalReelsScrolled < lowestReels) lowestReels = day.totalReelsScrolled

                        if (hasGoal) {
                            if (day.totalReelsScrolled <= day.dailyGoal + 30) {
                                limitsFollowed++
                                successfulDays++
                                currentStreak++
                                if (currentStreak > longestStreak) {
                                    longestStreak = currentStreak
                                }
                            } else {
                                limitsCrossed++
                                driftedDays++
                                currentStreak = 0
                            }
                        }
                    } else {
                        // Inactive/missed past day (zero reels and zero goal / untracked)
                        currentStreak = 0
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
                totalDriftedDays = driftedDays,
                totalSkullDays = driftedDays,
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

        /**
         * Calculates the total lifetime reels count ensuring every reel is counted exactly once.
         *
         * Formula:
         * Lifetime = (Sum of historical days' reels) + (Today's authoritative reel count) + (Unpersisted live session delta)
         *
         * - Historical days (dateString != todayStr): immutable records in Room.
         * - Today's count: maxOf(Room count for today, in-memory DayManager count for today),
         *   which guarantees monotonic, instantaneous updates without double-counting.
         * - Live session delta: only added if an active session has reels that have not yet been reflected
         *   in today's count (i.e. live.currentSessionReels > todayCount). In normal operation this delta is 0,
         *   preventing any double-counting of today's reels.
         */
        fun calculateLifetimeReels(
            dataList: List<DailyReelData>,
            todayStr: String,
            todayStats: DailyReelData?,
            live: OverlayState
        ): Long {
            val effectiveTodayStr = if (todayStr.isNotBlank()) todayStr else (todayStats?.dateString ?: "")

            // 1. Sum strictly historical days (dateString < today, rejecting future dates)
            val historicalSum = if (effectiveTodayStr.isNotBlank()) {
                dataList
                    .filter { it.dateString.isNotBlank() && it.dateString < effectiveTodayStr }
                    .sumOf { it.totalReelsCount.coerceAtLeast(0).toLong() }
            } else {
                dataList
                    .filter { it.dateString.isNotBlank() }
                    .sumOf { it.totalReelsCount.coerceAtLeast(0).toLong() }
            }

            // 2. Authoritative count for today: coalesce Room persisted count and in-memory DayManager state
            val roomTodayCount = dataList.find { it.dateString == effectiveTodayStr }?.totalReelsCount?.coerceAtLeast(0)?.toLong() ?: 0L
            val memoryTodayCount = if (todayStats != null && (todayStats.dateString == effectiveTodayStr || effectiveTodayStr.isEmpty())) {
                todayStats.totalReelsCount.coerceAtLeast(0).toLong()
            } else {
                0L
            }
            val todayCount = maxOf(roomTodayCount, memoryTodayCount)

            // 3. Genuine unpersisted live session delta (if any)
            val unpersistedDelta = if ((live.sessionState == SessionState.ACTIVE || live.sessionState == SessionState.ENTERING_REELS) &&
                live.currentSessionReels.toLong() > todayCount) {
                live.currentSessionReels.toLong() - todayCount
            } else {
                0L
            }

            return historicalSum + todayCount + unpersistedDelta
        }

        /**
         * Calculates the average reels per day:
         * Total lifetime reels divided by total tracked days (completed historical days + 1 for today).
         */
        fun calculateAvgReelsPerDay(
            totalLifetimeReels: Long,
            dataList: List<DailyReelData>,
            todayStr: String,
            todayStats: DailyReelData?
        ): Int {
            val effectiveTodayStr = if (todayStr.isNotBlank()) todayStr else (todayStats?.dateString ?: "")

            // Count valid historical days (strictly < today, ignoring future dates)
            val historicalDaysCount = if (effectiveTodayStr.isNotBlank()) {
                dataList.count { it.dateString.isNotBlank() && it.dateString < effectiveTodayStr }
            } else {
                dataList.count { it.dateString.isNotBlank() }
            }

            // Include today as the active tracked day
            val totalDays = (historicalDaysCount + 1).coerceAtLeast(1).toDouble()

            return Math.round(totalLifetimeReels.coerceAtLeast(0L).toDouble() / totalDays).toInt()
        }

        fun generateMonthCalendarData(
            month: YearMonth,
            dataList: List<DailyReelData>,
            todayStr: String,
            todayStats: DailyReelData?
        ): List<DailyBlocks> {
            val dataMap = dataList.associateBy { it.dateString }.toMutableMap()

            // Check if today falls in this month and coalesce
            val todayDate = try {
                LocalDate.parse(todayStr, DateTimeFormatter.ISO_LOCAL_DATE)
            } catch (e: Exception) {
                LocalDate.now()
            }
            val isCurrentMonth = (todayDate.year == month.year && todayDate.month == month.month)
            if (isCurrentMonth && todayStats != null && todayStats.dateString == todayStr) {
                val existing = dataMap[todayStr]
                val merged = if (existing != null) {
                    existing.copy(
                        totalReelsCount = maxOf(existing.totalReelsCount, todayStats.totalReelsCount),
                        dailyGoal = if (todayStats.dailyGoal > 0) todayStats.dailyGoal else existing.dailyGoal,
                        sessions = if (todayStats.sessions.isNotEmpty()) todayStats.sessions else existing.sessions,
                        isSkullDay = todayStats.isSkullDay || existing.isSkullDay,
                        isStrictModeOn = todayStats.isStrictModeOn,
                        isStrictModeLocked = todayStats.isStrictModeLocked,
                        isLimitLocked = todayStats.isLimitLocked
                    )
                } else {
                    todayStats
                }
                dataMap[todayStr] = merged
            }

            return dataMap.values.map { stats ->
                val date = try {
                    LocalDate.parse(stats.dateString, DateTimeFormatter.ISO_LOCAL_DATE)
                } catch (e: Exception) {
                    LocalDate.now()
                }
                val isToday = (stats.dateString == todayStr)
                val count = stats.totalReelsCount
                val goal = stats.dailyGoal
                val label = formatDayLabel(date)

                val isUnrestrictedDay = count > 0 && goal <= 0
                val isDriftedDay = goal > 0 && count > (goal + 30)
                val isGraceDay = goal > 0 && count > goal && !isDriftedDay
                val progress = if (goal > 0) count.toFloat() / goal else -1f

                val blocks = stats.sessions.map { session ->
                    TimeBlock(
                        0f, 0f, session.startTimeStamp, session.endTimeStamp,
                        ((session.endTimeStamp - session.startTimeStamp) / 60000).toInt(),
                        session.reelsScrolled, session.appName
                    )
                }

                DailyBlocks(
                    dayLabel = label,
                    blocks = blocks,
                    totalReelsScrolled = count,
                    dailyGoal = goal,
                    dateString = stats.dateString,
                    dateEpochDay = date.toEpochDay(),
                    percentage = progress,
                    isGraceDay = isGraceDay,
                    isDriftedDay = isDriftedDay,
                    isUnrestrictedDay = isUnrestrictedDay,
                    isSkullDay = isDriftedDay,
                    isToday = isToday
                )
            }.sortedBy { it.dateEpochDay }
        }
    }
}
