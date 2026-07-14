package com.reeltrak.app.domain.repository

import kotlinx.coroutines.flow.Flow

interface PreferencesRepository {
    val dailyIntent: Flow<Int>
    val isStrictModeEnabled: Flow<Boolean>
    val firstRunDate: Flow<Long>
    val goalSetDate: Flow<Long>
    val isStrictModeLocked: Flow<Boolean>
    val isDailyLimitLocked: Flow<Boolean>
    val lastLockEpochDay: Flow<Long>
    
    suspend fun setDailyIntent(intent: Int)
    suspend fun setStrictMode(enabled: Boolean)
    suspend fun setFirstRunDate(date: Long)
    suspend fun setGoalSetDate(date: Long)
    suspend fun setStrictModeLocked(locked: Boolean)
    suspend fun setDailyLimitLocked(locked: Boolean)
    suspend fun setLastLockEpochDay(day: Long)
}
