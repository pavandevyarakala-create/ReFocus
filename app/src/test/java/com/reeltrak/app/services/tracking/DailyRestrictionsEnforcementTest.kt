package com.reeltrak.app.services.tracking

import android.content.Context
import androidx.room.Room
import com.reeltrak.app.data.local.database.ReelTrakDatabase
import com.reeltrak.app.data.local.entity.DailyReelData
import com.reeltrak.app.data.local.entity.ReelCountAccountScope
import com.reeltrak.app.data.local.entity.strictModeDailyState
import com.reeltrak.app.data.security.authorization.AppAuthorizationManager
import com.reeltrak.app.domain.models.StrictModeDailyState
import com.reeltrak.app.domain.models.ThemeMode
import com.reeltrak.app.domain.repository.PreferencesRepository
import com.reeltrak.app.services.notifications.ReelNotificationManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Production validation test suite for Daily Limit and Strict Mode daily restrictions.
 *
 * Requirements verified:
 * Daily Limit:
 * - Set or edit daily limit allowed only once per calendar day.
 * - Further edits disabled/rejected until next day.
 * - Automatically allowed one new change at start of new calendar day.
 * - Enforced in underlying business logic state, persisting across restarts.
 * - Rapid/concurrent taps prevented from bypassing restrictions.
 *
 * Strict Mode:
 * - Automatically starts ON at calendar day change.
 * - Turn OFF allowed only once per day.
 * - If turned OFF then manually turned back ON, cannot turn OFF again until next day.
 * - If remaining ON all day, daily OFF allowance is NOT consumed.
 * - Reset OFF allowance at next calendar day.
 * - Enforced in underlying business logic, persisting across restarts.
 * - Rapid/concurrent attempts serialized and race-free.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DailyRestrictionsEnforcementTest {

    private lateinit var context: Context
    private lateinit var database: ReelTrakDatabase
    private lateinit var preferencesRepo: InMemoryPreferencesRepository
    private lateinit var notificationMgr: ReelNotificationManager
    private lateinit var testTimeProvider: MutableDayTimeProvider

    class MutableDayTimeProvider(
        var date: LocalDate = LocalDate.of(2026, 11, 4),
        var time: LocalTime = LocalTime.of(10, 0, 0),
        var elapsed: Long = 100_000L,
        var currentMs: Long = 1762233600000L
    ) : DayTimeProvider {
        override fun elapsedRealtime(): Long = elapsed
        override fun localDate(): LocalDate = date
        override fun localTime(): LocalTime = time
        override fun currentTimeMillis(): Long = currentMs

        fun advanceToNextDay() {
            date = date.plusDays(1)
            time = LocalTime.of(0, 1, 0)
            elapsed += 86_400_000L
            currentMs += 86_400_000L
        }
    }

    class InMemoryPreferencesRepository : PreferencesRepository {
        val prefs = ConcurrentHashMap<String, Any>()

        private val _defaultDailyLimit = MutableStateFlow(-1)
        override val defaultDailyLimit: Flow<Int> = _defaultDailyLimit
        override suspend fun setDefaultDailyLimit(limit: Int) { _defaultDailyLimit.value = limit; prefs["default_limit"] = limit }

        private val _lastLimitChangeDate = MutableStateFlow(0L)
        override val lastLimitChangeDate: Flow<Long> = _lastLimitChangeDate
        override suspend fun setLastLimitChangeDate(date: Long) { _lastLimitChangeDate.value = date; prefs["last_limit_change_date"] = date }

        private val _isDailyLimitLocked = MutableStateFlow(false)
        override val isDailyLimitLocked: Flow<Boolean> = _isDailyLimitLocked
        override suspend fun setDailyLimitLocked(locked: Boolean) { _isDailyLimitLocked.value = locked; prefs["is_daily_limit_locked"] = locked }

        private val _lastLockEpochDay = MutableStateFlow(0L)
        override val lastLockEpochDay: Flow<Long> = _lastLockEpochDay
        override suspend fun setLastLockEpochDay(day: Long) { _lastLockEpochDay.value = day; prefs["last_lock_epoch_day"] = day }

        private val _isStrictModeLocked = MutableStateFlow(false)
        override val isStrictModeLocked: Flow<Boolean> = _isStrictModeLocked
        override suspend fun setStrictModeLocked(locked: Boolean) { _isStrictModeLocked.value = locked; prefs["is_strict_mode_locked"] = locked }

        private val _lastStrictModeOffDate = MutableStateFlow<String?>(null)
        override val lastStrictModeOffDate: Flow<String?> = _lastStrictModeOffDate
        override suspend fun setLastStrictModeOffDate(date: String) { _lastStrictModeOffDate.value = date; prefs["last_strict_mode_off_date"] = date }

        private val _dailyIntent = MutableStateFlow(0)
        override val dailyIntent: Flow<Int> = _dailyIntent
        override suspend fun setDailyIntent(intent: Int) { _dailyIntent.value = intent; prefs["daily_intent"] = intent }

        override val firstRunDate: Flow<Long> = MutableStateFlow(0L)
        override suspend fun setFirstRunDate(date: Long) {}
        override val goalSetDate: Flow<Long> = MutableStateFlow(0L)
        override suspend fun setGoalSetDate(date: Long) {}
        override val notificationsEnabled: Flow<Boolean> = MutableStateFlow(true)
        override suspend fun setNotificationsEnabled(enabled: Boolean) {}
        override val lastDailySummaryDate: Flow<Long> = MutableStateFlow(0L)
        override suspend fun setLastDailySummaryDate(date: Long) {}
        override val counterThemeMode: Flow<ThemeMode> = MutableStateFlow(ThemeMode.DARK)
        override suspend fun setCounterThemeMode(mode: ThemeMode) {}
        private val _appThemeMode = MutableStateFlow(ThemeMode.LIGHT)
        override val appThemeMode: Flow<ThemeMode> = _appThemeMode
        override suspend fun setAppThemeMode(mode: ThemeMode) { _appThemeMode.value = mode }
        private val _isInstagramTrackingEnabled = MutableStateFlow(true)
        override val isInstagramTrackingEnabled: Flow<Boolean> = _isInstagramTrackingEnabled
        override suspend fun setInstagramTrackingEnabled(enabled: Boolean) { _isInstagramTrackingEnabled.value = enabled }
        private val _isYouTubeTrackingEnabled = MutableStateFlow(true)
        override val isYouTubeTrackingEnabled: Flow<Boolean> = _isYouTubeTrackingEnabled
        override suspend fun setYouTubeTrackingEnabled(enabled: Boolean) { _isYouTubeTrackingEnabled.value = enabled }
        override val authSession: Flow<com.reeltrak.app.core.auth.UserAuthData?> = MutableStateFlow(null)
        override suspend fun saveAuthSession(userData: com.reeltrak.app.core.auth.UserAuthData) {}
        override suspend fun clearAuthSession() {}
        override suspend fun clearAllPreferences() { prefs.clear() }
        override val isFreeMode: Flow<Boolean> = MutableStateFlow(false)
        override suspend fun setFreeMode(enabled: Boolean) {}
        override val hasMigratedToServer: Flow<Boolean> = MutableStateFlow(false)
        override suspend fun setHasMigratedToServer(migrated: Boolean) {}
        override val hasCompletedTour: Flow<Boolean> = MutableStateFlow(true)
        override suspend fun setHasCompletedTour(completed: Boolean) {}
        override val lastBackupTime: Flow<Long> = MutableStateFlow(0L)
        override suspend fun setLastBackupTime(time: Long) {}
        override val badge7UnlockTime: Flow<Long> = MutableStateFlow(0L)
        override val badge30UnlockTime: Flow<Long> = MutableStateFlow(0L)
        override val badge50UnlockTime: Flow<Long> = MutableStateFlow(0L)
        override val badge100UnlockTime: Flow<Long> = MutableStateFlow(0L)
        override val badge365UnlockTime: Flow<Long> = MutableStateFlow(0L)
        override val badge7DialogShown: Flow<Boolean> = MutableStateFlow(false)
        override val badge30DialogShown: Flow<Boolean> = MutableStateFlow(false)
        override val badge50DialogShown: Flow<Boolean> = MutableStateFlow(false)
        override val badge100DialogShown: Flow<Boolean> = MutableStateFlow(false)
        override val badge365DialogShown: Flow<Boolean> = MutableStateFlow(false)
        override suspend fun setBadgeUnlockTime(milestone: Int, time: Long) {}
        override suspend fun setBadgeDialogShown(milestone: Int, shown: Boolean) {}
        override suspend fun getAllPreferencesSync(): Map<String, Any> = prefs
        override suspend fun restorePreferences(cloudData: Map<String, Any>) { prefs.putAll(cloudData) }
    }

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, ReelTrakDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        preferencesRepo = InMemoryPreferencesRepository()
        notificationMgr = ReelNotificationManager(context, preferencesRepo)
        testTimeProvider = MutableDayTimeProvider()
        ReelCountAccountScope.setCurrent("local")
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun createDayManager(
        isEntitled: Boolean = true
    ): DayManager {
        val dm = DayManager(
            context = context,
            dailyReelDataDao = database.dailyReelDataDao,
            activeAccountScopeDao = database.activeAccountScopeDao,
            preferencesRepository = preferencesRepo,
            notificationManager = notificationMgr,
            syncManager = null,
            appAuthorizationManager = null
        )
        dm.timeProvider = testTimeProvider
        dm.allowUnconfiguredAuthForTesting = true
        dm.isEntitledOverrideForTesting = isEntitled

        // Deterministically await initial load
        withTimeout(3000L) {
            dm.currentDayStats.filterNotNull().first()
        }
        return dm
    }

    // =========================================================================
    // 1. DAILY LIMIT RESTRICTION TESTS
    // =========================================================================

    @Test
    fun testDailyLimit_singleChangePerDay_locksUntilNextDay() = runBlocking {
        val dm = createDayManager()
        val initialAllowed = dm.canSetDailyGoalToday()
        assertTrue("Initially allowed to set daily limit today", initialAllowed)

        val success = dm.setDailyGoalSuspend(150)
        assertTrue("First limit change on Day 1 must succeed", success)

        val stats = dm.currentDayStats.value
        assertNotNull(stats)
        assertEquals(150, stats?.dailyGoal)
        assertTrue("Limit must be locked after first change", stats?.isLimitLocked == true)
        assertFalse("Further changes must be disallowed today", dm.canSetDailyGoalToday())
    }

    @Test
    fun testDailyLimit_secondAttemptSameDay_rejectedInBusinessLogic() = runBlocking {
        val dm = createDayManager()
        val firstSuccess = dm.setDailyGoalSuspend(150)
        assertTrue(firstSuccess)

        // Second attempt with different goal on same day
        val secondSuccess = dm.setDailyGoalSuspend(300)
        assertFalse("Second limit edit on same day must be rejected", secondSuccess)

        // Verify state is unchanged
        val stats = dm.currentDayStats.value
        assertEquals("Goal must not be updated to 300", 150, stats?.dailyGoal)
        assertTrue("Limit must remain locked", stats?.isLimitLocked == true)
    }

    @Test
    fun testDailyLimit_midnightRollover_unlocksForNewDay() = runBlocking {
        val dm = createDayManager()
        dm.setDailyGoalSuspend(150)
        assertTrue("Day 1 is locked", dm.currentDayStats.value?.isLimitLocked == true)

        // Advance to Day 2
        testTimeProvider.advanceToNextDay()
        dm.checkDayTransition(isDateChanged = true)

        // Await rollover processing
        val day2Stats = withTimeout(3000L) {
            dm.currentDayStats.filter { it?.dateString == testTimeProvider.date.toString() }.first()
        }
        assertNotNull(day2Stats)
        assertEquals(testTimeProvider.date.toString(), day2Stats?.dateString)
        assertEquals(150, day2Stats?.dailyGoal) // Carried over
        assertFalse("Limit lock must reset to false on Day 2", day2Stats?.isLimitLocked ?: true)
        assertTrue("Must allow one new limit change on Day 2", dm.canSetDailyGoalToday())

        // Successfully set Day 2 limit
        val day2Success = dm.setDailyGoalSuspend(250)
        assertTrue("Day 2 limit change must succeed", day2Success)
        assertEquals(250, dm.currentDayStats.value?.dailyGoal)
        assertTrue("Day 2 limit must now be locked", dm.currentDayStats.value?.isLimitLocked == true)
    }

    @Test
    fun testDailyLimit_midnightRollover_doesNotResetToDefaultValue_carriesOverPreviousDayLimit() = runBlocking {
        // Configure an initial pre-existing default limit of 500 in preferences
        preferencesRepo.setDefaultDailyLimit(500)
        val dm = createDayManager()

        // User sets custom daily limit of 160 on Day 1
        val setDay1 = dm.setDailyGoalSuspend(160)
        assertTrue("Day 1 limit set to 160 succeeds", setDay1)
        assertEquals(160, dm.currentDayStats.value?.dailyGoal)
        assertTrue("Day 1 is locked after edit", dm.currentDayStats.value?.isLimitLocked == true)

        // Midnight rollover to Day 2
        testTimeProvider.advanceToNextDay()
        dm.checkDayTransition(isDateChanged = true)

        val day2Stats = withTimeout(3000L) {
            dm.currentDayStats.filter { it?.dateString == testTimeProvider.date.toString() }.first()
        }
        assertNotNull(day2Stats)
        // Must carry over Day 1's saved limit (160), NOT reset to default (500)
        assertEquals("Starting limit on Day 2 must carry over previous day's limit", 160, day2Stats?.dailyGoal)
        assertFalse("Day 2 edit lock must be reset to unlocked", day2Stats?.isLimitLocked ?: true)
        assertTrue("Day 2 daily edit allowance must be reset and available", dm.canSetDailyGoalToday())
    }

    @Test
    fun testDailyLimit_carriedOverLimitEditableOnlyOnce_locksUntilNextDay() = runBlocking {
        val dm = createDayManager()
        dm.setDailyGoalSuspend(150)
        assertTrue(dm.currentDayStats.value?.isLimitLocked == true)

        // Rollover to Day 2
        testTimeProvider.advanceToNextDay()
        dm.checkDayTransition(isDateChanged = true)

        withTimeout(3000L) {
            dm.currentDayStats.filter { it?.dateString == testTimeProvider.date.toString() }.first()
        }
        assertEquals(150, dm.currentDayStats.value?.dailyGoal)
        assertTrue("Carried-over limit is editable initially on Day 2", dm.canSetDailyGoalToday())

        // Edit carried-over limit once on Day 2
        val editSuccess = dm.setDailyGoalSuspend(220)
        assertTrue("Editing carried-over limit once on Day 2 must succeed", editSuccess)
        assertEquals(220, dm.currentDayStats.value?.dailyGoal)
        assertTrue("Day 2 is locked after one edit", dm.currentDayStats.value?.isLimitLocked == true)
        assertFalse("Further edits on Day 2 must be disallowed", dm.canSetDailyGoalToday())

        // Second edit attempt on Day 2 must be rejected
        val secondEdit = dm.setDailyGoalSuspend(350)
        assertFalse("Second edit attempt on Day 2 must be rejected", secondEdit)
        assertEquals("Goal remains 220", 220, dm.currentDayStats.value?.dailyGoal)

        // Rollover to Day 3 carries forward the edited limit (220) and resets allowance
        testTimeProvider.advanceToNextDay()
        dm.checkDayTransition(isDateChanged = true)

        val day3Stats = withTimeout(3000L) {
            dm.currentDayStats.filter { it?.dateString == testTimeProvider.date.toString() }.first()
        }
        assertEquals("Day 3 starting limit carries forward most recently saved limit (220)", 220, day3Stats?.dailyGoal)
        assertFalse("Day 3 edit lock is reset", day3Stats?.isLimitLocked ?: true)
        assertTrue("Day 3 allows one new edit", dm.canSetDailyGoalToday())
    }

    @Test
    fun testDailyLimit_dayRolloverWithoutEdit_continuesCarryingForwardMostRecentlySavedLimit() = runBlocking {
        val dm = createDayManager()
        dm.setDailyGoalSuspend(175)

        // Day 2 rollover: no edit made by user
        testTimeProvider.advanceToNextDay()
        dm.checkDayTransition(isDateChanged = true)
        val day2Stats = withTimeout(3000L) {
            dm.currentDayStats.filter { it?.dateString == testTimeProvider.date.toString() }.first()
        }
        assertEquals(175, day2Stats?.dailyGoal)
        assertFalse("Day 2 is unlocked", day2Stats?.isLimitLocked ?: true)

        // Day 3 rollover: still no edit made by user
        testTimeProvider.advanceToNextDay()
        dm.checkDayTransition(isDateChanged = true)
        val day3Stats = withTimeout(3000L) {
            dm.currentDayStats.filter { it?.dateString == testTimeProvider.date.toString() }.first()
        }
        assertEquals("Day 3 carries forward 175 without edit", 175, day3Stats?.dailyGoal)
        assertFalse("Day 3 is unlocked", day3Stats?.isLimitLocked ?: true)
        assertTrue("Day 3 edit allowance is available", dm.canSetDailyGoalToday())

        // User edits on Day 3
        val editDay3 = dm.setDailyGoalSuspend(320)
        assertTrue("Edit on Day 3 succeeds", editDay3)
        assertEquals(320, dm.currentDayStats.value?.dailyGoal)
        assertTrue("Day 3 is locked after edit", dm.currentDayStats.value?.isLimitLocked == true)

        // Day 4 rollover: carries forward 320 and resets allowance
        testTimeProvider.advanceToNextDay()
        dm.checkDayTransition(isDateChanged = true)
        val day4Stats = withTimeout(3000L) {
            dm.currentDayStats.filter { it?.dateString == testTimeProvider.date.toString() }.first()
        }
        assertEquals("Day 4 carries forward newly saved limit 320", 320, day4Stats?.dailyGoal)
        assertFalse("Day 4 is unlocked", day4Stats?.isLimitLocked ?: true)
        assertTrue("Day 4 edit allowance is reset", dm.canSetDailyGoalToday())
    }

    @Test
    fun testDailyLimit_multiDayRolloverGap_carriesForwardMostRecentlySavedLimit() = runBlocking {
        val dm = createDayManager()
        dm.setDailyGoalSuspend(190)
        assertTrue(dm.currentDayStats.value?.isLimitLocked == true)

        // Simulate 3 days passing without app activity
        testTimeProvider.advanceToNextDay() // Day 2
        testTimeProvider.advanceToNextDay() // Day 3
        testTimeProvider.advanceToNextDay() // Day 4
        dm.checkDayTransition(isDateChanged = true)

        val day4Stats = withTimeout(3000L) {
            dm.currentDayStats.filter { it?.dateString == testTimeProvider.date.toString() }.first()
        }
        assertEquals("Day 4 carries forward 190 across multi-day gap", 190, day4Stats?.dailyGoal)
        assertFalse("Day 4 edit lock is reset to unlocked", day4Stats?.isLimitLocked ?: true)
        assertTrue("Day 4 edit allowance is reset", dm.canSetDailyGoalToday())

        // Can edit Day 4 limit once
        val editDay4 = dm.setDailyGoalSuspend(240)
        assertTrue("Day 4 edit succeeds", editDay4)
        assertEquals(240, dm.currentDayStats.value?.dailyGoal)
        assertTrue("Day 4 is locked after edit", dm.currentDayStats.value?.isLimitLocked == true)
        assertFalse("Further edits on Day 4 disallowed", dm.canSetDailyGoalToday())
    }

    @Test
    fun testDailyLimit_persistenceAcrossRestart_preservesLockedState() = runBlocking {
        val dm1 = createDayManager()
        dm1.setDailyGoalSuspend(175)
        assertTrue(dm1.currentDayStats.value?.isLimitLocked == true)

        // Simulate process death / restart: create new DayManager instance with same DB & preferences
        val dm2 = createDayManager()

        val stats = dm2.currentDayStats.value
        assertNotNull("Day 1 stats loaded", stats)
        assertEquals(175, stats?.dailyGoal)
        assertTrue("isLimitLocked must persist across restart", stats?.isLimitLocked == true)

        // Attempting to change on restarted instance must still fail
        val editAfterRestart = dm2.setDailyGoalSuspend(400)
        assertFalse("Editing after restart on same day must be rejected", editAfterRestart)
        assertEquals(175, dm2.currentDayStats.value?.dailyGoal)
    }

    @Test
    fun testDailyLimit_concurrentAttempts_exactlyOneSucceeds() = runBlocking {
        val dm = createDayManager()
        val successCount = AtomicInteger(0)
        val failureCount = AtomicInteger(0)

        // Launch 10 concurrent attempts to set different goals
        val jobs = (1..10).map { goal ->
            launch(Dispatchers.Default) {
                val result = dm.setDailyGoalSuspend(goal * 50)
                if (result) successCount.incrementAndGet() else failureCount.incrementAndGet()
            }
        }
        jobs.joinAll()

        assertEquals("Exactly 1 concurrent edit must succeed", 1, successCount.get())
        assertEquals("Remaining 9 concurrent edits must be rejected", 9, failureCount.get())
        assertTrue("Limit must be locked", dm.currentDayStats.value?.isLimitLocked == true)
    }

    @Test
    fun testDailyLimit_unentitledUser_rejected() = runBlocking {
        val dm = createDayManager(isEntitled = false)
        val success = dm.setDailyGoalSuspend(200)
        assertFalse("Unentitled user must be rejected from setting daily goal", success)
    }

    // =========================================================================
    // 2. STRICT MODE RESTRICTION TESTS
    // =========================================================================

    @Test
    fun testStrictMode_startsAutoOnAndUnlocked_onNewCalendarDay() = runBlocking {
        val dm = createDayManager()

        val stats = dm.currentDayStats.value
        assertNotNull(stats)
        assertTrue("Strict Mode must automatically start ON", stats?.isStrictModeOn == true)
        assertFalse("Strict Mode must start unlocked", stats?.isStrictModeLocked ?: true)
        assertEquals(StrictModeDailyState.ACTIVE_INTERACTIVE, stats?.strictModeDailyState)
        assertTrue("User can turn it off once today", dm.canDisableStrictModeToday())
    }

    @Test
    fun testStrictMode_turnOffOnce_disablesForToday() = runBlocking {
        val dm = createDayManager()

        val success = dm.disableStrictModeForTodaySuspend()
        assertTrue("First turn-off must succeed", success)

        val stats = dm.currentDayStats.value
        assertFalse("Strict Mode must be OFF", stats?.isStrictModeOn ?: true)
        assertFalse("Strict Mode is not locked (it is disabled)", stats?.isStrictModeLocked ?: true)
        assertEquals(StrictModeDailyState.DISABLED_TODAY, stats?.strictModeDailyState)
        assertFalse("Cannot turn off again today", dm.canDisableStrictModeToday())
    }

    @Test
    fun testStrictMode_secondTurnOffAttemptSameDay_rejected() = runBlocking {
        val dm = createDayManager()

        val firstSuccess = dm.disableStrictModeForTodaySuspend()
        assertTrue(firstSuccess)

        val secondSuccess = dm.disableStrictModeForTodaySuspend()
        assertFalse("Second turn-off on same day must be rejected", secondSuccess)
    }

    @Test
    fun testStrictMode_turnOffThenOn_locksUntilNextDay_cannotTurnOffAgain() = runBlocking {
        val dm = createDayManager()

        // 1. Turn OFF
        val offSuccess = dm.disableStrictModeForTodaySuspend()
        assertTrue("Disable must succeed", offSuccess)
        assertEquals(StrictModeDailyState.DISABLED_TODAY, dm.currentDayStats.value?.strictModeDailyState)

        // 2. Turn back ON manually
        val onSuccess = dm.reactivateStrictModeSuspend()
        assertTrue("Reactivation must succeed", onSuccess)

        // 3. Must be ON and LOCKED
        val stats = dm.currentDayStats.value
        assertTrue("Strict Mode must be ON", stats?.isStrictModeOn == true)
        assertTrue("Strict Mode must be LOCKED for remainder of today", stats?.isStrictModeLocked == true)
        assertEquals(StrictModeDailyState.REACTIVATED_LOCKED, stats?.strictModeDailyState)
        assertFalse("Cannot turn off when locked", dm.canDisableStrictModeToday())

        // 4. Attempt to turn OFF again must be rejected
        val offAgainAttempt = dm.disableStrictModeForTodaySuspend()
        assertFalse("Turn OFF when reactivated and locked must be rejected", offAgainAttempt)
        assertTrue("Strict Mode must remain ON", dm.currentDayStats.value?.isStrictModeOn == true)
        assertTrue("Strict Mode must remain LOCKED", dm.currentDayStats.value?.isStrictModeLocked == true)
    }

    @Test
    fun testStrictMode_remainsOnAllDay_allowanceNotConsumed_unlockableNextDay() = runBlocking {
        val dm = createDayManager()

        // User leaves Strict Mode ON all day (never calls disable)
        assertEquals(StrictModeDailyState.ACTIVE_INTERACTIVE, dm.currentDayStats.value?.strictModeDailyState)

        // Midnight rollover to Day 2
        testTimeProvider.advanceToNextDay()
        dm.checkDayTransition(isDateChanged = true)

        val day2Stats = withTimeout(3000L) {
            dm.currentDayStats.filter { it?.dateString == testTimeProvider.date.toString() }.first()
        }
        assertTrue("Strict Mode remains ON on Day 2", day2Stats?.isStrictModeOn == true)
        assertFalse("Strict Mode unlocked on Day 2", day2Stats?.isStrictModeLocked ?: true)
        assertEquals(StrictModeDailyState.ACTIVE_INTERACTIVE, day2Stats?.strictModeDailyState)
        assertTrue("User has their daily OFF allowance on Day 2", dm.canDisableStrictModeToday())
    }

    @Test
    fun testStrictMode_midnightRollover_fromDisabledToday_resetsToAutoOnAndUnlocked() = runBlocking {
        val dm = createDayManager()

        // Day 1: User turns Strict Mode OFF and leaves it OFF
        dm.disableStrictModeForTodaySuspend()
        assertEquals(StrictModeDailyState.DISABLED_TODAY, dm.currentDayStats.value?.strictModeDailyState)

        // Midnight rollover to Day 2
        testTimeProvider.advanceToNextDay()
        dm.checkDayTransition(isDateChanged = true)

        val day2Stats = withTimeout(3000L) {
            dm.currentDayStats.filter { it?.dateString == testTimeProvider.date.toString() }.first()
        }
        assertTrue("Strict Mode automatically becomes ON on Day 2", day2Stats?.isStrictModeOn == true)
        assertFalse("Strict Mode unlocked on Day 2", day2Stats?.isStrictModeLocked ?: true)
        assertEquals(StrictModeDailyState.ACTIVE_INTERACTIVE, day2Stats?.strictModeDailyState)
        assertTrue("Day 2 OFF allowance is reset and available", dm.canDisableStrictModeToday())
    }

    @Test
    fun testStrictMode_midnightRollover_fromReactivatedLocked_resetsToAutoOnAndUnlocked() = runBlocking {
        val dm = createDayManager()

        // Day 1: User turns OFF then ON -> REACTIVATED_LOCKED
        dm.disableStrictModeForTodaySuspend()
        dm.reactivateStrictModeSuspend()
        assertEquals(StrictModeDailyState.REACTIVATED_LOCKED, dm.currentDayStats.value?.strictModeDailyState)

        // Midnight rollover to Day 2
        testTimeProvider.advanceToNextDay()
        dm.checkDayTransition(isDateChanged = true)

        val day2Stats = withTimeout(3000L) {
            dm.currentDayStats.filter { it?.dateString == testTimeProvider.date.toString() }.first()
        }
        assertTrue("Strict Mode automatically becomes ON on Day 2", day2Stats?.isStrictModeOn == true)
        assertFalse("Strict Mode lock resets on Day 2", day2Stats?.isStrictModeLocked ?: true)
        assertEquals(StrictModeDailyState.ACTIVE_INTERACTIVE, day2Stats?.strictModeDailyState)
        assertTrue("Day 2 OFF allowance is reset and available", dm.canDisableStrictModeToday())
    }

    @Test
    fun testStrictMode_persistenceAcrossRestart_preservesReactivatedLockedState() = runBlocking {
        val dm1 = createDayManager()

        dm1.disableStrictModeForTodaySuspend()
        dm1.reactivateStrictModeSuspend()
        assertEquals(StrictModeDailyState.REACTIVATED_LOCKED, dm1.currentDayStats.value?.strictModeDailyState)

        // Process death and restart
        val dm2 = createDayManager()

        val stats = dm2.currentDayStats.value
        assertTrue("isStrictModeOn must be true after restart", stats?.isStrictModeOn == true)
        assertTrue("isStrictModeLocked must be true after restart", stats?.isStrictModeLocked == true)
        assertEquals(StrictModeDailyState.REACTIVATED_LOCKED, stats?.strictModeDailyState)

        val turnOffAfterRestart = dm2.disableStrictModeForTodaySuspend()
        assertFalse("Turn-off after restart must remain rejected", turnOffAfterRestart)
    }

    @Test
    fun testStrictMode_concurrentDisableAttempts_exactlyOneSucceeds() = runBlocking {
        val dm = createDayManager()

        val successCount = AtomicInteger(0)
        val failureCount = AtomicInteger(0)

        val jobs = (1..10).map {
            launch(Dispatchers.Default) {
                val success = dm.disableStrictModeForTodaySuspend()
                if (success) successCount.incrementAndGet() else failureCount.incrementAndGet()
            }
        }
        jobs.joinAll()

        assertEquals("Exactly 1 concurrent turn-off must succeed", 1, successCount.get())
        assertEquals("Remaining 9 concurrent turn-offs must be rejected", 9, failureCount.get())
        assertEquals(StrictModeDailyState.DISABLED_TODAY, dm.currentDayStats.value?.strictModeDailyState)
    }

    @Test
    fun testStrictMode_unentitledUser_rejected() = runBlocking {
        val dm = createDayManager(isEntitled = false)

        val offSuccess = dm.disableStrictModeForTodaySuspend()
        assertFalse("Unentitled user cannot turn off strict mode", offSuccess)

        val reactivateSuccess = dm.reactivateStrictModeSuspend()
        assertFalse("Unentitled user cannot reactivate strict mode", reactivateSuccess)
    }
}
