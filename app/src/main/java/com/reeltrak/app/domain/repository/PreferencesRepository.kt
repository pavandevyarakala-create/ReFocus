package com.reeltrak.app.domain.repository

import kotlinx.coroutines.flow.Flow

interface PreferencesRepository {
    val dailyIntent: Flow<Int>
    val firstRunDate: Flow<Long>
    val goalSetDate: Flow<Long>
    val isStrictModeLocked: Flow<Boolean>
    val lastStrictModeOffDate: Flow<String?>
    val isDailyLimitLocked: Flow<Boolean>
    val lastLockEpochDay: Flow<Long>

    val defaultDailyLimit: Flow<Int>
    val lastLimitChangeDate: Flow<Long>
    val lastDailySummaryDate: Flow<Long>
    
    val notificationsEnabled: Flow<Boolean>
    
    val badge7UnlockTime: Flow<Long>
    val badge30UnlockTime: Flow<Long>
    val badge50UnlockTime: Flow<Long>
    val badge100UnlockTime: Flow<Long>
    val badge365UnlockTime: Flow<Long>
    
    val badge7DialogShown: Flow<Boolean>
    val badge30DialogShown: Flow<Boolean>
    val badge50DialogShown: Flow<Boolean>
    val badge100DialogShown: Flow<Boolean>
    val badge365DialogShown: Flow<Boolean>
    
    suspend fun setBadgeUnlockTime(milestone: Int, time: Long)
    suspend fun setBadgeDialogShown(milestone: Int, shown: Boolean)

    suspend fun setDefaultDailyLimit(limit: Int)
    suspend fun setLastLimitChangeDate(date: Long)
    suspend fun setLastDailySummaryDate(date: Long)
    
    suspend fun setDailyIntent(intent: Int)
    suspend fun setFirstRunDate(date: Long)
    suspend fun setGoalSetDate(date: Long)
    suspend fun setStrictModeLocked(locked: Boolean)
    suspend fun setLastStrictModeOffDate(date: String)
    suspend fun setDailyLimitLocked(locked: Boolean)
    suspend fun setLastLockEpochDay(day: Long)

    suspend fun setNotificationsEnabled(enabled: Boolean)

    val counterThemeMode: Flow<com.reeltrak.app.domain.models.ThemeMode>
    suspend fun setCounterThemeMode(mode: com.reeltrak.app.domain.models.ThemeMode)

    val authSession: Flow<com.reeltrak.app.core.auth.UserAuthData?>
    suspend fun saveAuthSession(userData: com.reeltrak.app.core.auth.UserAuthData)
    suspend fun clearAuthSession()
    suspend fun clearAllPreferences()

    val isFreeMode: Flow<Boolean>
    suspend fun setFreeMode(enabled: Boolean)

    val hasMigratedToServer: Flow<Boolean>
    suspend fun setHasMigratedToServer(migrated: Boolean)

    val hasCompletedTour: Flow<Boolean>
    suspend fun setHasCompletedTour(completed: Boolean)

    val lastBackupTime: Flow<Long>
    suspend fun setLastBackupTime(time: Long)

    val currentBackupRequestId: Flow<String?> get() = kotlinx.coroutines.flow.flowOf(null)
    suspend fun setCurrentBackupRequestId(requestId: String?) {}

    val currentRestoreRequestId: Flow<String?> get() = kotlinx.coroutines.flow.flowOf(null)
    suspend fun setCurrentRestoreRequestId(requestId: String?) {}

    val lastRestoreTime: Flow<Long> get() = kotlinx.coroutines.flow.flowOf(0L)
    suspend fun setLastRestoreTime(time: Long) {}

    val lastBackupStatus: Flow<String?> get() = kotlinx.coroutines.flow.flowOf(null)
    suspend fun setLastBackupStatus(status: String?) {}

    suspend fun getAllPreferencesSync(): Map<String, Any>
    suspend fun restorePreferences(cloudData: Map<String, Any>)
}
