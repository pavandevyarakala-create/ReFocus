package com.reeltrak.app.services.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import com.reeltrak.app.data.local.dao.DailyStatisticsDao
import com.reeltrak.app.data.local.dao.GlobalStatisticsDao
import com.reeltrak.app.data.local.entity.DailyStatisticsEntity
import com.reeltrak.app.domain.repository.PreferencesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DayManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dailyStatisticsDao: DailyStatisticsDao,
    private val globalStatisticsDao: GlobalStatisticsDao,
    private val preferencesRepository: PreferencesRepository
) {
    private val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        Log.e("DayManager", "Coroutine persistence failed: ${exception.message}", exception)
    }
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
    
    // Core state: The current system epoch day.
    private val _currentEpochDay = MutableStateFlow(getCurrentSystemEpochDay())
    val currentEpochDay: StateFlow<Long> = _currentEpochDay.asStateFlow()

    // Core state: The loaded database row for the current day.
    private val _currentDayStats = MutableStateFlow<DailyStatisticsEntity?>(null)
    val currentDayStats: StateFlow<DailyStatisticsEntity?> = _currentDayStats.asStateFlow()

    private var isInitialized = false
    private val lock = Any()

    init {
        setupBroadcastReceiver()
        managerScope.launch {
            // Re-evaluate whenever the day changes
            _currentEpochDay.collect { epochDay ->
                loadOrCreateDay(epochDay)
            }
        }
        
        // Only listen for date changes now. We do NOT listen to the global daily intent preference
        // because each day manages its own independent goal state.
        // setDailyGoal() already manually handles saving to both when a goal is explicitly set.
    }

    private fun getCurrentSystemEpochDay(): Long {
        return LocalDate.now().toEpochDay()
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

    /**
     * Call this when resuming the app or from watchdog/services to ensure
     * we haven't missed a day transition.
     */
    fun checkDayTransition() {
        val today = getCurrentSystemEpochDay()
        val currentStoredDay = _currentEpochDay.value
        
        if (today > currentStoredDay) {
            Log.i("DayManager", "MIDNIGHT(S) DETECTED: Finalizing days from $currentStoredDay to ${today - 1}")
            managerScope.launch {
                // Loop through every missed day (including the one that just ended)
                for (epoch in currentStoredDay until today) {
                    finalizeDay(epoch)
                }
            }
            // Transition to new day
            _currentEpochDay.value = today
        } else if (today < currentStoredDay) {
            Log.w("DayManager", "BACKWARD TIME TRAVEL DETECTED: Moving from $currentStoredDay back to $today.")
            _currentEpochDay.value = today
        }
    }

    private suspend fun finalizeDay(epochDay: Long) {
        val stats = dailyStatisticsDao.getStatisticsForDate(epochDay)
        val todayReels = stats?.totalReelsScrolled ?: 0
        
        // 1. Update Daily Stats (Performance indicators for the heatmap)
        if (stats != null && stats.dailyGoal > 0) {
            val percentage = (todayReels.toFloat() / stats.dailyGoal.toFloat()) * 100f
            val level = when {
                percentage <= 50f -> 1 // Dark Green
                percentage <= 85f -> 2 // Medium Green
                percentage <= 100f -> 3 // Soft Red
                percentage <= 117f -> 4 // Medium Red
                else -> 5 // Medium Dark Red
            }
            dailyStatisticsDao.finalizeDay(epochDay, percentage, level)
        }
        
        // 2. Update Global Stats (Atomic update for Lifetime Reels + Completed Days)
        globalStatisticsDao.incrementCompletedDay(todayReels)
        
        Log.d("DayManager", "Finalized day $epochDay: reels=$todayReels")
    }

    private suspend fun loadOrCreateDay(epochDay: Long) {
        synchronized(lock) {
            // Pre-emptively clear current stats while loading
            _currentDayStats.value = null
        }
        
        var stats = dailyStatisticsDao.getStatisticsForDate(epochDay)
        if (stats == null) {
            stats = DailyStatisticsEntity(
                dateEpochDay = epochDay,
                totalReelsScrolled = 0,
                dailyGoal = -1, // -1 explicitly indicates no goal set for today
                lastUpdated = System.currentTimeMillis()
            )
            dailyStatisticsDao.insert(stats)
            Log.d("DayManager", "Created new day row for $epochDay with goal -1 (Not Set)")
        } else {
            Log.d("DayManager", "Loaded existing day row for $epochDay with count ${stats.totalReelsScrolled}")
        }
        
        _currentDayStats.value = stats
        isInitialized = true
    }

    /**
     * Sets the goal for the CURRENT active day and persists it to preferences.
     */
    fun setDailyGoal(goal: Int) {
        managerScope.launch {
            val today = _currentEpochDay.value
            
            // 1. Save to preferences so it becomes the default for future days
            preferencesRepository.setDailyIntent(goal)
            preferencesRepository.setGoalSetDate(today)
            
            // 2. Update the DB for today
            dailyStatisticsDao.updateGoal(today, goal)
            
            // 3. Reflect instantly in current memory
            _currentDayStats.update { it?.copy(dailyGoal = goal) }
        }
    }

    /**
     * Increments the reel count for the CURRENT active day.
     */
    fun incrementReelCount(amount: Int = 1) {
        checkDayTransition() // Ensure we're on the right day before incrementing
        val today = _currentEpochDay.value
        val now = System.currentTimeMillis()
        
        // 1. Reflect instantly in current memory (optimistic update - completely synchronous for 0ms UI latency)
        _currentDayStats.update { 
            it?.copy(
                totalReelsScrolled = it.totalReelsScrolled + amount,
                lastUpdated = now
            ) 
        }
        
        managerScope.launch {
            // 2. Update DB asynchronously
            dailyStatisticsDao.incrementCount(today, amount, now)
        }
    }
}
