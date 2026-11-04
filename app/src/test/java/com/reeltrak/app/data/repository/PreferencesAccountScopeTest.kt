package com.reeltrak.app.data.repository

import android.content.Context
import com.reeltrak.app.data.local.entity.ReelCountAccountScope
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreferencesAccountScopeTest {
    @Test
    fun `daily limit is isolated by active account and follows switching`() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        val repository = PreferencesRepositoryImpl(context)
        val suffix = System.nanoTime().toString()
        val scopeA = "scope-a-$suffix"
        val scopeB = "scope-b-$suffix"

        try {
            ReelCountAccountScope.setCurrent(scopeA)
            repository.setDefaultDailyLimit(45)
            repository.setDailyLimitLocked(true)
            repository.setStrictModeLocked(true)
            repository.setLastLimitChangeDate(123L)
            assertEquals(45, repository.defaultDailyLimit.first())

            ReelCountAccountScope.setCurrent(scopeB)
            assertEquals(-1, repository.defaultDailyLimit.first())
            assertEquals(false, repository.isDailyLimitLocked.first())
            assertEquals(false, repository.isStrictModeLocked.first())
            assertEquals(0L, repository.lastLimitChangeDate.first())
            repository.setDefaultDailyLimit(12)
            assertEquals(12, repository.defaultDailyLimit.first())

            ReelCountAccountScope.setCurrent(scopeA)
            assertEquals(45, repository.defaultDailyLimit.first())
            assertEquals(true, repository.isDailyLimitLocked.first())
            assertEquals(true, repository.isStrictModeLocked.first())
            assertEquals(123L, repository.lastLimitChangeDate.first())

            // Logout clears session preferences but must preserve account-owned settings.
            ReelCountAccountScope.setCurrent("local")
            repository.clearAllPreferences()
            ReelCountAccountScope.setCurrent(scopeA)
            assertEquals(45, repository.defaultDailyLimit.first())
            assertEquals(true, repository.isDailyLimitLocked.first())
            assertEquals(true, repository.isStrictModeLocked.first())
            assertEquals(123L, repository.lastLimitChangeDate.first())
            val exported = repository.getAllPreferencesSync()
            assertEquals(45, exported["default_daily_limit"])
            assertEquals(false, exported.keys.any { it.startsWith("default_daily_limit_") })

            ReelCountAccountScope.setCurrent(scopeB)
            repository.restorePreferences(mapOf("default_daily_limit" to 20))
            repository.setDefaultDailyLimitForScope(scopeA, 52)
            assertEquals(20, repository.defaultDailyLimit.first())
            ReelCountAccountScope.setCurrent(scopeA)
            assertEquals(52, repository.defaultDailyLimit.first())
        } finally {
            ReelCountAccountScope.setCurrent("local")
        }
    }

    @Test
    fun `legacy unscoped preferences fall back properly when account scoped keys are absent`() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        val repository = PreferencesRepositoryImpl(context)
        val legacyScope = "test-legacy-" + System.nanoTime()

        try {
            ReelCountAccountScope.setCurrent(legacyScope)
            // Seed legacy unscoped preferences directly in DataStore
            context.dataStore.edit { prefs ->
                prefs[androidx.datastore.preferences.core.intPreferencesKey("default_daily_limit")] = 40
                prefs[androidx.datastore.preferences.core.booleanPreferencesKey("daily_limit_locked")] = true
                prefs[androidx.datastore.preferences.core.booleanPreferencesKey("strict_mode_locked")] = true
                prefs[androidx.datastore.preferences.core.stringPreferencesKey("last_strict_mode_off_date")] = "2026-10-25"
                prefs[androidx.datastore.preferences.core.longPreferencesKey("last_lock_epoch_day")] = 100L
                prefs[androidx.datastore.preferences.core.longPreferencesKey("last_limit_change_date")] = 200L
            }

            // Verify that reading from repository returns the legacy values when scoped key is absent
            assertEquals(40, repository.defaultDailyLimit.first())
            assertEquals(true, repository.isDailyLimitLocked.first())
            assertEquals(true, repository.isStrictModeLocked.first())
            assertEquals("2026-10-25", repository.lastStrictModeOffDate.first())
            assertEquals(100L, repository.lastLockEpochDay.first())
            assertEquals(200L, repository.lastLimitChangeDate.first())

            // Writing a new scoped value must supersede the legacy fallback
            repository.setDefaultDailyLimit(75)
            assertEquals(75, repository.defaultDailyLimit.first())

            // Switching to another scope without scoped key still gets legacy fallback
            val otherScope = "test-other-" + System.nanoTime()
            ReelCountAccountScope.setCurrent(otherScope)
            assertEquals(40, repository.defaultDailyLimit.first())
        } finally {
            ReelCountAccountScope.setCurrent("local")
        }
    }
}
