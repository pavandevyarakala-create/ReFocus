package com.reeltrak.app.services.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import com.reeltrak.app.data.local.dao.DailyReelDataDao
import com.reeltrak.app.data.local.entity.DailyReelData
import com.reeltrak.app.data.local.entity.SessionData
import com.reeltrak.app.domain.repository.PreferencesRepository
import com.reeltrak.app.services.notifications.ReelNotificationManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.reeltrak.app.core.sync.SyncManager

@Singleton
class DayManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dailyReelDataDao: DailyReelDataDao,
    private val preferencesRepository: PreferencesRepository,
    private val notificationManager: ReelNotificationManager,
    private val syncManager: SyncManager
) {
    private val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        Log.e("DayManager", "Coroutine persistence failed: ${exception.message}", exception)
    }
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
    
    // Core state: The current system date string YYYY-MM-DD.
    private val _currentDateString = MutableStateFlow(getCurrentDateString())
    val currentDateString: StateFlow<String> = _currentDateString.asStateFlow()

    // Core state: The loaded database row for the current day.
    private val _currentDayStats = MutableStateFlow<DailyReelData?>(null)
    val currentDayStats: StateFlow<DailyReelData?> = _currentDayStats.asStateFlow()

    private var isInitialized = false
    private var isRestoring = false
    private val actionMutex = Mutex()

    init {
        setupBroadcastReceiver()
        managerScope.launch {
            _currentDateString.collect { dateStr ->
                loadOrCreateDay(dateStr)
            }
        }
    }

    private fun getCurrentDateString(): String {
        return LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE) // YYYY-MM-DD
    }

    private fun setupBroadcastReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                checkDayTransition()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    fun checkDayTransition() {
        managerScope.launch {
            actionMutex.withLock {
                val todayStr = getCurrentDateString()
                val currentStoredStr = _currentDateString.value
                
                if (todayStr != currentStoredStr) {
                    Log.i("DayManager", "DATE CHANGED: $currentStoredStr to $todayStr")
                    // Final End-Of-Day Sync for yesterday
                    _currentDayStats.value?.let { 
                        dailyReelDataDao.insertOrReplace(it)
                    }
                    syncManager.backupSpecificDay(currentStoredStr)
                    
                    _currentDateString.value = todayStr
                    loadOrCreateDayLocked(todayStr)
                }
            }
        }
    }

    private fun checkMilestones(stats: DailyReelData) {
        val goal = stats.dailyGoal
        if (goal <= 0) return
        
        val count = stats.totalReelsCount
        
        when (count) {
            goal -> notificationManager.showLimitReachedNotification()
            (goal * 0.90f).toInt() -> notificationManager.showProgressMilestoneNotification(90, goal - count)
            (goal * 0.75f).toInt() -> notificationManager.showProgressMilestoneNotification(75, goal - count)
            (goal * 0.50f).toInt() -> notificationManager.showProgressMilestoneNotification(50, goal - count)
            (goal * 0.25f).toInt() -> notificationManager.showProgressMilestoneNotification(25, goal - count)
        }
    }

    private suspend fun loadOrCreateDay(dateStr: String) {
        actionMutex.withLock {
            loadOrCreateDayLocked(dateStr)
        }
    }
    
    private suspend fun loadOrCreateDayLocked(dateStr: String) {
        _currentDayStats.value = null
        
        var stats = dailyReelDataDao.getDailyDataSync(dateStr)
        if (stats == null) {
            val lastStats = dailyReelDataDao.getLatestDailyDataSync()
            val carryOverLimit = lastStats?.dailyGoal ?: preferencesRepository.defaultDailyLimit.first()
            val carryOverStrictMode = lastStats?.isStrictModeOn ?: false
            stats = DailyReelData(
                dateString = dateStr,
                totalReelsCount = 0,
                dailyGoal = carryOverLimit,
                isLimitLocked = false,
                isStrictModeOn = carryOverStrictMode,
                isStrictModeLocked = false,
                updatedAt = System.currentTimeMillis()
            )
            dailyReelDataDao.insertOrReplace(stats)
            Log.d("DayManager", "Created new day row for $dateStr with goal $carryOverLimit")
        } else {
            Log.d("DayManager", "Loaded existing day row for $dateStr with count ${stats.totalReelsCount}")
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

    fun setDailyGoal(goal: Int) {
        if (isRestoring) return
        managerScope.launch {
            actionMutex.withLock {
                val currentStats = _currentDayStats.value ?: return@withLock
                if (currentStats.isLimitLocked) {
                    Log.d("DayManager", "Limit change rejected. Already locked for today.")
                    return@withLock
                }

                // 1. Save to preferences so it becomes the default for future days if needed
                preferencesRepository.setDailyIntent(goal)
                preferencesRepository.setDefaultDailyLimit(goal)
                // Storing current epoch day for legacy logic if needed
                val todayEpoch = LocalDate.now().toEpochDay()
                preferencesRepository.setGoalSetDate(todayEpoch)
                preferencesRepository.setLastLimitChangeDate(todayEpoch)
                
                // 2. Update the DB for today and lock the limit
                val updatedStats = currentStats.copy(
                    dailyGoal = goal, 
                    isLimitLocked = true,
                    updatedAt = System.currentTimeMillis()
                )
                dailyReelDataDao.insertOrReplace(updatedStats)
                _currentDayStats.value = updatedStats
            }
        }
    }

    fun incrementReelCount(amount: Int = 1) {
        if (isRestoring) return
        
        managerScope.launch {
            actionMutex.withLock {
                val todayStr = getCurrentDateString()
                val currentStoredStr = _currentDateString.value
                
                if (todayStr != currentStoredStr) {
                    Log.i("DayManager", "MIDNIGHT SCROLL DETECTED: $currentStoredStr to $todayStr")
                    // Final End-Of-Day Sync for yesterday
                    _currentDayStats.value?.let { 
                        dailyReelDataDao.insertOrReplace(it)
                    }
                    syncManager.backupSpecificDay(currentStoredStr)
                    
                    _currentDateString.value = todayStr
                    loadOrCreateDayLocked(todayStr)
                }
                
                val now = System.currentTimeMillis()
                val currentStats = _currentDayStats.value ?: return@withLock
                
                val updated = currentStats.copy(
                    totalReelsCount = currentStats.totalReelsCount + amount,
                    updatedAt = now
                )
                
                _currentDayStats.value = updated
                checkMilestones(updated)
                
                dailyReelDataDao.insertOrReplace(updated)
                // Real-time syncManager.backupToCloud() removed
            }
        }
    }

    fun appendSession(sessionData: SessionData) {
        if (isRestoring) return
        
        managerScope.launch {
            actionMutex.withLock {
                val todayStr = getCurrentDateString()
                val currentStoredStr = _currentDateString.value
                
                if (todayStr != currentStoredStr) {
                    Log.i("DayManager", "MIDNIGHT SESSION DETECTED: $currentStoredStr to $todayStr")
                    // Final End-Of-Day Sync for yesterday
                    _currentDayStats.value?.let { 
                        dailyReelDataDao.insertOrReplace(it)
                    }
                    syncManager.backupSpecificDay(currentStoredStr)
                    
                    _currentDateString.value = todayStr
                    loadOrCreateDayLocked(todayStr)
                }
                
                val now = System.currentTimeMillis()
                val currentStats = _currentDayStats.value ?: return@withLock
                
                val updatedSessions = currentStats.sessions.toMutableList()
                updatedSessions.add(sessionData)
                val addedTime = sessionData.endTimeStamp - sessionData.startTimeStamp
                
                val updated = currentStats.copy(
                    sessions = updatedSessions,
                    totalTimeSpentMillis = currentStats.totalTimeSpentMillis + addedTime,
                    updatedAt = now
                )
                
                _currentDayStats.value = updated
                dailyReelDataDao.insertOrReplace(updated)
                // Real-time syncManager.backupToCloud() removed
            }
        }
    }

    fun setStrictMode(enabled: Boolean) {
        if (isRestoring) return
        
        managerScope.launch {
            actionMutex.withLock {
                val currentStats = _currentDayStats.value ?: return@withLock
                
                if (currentStats.isStrictModeLocked) {
                    Log.d("DayManager", "Strict Mode change rejected. Already locked for today.")
                    return@withLock
                }

                val updated = currentStats.copy(
                    isStrictModeOn = enabled,
                    isStrictModeLocked = enabled, // One-Way Lock: Lock it immediately if turned ON
                    updatedAt = System.currentTimeMillis()
                )
                
                _currentDayStats.value = updated
                dailyReelDataDao.insertOrReplace(updated)
            }
        }
    }
}
