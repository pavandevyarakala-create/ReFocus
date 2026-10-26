package com.reeltrak.app.services.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import com.reeltrak.app.data.local.dao.DailyReelDataDao
import com.reeltrak.app.data.local.dao.ReelTransitionWriteStatus
import com.reeltrak.app.data.local.entity.ReelCountAccountScope
import com.reeltrak.app.data.local.entity.DailyReelData
import com.reeltrak.app.data.local.entity.SessionData
import com.reeltrak.app.data.local.entity.strictModeDailyState
import com.reeltrak.app.domain.models.StrictModeDailyState
import com.reeltrak.app.domain.repository.PreferencesRepository
import com.reeltrak.app.services.notifications.ReelNotificationManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.os.SystemClock
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.reeltrak.app.core.sync.SyncWorker
import com.reeltrak.app.core.sync.SyncManager
import com.reeltrak.app.data.security.authorization.AppAuthorizationManager
import com.reeltrak.app.data.security.authorization.AuthorizationToken
import com.reeltrak.app.data.security.authorization.OperationalMode
import com.reeltrak.app.services.tracking.engine.model.SemanticFingerprint

interface DayTimeProvider {
    fun elapsedRealtime(): Long
    fun localDate(): LocalDate
    fun localTime(): LocalTime
    fun currentTimeMillis(): Long
}

enum class ReelCountAcceptance { REJECTED, COUNTED, ALREADY_ACCEPTED }

object DefaultDayTimeProvider : DayTimeProvider {
    override fun elapsedRealtime(): Long = SystemClock.elapsedRealtime()
    override fun localDate(): LocalDate = LocalDate.now()
    override fun localTime(): LocalTime = LocalTime.now()
    override fun currentTimeMillis(): Long = System.currentTimeMillis()
}

