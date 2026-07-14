package com.reeltrak.app.data.repository

import android.content.Context
import android.os.Environment
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.reeltrak.app.domain.repository.PreferencesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "settings")

@Singleton
class PreferencesRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : PreferencesRepository {

    private val DAILY_INTENT_KEY = intPreferencesKey("daily_intent")
    private val STRICT_MODE_KEY = androidx.datastore.preferences.core.booleanPreferencesKey("strict_mode")
    private val FIRST_RUN_DATE_KEY = longPreferencesKey("first_run_date")
    private val GOAL_SET_DATE_KEY = longPreferencesKey("goal_set_date")
    private val STRICT_MODE_LOCKED_KEY = androidx.datastore.preferences.core.booleanPreferencesKey("strict_mode_locked")
    private val DAILY_LIMIT_LOCKED_KEY = androidx.datastore.preferences.core.booleanPreferencesKey("daily_limit_locked")
    private val LAST_LOCK_EPOCH_DAY_KEY = longPreferencesKey("last_lock_epoch_day")

    // The Hidden File Trick Backup
    private val backupFile: File? by lazy {
        try {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            if (!dir.exists()) dir.mkdirs()
            File(dir, ".reeltrak_intent_backup.txt")
        } catch (e: Exception) {
            null
        }
    }

    override val dailyIntent: Flow<Int> = context.dataStore.data
        .map { preferences ->
            var intent = preferences[DAILY_INTENT_KEY] ?: 0
            
            // If DataStore is wiped, instantly restore from the hidden file!
            if (intent == 0) {
                try {
                    backupFile?.let { file ->
                        if (file.exists()) {
                            val backupVal = file.readText().toIntOrNull()
                            if (backupVal != null && backupVal > 0) {
                                intent = backupVal
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Ignore backup read errors
                }
            }
            intent
        }

    override suspend fun setDailyIntent(intent: Int) {
        // 1. Save to standard secure DataStore
        context.dataStore.edit { preferences ->
            preferences[DAILY_INTENT_KEY] = intent
        }
        
        // 2. Save to the invisible backup file in the public Documents folder
        try {
            backupFile?.writeText(intent.toString())
        } catch (e: Exception) {
            // Ignore write errors if permissions are restricted on Android 11+
        }
    }

    override val isStrictModeEnabled: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[STRICT_MODE_KEY] ?: true
        }

    override suspend fun setStrictMode(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[STRICT_MODE_KEY] = enabled
        }
    }

    override val firstRunDate: Flow<Long> = context.dataStore.data
        .map { preferences ->
            preferences[FIRST_RUN_DATE_KEY] ?: 0L
        }

    override suspend fun setFirstRunDate(date: Long) {
        context.dataStore.edit { preferences ->
            preferences[FIRST_RUN_DATE_KEY] = date
        }
    }

    override val goalSetDate: Flow<Long> = context.dataStore.data
        .map { preferences ->
            preferences[GOAL_SET_DATE_KEY] ?: 0L
        }

    override suspend fun setGoalSetDate(date: Long) {
        context.dataStore.edit { preferences ->
            preferences[GOAL_SET_DATE_KEY] = date
        }
    }

    override val isStrictModeLocked: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[STRICT_MODE_LOCKED_KEY] ?: false
        }

    override suspend fun setStrictModeLocked(locked: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[STRICT_MODE_LOCKED_KEY] = locked
        }
    }

    override val isDailyLimitLocked: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[DAILY_LIMIT_LOCKED_KEY] ?: false
        }

    override suspend fun setDailyLimitLocked(locked: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[DAILY_LIMIT_LOCKED_KEY] = locked
        }
    }

    override val lastLockEpochDay: Flow<Long> = context.dataStore.data
        .map { preferences ->
            preferences[LAST_LOCK_EPOCH_DAY_KEY] ?: 0L
        }

    override suspend fun setLastLockEpochDay(day: Long) {
        context.dataStore.edit { preferences ->
            preferences[LAST_LOCK_EPOCH_DAY_KEY] = day
        }
    }
}
