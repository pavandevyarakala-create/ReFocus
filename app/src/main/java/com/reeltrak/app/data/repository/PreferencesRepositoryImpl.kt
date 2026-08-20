package com.reeltrak.app.data.repository

import android.content.Context
import android.os.Environment
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.reeltrak.app.domain.repository.PreferencesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
    private val FIRST_RUN_DATE_KEY = longPreferencesKey("first_run_date")
    private val GOAL_SET_DATE_KEY = longPreferencesKey("goal_set_date")
    private val DAILY_LIMIT_LOCKED_KEY = booleanPreferencesKey("daily_limit_locked")
    private val STRICT_MODE_LOCKED_KEY = booleanPreferencesKey("strict_mode_locked")
    private val LAST_LOCK_EPOCH_DAY_KEY = longPreferencesKey("last_lock_epoch_day")

    private val DEFAULT_DAILY_LIMIT_KEY = intPreferencesKey("default_daily_limit")
    private val LAST_LIMIT_CHANGE_DATE_KEY = longPreferencesKey("last_limit_change_date")
    private val LAST_DAILY_SUMMARY_DATE_KEY = longPreferencesKey("last_daily_summary_date")
    
    private val BADGE_7_UNLOCK_TIME_KEY = longPreferencesKey("badge_7_unlock_time")
    private val BADGE_30_UNLOCK_TIME_KEY = longPreferencesKey("badge_30_unlock_time")
    private val BADGE_50_UNLOCK_TIME_KEY = longPreferencesKey("badge_50_unlock_time")
    private val BADGE_100_UNLOCK_TIME_KEY = longPreferencesKey("badge_100_unlock_time")
    private val BADGE_365_UNLOCK_TIME_KEY = longPreferencesKey("badge_365_unlock_time")

    private val BADGE_7_DIALOG_SHOWN_KEY = booleanPreferencesKey("badge_7_dialog_shown")
    private val BADGE_30_DIALOG_SHOWN_KEY = booleanPreferencesKey("badge_30_dialog_shown")
    private val BADGE_50_DIALOG_SHOWN_KEY = booleanPreferencesKey("badge_50_dialog_shown")
    private val BADGE_100_DIALOG_SHOWN_KEY = booleanPreferencesKey("badge_100_dialog_shown")
    private val BADGE_365_DIALOG_SHOWN_KEY = booleanPreferencesKey("badge_365_dialog_shown")

    // Authentication
    private val AUTH_ID_TOKEN_KEY = stringPreferencesKey("auth_id_token")
    private val AUTH_EMAIL_KEY = stringPreferencesKey("auth_email")
    private val AUTH_DISPLAY_NAME_KEY = stringPreferencesKey("auth_display_name")
    private val AUTH_PROFILE_PICTURE_URI_KEY = stringPreferencesKey("auth_profile_picture_uri")

    private val HAS_MIGRATED_TO_SERVER_KEY = booleanPreferencesKey("has_migrated_to_server")

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

    override val isStrictModeLocked: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[STRICT_MODE_LOCKED_KEY] ?: false }

    override suspend fun setStrictModeLocked(locked: Boolean) {
        context.dataStore.edit { preferences -> preferences[STRICT_MODE_LOCKED_KEY] = locked }
    }

    override val lastLockEpochDay: Flow<Long> = context.dataStore.data
        .map { preferences -> preferences[LAST_LOCK_EPOCH_DAY_KEY] ?: 0L }

    override suspend fun setLastLockEpochDay(day: Long) {
        context.dataStore.edit { preferences -> preferences[LAST_LOCK_EPOCH_DAY_KEY] = day }
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

    override val isDailyLimitLocked: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[DAILY_LIMIT_LOCKED_KEY] ?: false
        }

    override suspend fun setDailyLimitLocked(locked: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[DAILY_LIMIT_LOCKED_KEY] = locked
        }
    }

    override val defaultDailyLimit: Flow<Int> = context.dataStore.data
        .map { preferences ->
            preferences[DEFAULT_DAILY_LIMIT_KEY] ?: -1
        }

    override suspend fun setDefaultDailyLimit(limit: Int) {
        context.dataStore.edit { preferences ->
            preferences[DEFAULT_DAILY_LIMIT_KEY] = limit
        }
    }

    override val lastLimitChangeDate: Flow<Long> = context.dataStore.data
        .map { preferences ->
            preferences[LAST_LIMIT_CHANGE_DATE_KEY] ?: 0L
        }

    override suspend fun setLastLimitChangeDate(date: Long) {
        context.dataStore.edit { preferences ->
            preferences[LAST_LIMIT_CHANGE_DATE_KEY] = date
        }
    }

    override val lastDailySummaryDate: Flow<Long> = context.dataStore.data
        .map { preferences ->
            preferences[LAST_DAILY_SUMMARY_DATE_KEY] ?: 0L
        }

    override suspend fun setLastDailySummaryDate(date: Long) {
        context.dataStore.edit { preferences ->
            preferences[LAST_DAILY_SUMMARY_DATE_KEY] = date
        }
    }

    override val badge7UnlockTime: Flow<Long> = context.dataStore.data.map { it[BADGE_7_UNLOCK_TIME_KEY] ?: 0L }
    override val badge30UnlockTime: Flow<Long> = context.dataStore.data.map { it[BADGE_30_UNLOCK_TIME_KEY] ?: 0L }
    override val badge50UnlockTime: Flow<Long> = context.dataStore.data.map { it[BADGE_50_UNLOCK_TIME_KEY] ?: 0L }
    override val badge100UnlockTime: Flow<Long> = context.dataStore.data.map { it[BADGE_100_UNLOCK_TIME_KEY] ?: 0L }
    override val badge365UnlockTime: Flow<Long> = context.dataStore.data.map { it[BADGE_365_UNLOCK_TIME_KEY] ?: 0L }

    override val badge7DialogShown: Flow<Boolean> = context.dataStore.data.map { it[BADGE_7_DIALOG_SHOWN_KEY] ?: false }
    override val badge30DialogShown: Flow<Boolean> = context.dataStore.data.map { it[BADGE_30_DIALOG_SHOWN_KEY] ?: false }
    override val badge50DialogShown: Flow<Boolean> = context.dataStore.data.map { it[BADGE_50_DIALOG_SHOWN_KEY] ?: false }
    override val badge100DialogShown: Flow<Boolean> = context.dataStore.data.map { it[BADGE_100_DIALOG_SHOWN_KEY] ?: false }
    override val badge365DialogShown: Flow<Boolean> = context.dataStore.data.map { it[BADGE_365_DIALOG_SHOWN_KEY] ?: false }

    override suspend fun setBadgeUnlockTime(milestone: Int, time: Long) {
        context.dataStore.edit { preferences ->
            when (milestone) {
                7 -> preferences[BADGE_7_UNLOCK_TIME_KEY] = time
                30 -> preferences[BADGE_30_UNLOCK_TIME_KEY] = time
                50 -> preferences[BADGE_50_UNLOCK_TIME_KEY] = time
                100 -> preferences[BADGE_100_UNLOCK_TIME_KEY] = time
                365 -> preferences[BADGE_365_UNLOCK_TIME_KEY] = time
            }
        }
    }

    override suspend fun setBadgeDialogShown(milestone: Int, shown: Boolean) {
        context.dataStore.edit { preferences ->
            when (milestone) {
                7 -> preferences[BADGE_7_DIALOG_SHOWN_KEY] = shown
                30 -> preferences[BADGE_30_DIALOG_SHOWN_KEY] = shown
                50 -> preferences[BADGE_50_DIALOG_SHOWN_KEY] = shown
                100 -> preferences[BADGE_100_DIALOG_SHOWN_KEY] = shown
                365 -> preferences[BADGE_365_DIALOG_SHOWN_KEY] = shown
            }
        }
    }

    override val authSession: Flow<com.reeltrak.app.core.auth.UserAuthData?> = context.dataStore.data.map { preferences ->
        val idToken = preferences[AUTH_ID_TOKEN_KEY]
        if (idToken != null) {
            com.reeltrak.app.core.auth.UserAuthData(
                idToken = idToken,
                email = preferences[AUTH_EMAIL_KEY] ?: "",
                displayName = preferences[AUTH_DISPLAY_NAME_KEY] ?: "",
                profilePictureUri = preferences[AUTH_PROFILE_PICTURE_URI_KEY] ?: ""
            )
        } else {
            null
        }
    }

    override suspend fun saveAuthSession(userData: com.reeltrak.app.core.auth.UserAuthData) {
        context.dataStore.edit { preferences ->
            preferences[AUTH_ID_TOKEN_KEY] = userData.idToken
            preferences[AUTH_EMAIL_KEY] = userData.email
            preferences[AUTH_DISPLAY_NAME_KEY] = userData.displayName
            preferences[AUTH_PROFILE_PICTURE_URI_KEY] = userData.profilePictureUri
        }
    }

    override suspend fun clearAuthSession() {
        context.dataStore.edit { preferences ->
            preferences.remove(AUTH_ID_TOKEN_KEY)
            preferences.remove(AUTH_EMAIL_KEY)
            preferences.remove(AUTH_DISPLAY_NAME_KEY)
            preferences.remove(AUTH_PROFILE_PICTURE_URI_KEY)
        }
    }

    override suspend fun clearAllPreferences() {
        context.dataStore.edit { it.clear() }
    }

    override val hasMigratedToServer: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[HAS_MIGRATED_TO_SERVER_KEY] ?: false
    }

    override suspend fun setHasMigratedToServer(migrated: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[HAS_MIGRATED_TO_SERVER_KEY] = migrated
        }
    }

    override suspend fun getAllPreferencesSync(): Map<String, Any> {
        val prefs = context.dataStore.data.first()
        val result = mutableMapOf<String, Any>()
        
        for ((key, value) in prefs.asMap()) {
            // Skip auth keys
            if (key.name != AUTH_ID_TOKEN_KEY.name && 
                key.name != AUTH_EMAIL_KEY.name && 
                key.name != AUTH_DISPLAY_NAME_KEY.name && 
                key.name != AUTH_PROFILE_PICTURE_URI_KEY.name) {
                result[key.name] = value
            }
        }
        return result
    }

    override suspend fun restorePreferences(cloudData: Map<String, Any>) {
        context.dataStore.edit { preferences ->
            cloudData.forEach { (keyName, value) ->
                when (keyName) {
                    "daily_intent", "default_daily_limit" -> {
                        preferences[intPreferencesKey(keyName)] = (value as? Number)?.toInt() ?: 0
                    }
                    "first_run_date", "goal_set_date", "last_lock_epoch_day", 
                    "last_limit_change_date", "last_daily_summary_date",
                    "badge_7_unlock_time", "badge_30_unlock_time", "badge_50_unlock_time", 
                    "badge_100_unlock_time", "badge_365_unlock_time" -> {
                        preferences[longPreferencesKey(keyName)] = (value as? Number)?.toLong() ?: 0L
                    }
                    "daily_limit_locked", "strict_mode_locked",
                    "badge_7_dialog_shown", "badge_30_dialog_shown", "badge_50_dialog_shown", 
                    "badge_100_dialog_shown", "badge_365_dialog_shown" -> {
                        preferences[booleanPreferencesKey(keyName)] = value as? Boolean ?: false
                    }
                }
            }
        }
    }
}