@Singleton
class DayManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dailyReelDataDao: DailyReelDataDao,
    private val preferencesRepository: PreferencesRepository,
    private val notificationManager: ReelNotificationManager,
    private val syncManager: SyncManager,
    private val appAuthorizationManager: AppAuthorizationManager? = null,
    private val subscriptionManagerLazy: dagger.Lazy<com.reeltrak.app.domain.managers.SubscriptionManager>? = null
) {
    internal var authorizationManagerOverride: AppAuthorizationManager? = null
    internal val effectiveAuthManager: AppAuthorizationManager?
        get() = authorizationManagerOverride ?: appAuthorizationManager

    internal var subscriptionManagerOverride: com.reeltrak.app.domain.managers.SubscriptionManager? = null
    internal val effectiveSubscriptionManager: com.reeltrak.app.domain.managers.SubscriptionManager?
        get() = subscriptionManagerOverride ?: subscriptionManagerLazy?.get()

    internal var timeProvider: DayTimeProvider = DefaultDayTimeProvider

    // Anchors for detecting illegitimate time jumps vs real time elapsed
    private var lastConfirmedDate: LocalDate = timeProvider.localDate()
    private var lastConfirmedLocalTime: LocalTime = timeProvider.localTime()
    private var lastConfirmedElapsedRealtimeMs: Long = timeProvider.elapsedRealtime()

    private fun updateConfirmationAnchors(
        date: LocalDate = timeProvider.localDate(),
        time: LocalTime = timeProvider.localTime()
    ) {
        lastConfirmedDate = date
        lastConfirmedLocalTime = time
        lastConfirmedElapsedRealtimeMs = timeProvider.elapsedRealtime()
    }

    private fun isLegitimateDateTransition(
        currentDate: LocalDate,
        candidateDate: LocalDate,
        isDateChangedBroadcast: Boolean = false,
        isTimezoneChanged: Boolean = false
    ): Boolean {
        // 1. Backward transitions are NEVER legitimate day rollovers
        if (candidateDate.isBefore(currentDate)) {
            Log.w("DayManager", "Rejected backward date transition: $currentDate -> $candidateDate")
            return false
        }

        // 2. Same date is not a transition
        if (candidateDate == currentDate) {
            return false
        }

        // 3. If Android OS explicitly broadcast ACTION_DATE_CHANGED, the OS confirmed midnight rollover
        if (isDateChangedBroadcast) {
            Log.i("DayManager", "Legitimate date transition confirmed by ACTION_DATE_CHANGED: $currentDate -> $candidateDate")
            return true
        }

        // 4. If ACTION_TIMEZONE_CHANGED was broadcast, allow the timezone shift
        if (isTimezoneChanged) {
            Log.i("DayManager", "Date transition via ACTION_TIMEZONE_CHANGED: $currentDate -> $candidateDate")
            return true
        }

        // 5. Evaluate elapsed real time against time required to reach midnight
        val nowElapsed = timeProvider.elapsedRealtime()
        val elapsedSinceLastAnchorMs = (nowElapsed - lastConfirmedElapsedRealtimeMs).coerceAtLeast(0L)

        // Calculate minimum real time required to reach midnight from last confirmed local time
        val startDateTime = LocalDateTime.of(currentDate, lastConfirmedLocalTime)
        val midnightDateTime = LocalDateTime.of(currentDate.plusDays(1), LocalTime.MIDNIGHT)
        val requiredMillisToMidnight = Duration.between(startDateTime, midnightDateTime).toMillis().coerceAtLeast(0L)

        // Allow a small grace margin of 5 seconds for clock drift near midnight
        val requiredWithGrace = (requiredMillisToMidnight - 5_000L).coerceAtLeast(0L)

        if (elapsedSinceLastAnchorMs < requiredWithGrace) {
            Log.w(
                "DayManager",
                "REJECTED FALSE NEXT-DAY TRANSITION: candidateDate=$candidateDate, " +
                "elapsedSinceAnchor=${elapsedSinceLastAnchorMs}ms, requiredToMidnight=${requiredMillisToMidnight}ms"
            )
            return false
        }

        Log.i(
            "DayManager",
            "Legitimate date transition verified by elapsed real time: $currentDate -> $candidateDate " +
            "(elapsed=${elapsedSinceLastAnchorMs}ms, required=${requiredMillisToMidnight}ms)"
        )
        return true
    }

    private val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        Log.e("DayManager", "Coroutine persistence failed: ${exception.message}", exception)
    }
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
    
    // Core state: The current system date string YYYY-MM-DD based on Android local time.
    private val _currentDateString = MutableStateFlow(getCurrentDateString())
    val currentDateString: StateFlow<String> = _currentDateString.asStateFlow()

    // Core state: The loaded database row for the current day.
    private val _currentDayStats = MutableStateFlow<DailyReelData?>(null)
    val currentDayStats: StateFlow<DailyReelData?> = _currentDayStats.asStateFlow()

    val strictModeDailyState: Flow<StrictModeDailyState> = _currentDayStats
        .map { it?.strictModeDailyState ?: StrictModeDailyState.ACTIVE_INTERACTIVE }

    private var isInitialized = false
    @Volatile private var isRestoring = false
    private val actionMutex = Mutex()

    init {
        setupBroadcastReceiver()
        try {
            WorkManager.getInstance(context).cancelUniqueWork("FirebaseSync")
        } catch (_: Exception) {}
        managerScope.launch {
            actionMutex.withLock {
                val todayStr = getCurrentDateString()
                recoverMissedTransitionsAndInitializeLocked(todayStr)
            }
        }
    }

    /**
     * Authoritative, idempotent recovery pipeline for cold starts, force-stops, reboots, and multi-day gaps.
     * Must be invoked while holding actionMutex.
     */
    private suspend fun recoverMissedTransitionsAndInitializeLocked(todayStr: String) {
        flushPendingWritesLocked()

        val latestRecord = dailyReelDataDao.getLatestHistoricalDataSync(todayStr)
            ?: dailyReelDataDao.getLatestDailyDataSync()

        if (latestRecord != null && latestRecord.dateString < todayStr) {
            Log.i("DayManager", "MISSED TRANSITION RECOVERY: Found historical record ${latestRecord.dateString} prior to today $todayStr")
            // Ensure historical record is finalized in local Room DB.
            // Strict manual-only policy: Never automatically enqueue background sync on midnight or missed transitions.
            val finalizedHistorical = latestRecord.copy(
                updatedAt = timeProvider.currentTimeMillis()
            )
            dailyReelDataDao.insertOrReplace(finalizedHistorical)
        }

        _currentDateString.value = todayStr
        loadOrCreateDayLocked(todayStr)
        val parsedDate = try {
            LocalDate.parse(todayStr, DateTimeFormatter.ISO_LOCAL_DATE)
        } catch (e: Exception) {
            timeProvider.localDate()
        }
        updateConfirmationAnchors(parsedDate, timeProvider.localTime())
        isInitialized = true
    }

    /**
     * Compatibility hook for existing lifecycle/session callers. Reel-count writes are already
     * committed synchronously in Room by acceptReelTransition(), so there is no buffered count
     * snapshot to flush.
     */
    private suspend fun flushPendingWritesLocked() {
        // Reel increments are committed synchronously by the Room transaction. There is no
        // volatile count snapshot left to flush here.
    }

    /** Safe compatibility barrier before reads or lifecycle operations. */
    suspend fun flushPendingWritesSuspend() {
        actionMutex.withLock {
            flushPendingWritesLocked()
        }
    }

    /**
     * Public flush hook for session transitions, service shutdown, or midnight rollover.
     */
    fun flushPendingWrites() {
        managerScope.launch {
            flushPendingWritesSuspend()
        }
    }

    private fun triggerBackgroundSync() {
        // PERMANENTLY DISABLED: Strict Manual-Only Policy.
        // Background sync at midnight or on schedule is prohibited.
        // Local habit data remains strictly local until user explicitly selects "Back Up Data".
        Log.d("DayManager", "triggerBackgroundSync: Disabled per strict manual-only backup policy.")
    }

    private fun getCurrentDateString(): String {
        return timeProvider.localDate().format(DateTimeFormatter.ISO_LOCAL_DATE) // YYYY-MM-DD
    }

    private fun setupBroadcastReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                if (action == Intent.ACTION_DATE_CHANGED) {
                    checkDayTransition(isDateChanged = true)
                } else if (action == Intent.ACTION_TIMEZONE_CHANGED) {
                    checkDayTransition(isTimezoneChanged = true)
                } else if (action == Intent.ACTION_TIME_CHANGED) {
                    checkDayTransition(isTimeChanged = true)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    fun checkDayTransition(
        isDateChanged: Boolean = false,
        isTimezoneChanged: Boolean = false,
        isTimeChanged: Boolean = false
    ) {
        managerScope.launch {
            actionMutex.withLock {
                val today = timeProvider.localDate()
                val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
                val currentStoredStr = _currentDateString.value
                val currentStoredDate = try {
                    LocalDate.parse(currentStoredStr, DateTimeFormatter.ISO_LOCAL_DATE)
                } catch (e: Exception) {
                    today
                }

                if (todayStr != currentStoredStr) {
                    if (isTimezoneChanged) {
                        // Safe timezone change handling:
                        // If a row for todayStr already exists in Room, load it.
                        // If todayStr is ahead, allow forward timezone transition.
                        val existingRow = dailyReelDataDao.getDailyDataSync(todayStr)
                        if (existingRow != null) {
                            Log.i("DayManager", "TIMEZONE_CHANGED: Restoring existing record for $todayStr with count ${existingRow.totalReelsCount}")
                            flushPendingWritesLocked()
                            _currentDateString.value = todayStr
                            loadOrCreateDayLocked(todayStr)
                            updateConfirmationAnchors(today, timeProvider.localTime())
                        } else if (today.isAfter(currentStoredDate)) {
                            Log.i("DayManager", "TIMEZONE_CHANGED: Legitimate forward timezone advance: $currentStoredStr to $todayStr")
                            recoverMissedTransitionsAndInitializeLocked(todayStr)
                        } else {
                            Log.w("DayManager", "TIMEZONE_CHANGED: Ignored backward timezone shift without existing data: $currentStoredStr -> $todayStr")
                        }
                    } else if (isLegitimateDateTransition(currentStoredDate, today, isDateChanged)) {
                        Log.i("DayManager", "DATE CHANGED: $currentStoredStr to $todayStr (dateChanged=$isDateChanged, timeChanged=$isTimeChanged)")
                        recoverMissedTransitionsAndInitializeLocked(todayStr)
                    } else {
                        Log.w("DayManager", "Rejected illegitimate day transition from $currentStoredStr to $todayStr (timeChanged=$isTimeChanged)")
                    }
                }
            }
        }
    }

    private fun checkMilestones(countBefore: Int, stats: DailyReelData) {
        val goal = stats.dailyGoal
        if (goal <= 0) return
        
        val countAfter = stats.totalReelsCount
        val milestones = listOf(
            25 to (goal * 0.25f).toInt(),
            50 to (goal * 0.50f).toInt(),
            75 to (goal * 0.75f).toInt(),
            90 to (goal * 0.90f).toInt()
        )
        for ((percentage, threshold) in milestones) {
            if (threshold > 0 && countBefore < threshold && countAfter >= threshold) {
                notificationManager.showProgressMilestoneNotification(percentage, (goal - countAfter).coerceAtLeast(0))
            }
        }
        if (countBefore < goal && countAfter >= goal) {
            notificationManager.showLimitReachedNotification()
        }
    }

    private suspend fun loadOrCreateDay(dateStr: String) {
        actionMutex.withLock {
            loadOrCreateDayLocked(dateStr)
        }
    }
    
    private suspend fun loadOrCreateDayLocked(dateStr: String) {
        flushPendingWritesLocked()
        
        var stats = dailyReelDataDao.getDailyDataSync(dateStr)
        if (stats == null) {
            val lastStats = dailyReelDataDao.getLatestHistoricalDataSync(dateStr)
            val defaultLimit = preferencesRepository.defaultDailyLimit.first()
            val carryOverLimit = if (defaultLimit > 0) defaultLimit else (lastStats?.dailyGoal ?: defaultLimit)
            stats = DailyReelData(
                dateString = dateStr,
                totalReelsCount = 0,
                dailyGoal = carryOverLimit,
                isLimitLocked = false,
                isStrictModeOn = true,
                isStrictModeLocked = false,
                updatedAt = timeProvider.currentTimeMillis(),
                syncStatus = 1
            )
            dailyReelDataDao.insertOrReplace(stats)
            Log.i("DAY_RESET", "oldDate=${_currentDateString.value} newDate=$dateStr reason=LOCAL_DATE_CHANGED StrictMode=AUTO_ON")
        } else {
            // MONOTONIC INVARIANT PROTECTION:
            // For the SAME local date, the count can ONLY increase.
            // Never overwrite memory with a lower count if memory already has a higher count for dateStr.
            val currentMemory = _currentDayStats.value
            if (currentMemory != null && currentMemory.dateString == dateStr && currentMemory.totalReelsCount > stats.totalReelsCount) {
                Log.w("DAY_STATE_INVARIANT", "Prevented stale DB reload from decreasing count (${currentMemory.totalReelsCount} > ${stats.totalReelsCount})")
                stats = stats.copy(totalReelsCount = currentMemory.totalReelsCount)
                dailyReelDataDao.insertOrReplace(stats)
            } else {
                Log.d("DayManager", "Loaded existing day row for $dateStr with count ${stats.totalReelsCount}")
            }

            // If existing row has unconfigured limit (<= 0), check if preferences have a restored limit
            if (stats.dailyGoal <= 0) {
                val defaultLimit = preferencesRepository.defaultDailyLimit.first()
                if (defaultLimit > 0) {
                    stats = stats.copy(dailyGoal = defaultLimit)
                    dailyReelDataDao.insertOrReplace(stats)
                    Log.i("DayManager", "Reconciled existing day row with defaultDailyLimit: $defaultLimit")
                }
            }

            // DAILY STRICT MODE RECONCILIATION:
            val lastOffDate = preferencesRepository.lastStrictModeOffDate.first()
            if (lastOffDate == stats.dateString) {
                // The user already consumed their daily OFF allowance for today.
                // If Strict Mode was subsequently reactivated today, ensure it is locked.
                if (stats.isStrictModeOn && !stats.isStrictModeLocked) {
                    stats = stats.copy(isStrictModeLocked = true)
                    dailyReelDataDao.insertOrReplace(stats)
                    Log.i("DayManager", "Reconciled Strict Mode lock for $dateStr based on lastStrictModeOffDate")
                }
            } else {
                // The user has NOT consumed their daily OFF allowance today:
                // Strict Mode must unconditionally start ON and unlocked for every new calendar day.
                if (!stats.isStrictModeOn || stats.isStrictModeLocked) {
                    stats = stats.copy(isStrictModeOn = true, isStrictModeLocked = false)
                    dailyReelDataDao.insertOrReplace(stats)
                    Log.i("DAY_RESET", "Reconciled Strict Mode for $dateStr to AUTO_ON (interactive)")
                }
            }
        }
        
        _currentDayStats.value = stats
        isInitialized = true
    }

    fun setRestoring(restoring: Boolean) {
        isRestoring = restoring
        if (!restoring) {
            Log.d("DayManager", "Restore finished. Refreshing memory from DB.")
            managerScope.launch {
                loadOrCreateDay(_currentDateString.value)
            }
        }
    }

    fun setDailyGoal(goal: Int, forceUpdate: Boolean = false) {
        if (isRestoring) return
        val isEntitled = effectiveAuthManager?.getOperationalMode()?.isEntitled
            ?: (effectiveSubscriptionManager?.hasActiveEntitlementNow() == true)
        if (!isEntitled) {
            Log.w("DayManager", "setDailyGoal rejected: Daily Limit is a Trial/Premium-only feature. Current mode: ${effectiveAuthManager?.getOperationalMode()}")
            return
        }
        managerScope.launch {
            actionMutex.withLock {
                flushPendingWritesLocked()
                if (_currentDayStats.value == null) {
                    loadOrCreateDayLocked(_currentDateString.value)
                }
                val currentStats = _currentDayStats.value ?: return@withLock
                if (!forceUpdate && currentStats.isLimitLocked) {
                    Log.d("DayManager", "Limit change rejected. Already locked for today.")
                    return@withLock
                }

                // 1. Save to preferences so it becomes the default for future days if needed
                preferencesRepository.setDailyIntent(goal)
                preferencesRepository.setDefaultDailyLimit(goal)
                val todayEpoch = timeProvider.localDate().toEpochDay()
                preferencesRepository.setGoalSetDate(todayEpoch)
                preferencesRepository.setLastLimitChangeDate(todayEpoch)
                
                // 2. Update the DB for today and lock the limit
                val updatedStats = currentStats.copy(
                    dailyGoal = goal, 
                    isLimitLocked = true,
                    updatedAt = timeProvider.currentTimeMillis(),
                    syncStatus = 1
                )
                dailyReelDataDao.insertOrReplace(updatedStats)
                _currentDayStats.value = updatedStats
            }
            // Strict manual-only policy: Daily goal persists strictly locally.
            // It is backed up only when the user explicitly triggers "Back Up Data".
        }
    }

    /**
     * Restores an account-level daily limit from cloud into active preferences, today's database row, and memory.
     */
    suspend fun restoreActiveDailyLimit(goal: Int) {
        val isEntitled = effectiveAuthManager?.getOperationalMode()?.isEntitled
            ?: (effectiveSubscriptionManager?.hasActiveEntitlementNow() == true)
        if (!isEntitled) {
            Log.w("DayManager", "restoreActiveDailyLimit rejected: Daily Limit is a Trial/Premium-only feature. Mode: ${effectiveAuthManager?.getOperationalMode()}")
            return
        }
        actionMutex.withLock {
            flushPendingWritesLocked()
            preferencesRepository.setDailyIntent(goal)
            preferencesRepository.setDefaultDailyLimit(goal)

            val dateStr = _currentDateString.value
            val currentStats = _currentDayStats.value ?: dailyReelDataDao.getDailyDataSync(dateStr)
            val updatedStats = if (currentStats != null) {
                currentStats.copy(
                    dailyGoal = goal,
                    updatedAt = timeProvider.currentTimeMillis()
                )
            } else {
                DailyReelData(
                    dateString = dateStr,
                    totalReelsCount = 0,
                    dailyGoal = goal,
                    isLimitLocked = false,
                    isStrictModeOn = true,
                    isStrictModeLocked = false,
                    updatedAt = timeProvider.currentTimeMillis(),
                    syncStatus = 1
                )
            }
            dailyReelDataDao.insertOrReplace(updatedStats)
            _currentDayStats.value = updatedStats
            Log.i("DayManager", "Successfully restored active daily limit: $goal for date: $dateStr")
        }
    }

    /**
     * Resets active in-memory session tracking when an account signs out.
     */
    suspend fun resetAccountSession() {
        actionMutex.withLock {
            flushPendingWritesLocked()
            _currentDayStats.update { null }
            isInitialized = false
            Log.i("DayManager", "Active account tracking session reset in memory")
        }
    }

    fun acceptReelTransition(
        fingerprint: SemanticFingerprint,
        token: AuthorizationToken? = null
    ): ReelCountAcceptance {
        if (!isCountAuthorized(token) || isRestoring || !managerScope.isActive) {
            return ReelCountAcceptance.REJECTED
        }

        return try {
            // The service calls this from its IO analysis path. Waiting for this transaction is
            // intentional: TransitionLedger must not report acceptance before Room commits.
            kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                actionMutex.withLock {
                    if (!isCountAuthorized(token) || isRestoring || !managerScope.isActive) {
                        return@withLock ReelCountAcceptance.REJECTED
                    }

                    val today = timeProvider.localDate()
                    val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
                    val currentStoredStr = _currentDateString.value
                    val currentStoredDate = try {
                        LocalDate.parse(currentStoredStr, DateTimeFormatter.ISO_LOCAL_DATE)
                    } catch (_: Exception) {
                        today
                    }

                    if (todayStr != currentStoredStr) {
                        if (isLegitimateDateTransition(currentStoredDate, today)) {
                            Log.i("DAY_RESET", "LOCAL_DATE_CHANGED: $currentStoredStr to $todayStr")
                            recoverMissedTransitionsAndInitializeLocked(todayStr)
                        } else {
                            Log.w("DAY_RESET", "Ignored illegitimate date difference in acceptReelTransition: stored=$currentStoredStr, current=$todayStr")
                        }
                    }

                    if (_currentDayStats.value == null) loadOrCreateDayLocked(todayStr)
                    val currentStats = _currentDayStats.value ?: return@withLock ReelCountAcceptance.REJECTED
                    val dateString = currentStats.dateString
                    val countBefore = currentStats.totalReelsCount
                    val now = timeProvider.currentTimeMillis()
                    val result = dailyReelDataDao.acceptReelTransition(
                        dateString = dateString,
                        accountScope = ReelCountAccountScope.fromUid(token?.uid),
                        fingerprint = fingerprint,
                        acceptedAtMs = now
                    )
                    val committedStats = result.dailyData ?: return@withLock ReelCountAcceptance.REJECTED
                    _currentDayStats.value = committedStats
                    updateConfirmationAnchors(today, timeProvider.localTime())

                    when (result.status) {
                        ReelTransitionWriteStatus.COUNTED -> {
                            checkMilestones(countBefore, committedStats)
                            Log.d("DAY_STATE", "date=$dateString count_before=$countBefore count_after=${committedStats.totalReelsCount} reason=REEL_DETECTED")
                            if (com.reeltrak.app.BuildConfig.DEBUG) {
                                Log.d("COUNTER_DIAG", "[POINT_2_AUTHORITATIVE_INCREMENT] count_before=$countBefore, count_after=${committedStats.totalReelsCount}, date=$dateString, thread=${Thread.currentThread().name}")
                            }
                            ReelCountAcceptance.COUNTED
                        }
                        ReelTransitionWriteStatus.ALREADY_ACCEPTED -> ReelCountAcceptance.ALREADY_ACCEPTED
                        ReelTransitionWriteStatus.DAILY_ROW_MISSING -> ReelCountAcceptance.REJECTED
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.e("DayManager", "Durable reel transition transaction failed; transition rejected", failure)
            ReelCountAcceptance.REJECTED
        }
    }

    private fun isCountAuthorized(token: AuthorizationToken?): Boolean {
        val authMgr = effectiveAuthManager ?: return true
        if (authMgr.getOperationalMode() == OperationalMode.LOCKED_OR_EXPIRED) return false
        return token == null || authMgr.verifyToken(token)
    }

    fun appendSession(sessionData: SessionData, token: AuthorizationToken? = null) {
        if (isRestoring) return
        
        // PERSISTENCE GATE: Explicit Operational Mode Verification
        val authMgr = effectiveAuthManager
        if (authMgr != null && token != null) {
            val mode = authMgr.getOperationalMode()
            if (mode == OperationalMode.LOCKED_OR_EXPIRED || !authMgr.verifyToken(token)) {
                Log.w("DayManager", "PERSISTENCE GATE REJECTION: appendSession dropped due to invalid authorization token or locked mode")
                return
            }
        } else if (authMgr != null) {
            val mode = authMgr.getOperationalMode()
            if (mode == OperationalMode.LOCKED_OR_EXPIRED) {
                Log.w("DayManager", "PERSISTENCE GATE REJECTION: appendSession dropped in LOCKED_OR_EXPIRED mode")
                return
            }
        }

        managerScope.launch {
            actionMutex.withLock {
                val today = timeProvider.localDate()
                val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
                val currentStoredStr = _currentDateString.value
                val currentStoredDate = try {
                    LocalDate.parse(currentStoredStr, DateTimeFormatter.ISO_LOCAL_DATE)
                } catch (e: Exception) {
                    today
                }
                
                if (todayStr != currentStoredStr) {
                    if (isLegitimateDateTransition(currentStoredDate, today)) {
                        Log.i("DayManager", "MIDNIGHT SESSION DETECTED: $currentStoredStr to $todayStr")
                        recoverMissedTransitionsAndInitializeLocked(todayStr)
                    } else {
                        Log.w("DayManager", "Ignored illegitimate date difference in appendSession: stored=$currentStoredStr, current=$todayStr")
                    }
                }
                
                if (_currentDayStats.value == null) {
                    loadOrCreateDayLocked(todayStr)
                }

                val now = timeProvider.currentTimeMillis()
                val currentStats = _currentDayStats.value ?: return@withLock
                
                val updatedSessions = currentStats.sessions.toMutableList()
                updatedSessions.add(sessionData)
                val addedTime = sessionData.endTimeStamp - sessionData.startTimeStamp
                
                val updated = currentStats.copy(
                    sessions = updatedSessions,
                    totalTimeSpentMillis = currentStats.totalTimeSpentMillis + addedTime,
                    updatedAt = now,
                    syncStatus = 1
                )
                
                _currentDayStats.value = updated
                updateConfirmationAnchors(today, timeProvider.localTime())
                // AppendSession represents a session boundary: flush immediately
                flushPendingWritesLocked()
                dailyReelDataDao.insertOrReplace(updated)
            }
        }
    }

    /**
     * Disables Strict Mode for today (user's one-time privilege).
     * Rejected if user lacks active entitlement (Premium or Trial)
     * or if Strict Mode is already locked for today.
     */
    fun disableStrictModeForToday() {
        if (isRestoring) return
        if (effectiveSubscriptionManager?.hasActiveEntitlementNow() != true) {
            Log.w("DayManager", "disableStrictModeForToday rejected: User does not have active Premium or Trial entitlement.")
            return
        }
        
        managerScope.launch {
            actionMutex.withLock {
                flushPendingWritesLocked()
                if (_currentDayStats.value == null) {
                    loadOrCreateDayLocked(_currentDateString.value)
                }
                val currentStats = _currentDayStats.value ?: return@withLock
                
                if (currentStats.isStrictModeLocked) {
                    Log.d("DayManager", "Strict Mode disable rejected. Already locked for today.")
                    return@withLock
                }

                val todayStr = currentStats.dateString
                val lastOffDate = preferencesRepository.lastStrictModeOffDate.first()
                if (lastOffDate == todayStr) {
                    Log.d("DayManager", "Strict Mode disable rejected. Already turned OFF once today ($todayStr).")
                    return@withLock
                }

                val updated = currentStats.copy(
                    isStrictModeOn = false,
                    isStrictModeLocked = false,
                    updatedAt = timeProvider.currentTimeMillis(),
                    syncStatus = 1
                )
                
                preferencesRepository.setLastStrictModeOffDate(todayStr)
                _currentDayStats.value = updated
                dailyReelDataDao.insertOrReplace(updated)
                Log.i("DayManager", "Strict Mode disabled for today by user ($todayStr).")
            }
        }
    }

    /**
     * Reactivates Strict Mode after successful completion of a rewarded ad.
     * Enforcement becomes active and LOCKED for the remainder of today.
     * Rejected if user lacks active entitlement (Premium or Trial).
     */
    fun reactivateStrictModeWithAd() {
        if (isRestoring) return
        if (effectiveSubscriptionManager?.hasActiveEntitlementNow() != true) {
            Log.w("DayManager", "reactivateStrictModeWithAd rejected: User does not have active Premium or Trial entitlement.")
            return
        }

        managerScope.launch {
            actionMutex.withLock {
                flushPendingWritesLocked()
                if (_currentDayStats.value == null) {
                    loadOrCreateDayLocked(_currentDateString.value)
                }
                val currentStats = _currentDayStats.value ?: return@withLock

                val updated = currentStats.copy(
                    isStrictModeOn = true,
                    isStrictModeLocked = true,
                    updatedAt = timeProvider.currentTimeMillis(),
                    syncStatus = 1
                )

                _currentDayStats.value = updated
                dailyReelDataDao.insertOrReplace(updated)
                Log.i("DayManager", "Strict Mode reactivated and locked for today after rewarded ad.")
            }
        }
    }

    fun setStrictMode(enabled: Boolean) {
        if (effectiveSubscriptionManager?.hasActiveEntitlementNow() != true) {
            Log.w("DayManager", "setStrictMode rejected: User does not have active Premium or Trial entitlement.")
            return
        }
        if (enabled) {
            reactivateStrictModeWithAd()
        } else {
            disableStrictModeForToday()
        }
    }

    /**
     * Authoritatively verifies whether Strict Mode is both entitled (Active Premium or Active Trial)
     * AND enabled for the current active tracking day.
     */
    fun isStrictModeAuthorizedAndActiveNow(): Boolean {
        val hasEntitlement = effectiveSubscriptionManager?.hasActiveEntitlementNow() == true
        val isEnabledOnCurrentDay = _currentDayStats.value?.isStrictModeOn == true
        return hasEntitlement && isEnabledOnCurrentDay
    }

    /**
     * Authoritatively determines if the user is currently eligible to turn Strict Mode OFF today.
     * Requires active entitlement, Strict Mode being ON, not locked, and today's OFF allowance unused.
     */
    suspend fun canDisableStrictModeToday(): Boolean {
        val currentStats = _currentDayStats.value ?: return false
        val hasEntitlement = effectiveSubscriptionManager?.hasActiveEntitlementNow() == true
        val lastOffDate = preferencesRepository.lastStrictModeOffDate.first()
        return hasEntitlement && currentStats.isStrictModeOn && !currentStats.isStrictModeLocked && lastOffDate != currentStats.dateString
    }
}
