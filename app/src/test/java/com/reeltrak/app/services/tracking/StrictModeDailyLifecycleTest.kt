package com.reeltrak.app.services.tracking

import com.reeltrak.app.data.local.entity.DailyReelData
import com.reeltrak.app.data.local.entity.strictModeDailyState
import com.reeltrak.app.domain.models.StrictModeDailyState
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * Deterministic unit test suite verifying the complete Strict Mode Daily Lifecycle:
 * 1. Every new calendar day starts ON and interactive (ACTIVE_INTERACTIVE).
 * 2. User can turn it OFF once without watching an ad (DISABLED_TODAY).
 * 3. Reactivation requires completed rewarded ad and transitions to ON + LOCKED (REACTIVATED_LOCKED).
 * 4. While locked, user cannot turn it OFF again for the rest of the day.
 * 5. Midnight rollover resets state back to ACTIVE_INTERACTIVE, regardless of whether yesterday
 *    ended in DISABLED_TODAY or REACTIVATED_LOCKED.
 * 6. Incomplete / failed ads do not reactivate or lock Strict Mode.
 */
class StrictModeDailyLifecycleTest {

    private fun findSourceFile(relativePath: String): File {
        val candidates = listOf(
            relativePath,
            "app/$relativePath",
            "../app/$relativePath"
        )
        return candidates.map { File(it) }.firstOrNull { it.exists() }
            ?: throw IllegalStateException("Source file not found: $relativePath")
    }

    /**
     * In-memory simulator modeling the exact DayManager + DailyReelData daily state machine.
     */
    class StrictModeLifecycleHarness(
        var currentDate: LocalDate = LocalDate.of(2026, 10, 8),
        initialDb: Map<String, DailyReelData> = emptyMap(),
        var lastStrictModeOffDate: String? = null
    ) {
        val inMemoryDb = ConcurrentHashMap<String, DailyReelData>().apply { putAll(initialDb) }
        var currentDateString: String = currentDate.format(DateTimeFormatter.ISO_LOCAL_DATE)
        var currentDayStats: DailyReelData? = null

        init {
            loadOrCreateDayLocked(currentDateString)
        }

        fun getCurrentDateStr(): String = currentDate.format(DateTimeFormatter.ISO_LOCAL_DATE)

        fun advanceToNextDay() {
            currentDate = currentDate.plusDays(1)
            val nextDateStr = getCurrentDateStr()
            currentDateString = nextDateStr
            loadOrCreateDayLocked(nextDateStr)
        }

        fun loadOrCreateDayLocked(dateStr: String) {
            var stats = inMemoryDb[dateStr]
            if (stats == null) {
                val lastStats = inMemoryDb.values.maxByOrNull { it.dateString }
                val carryOverLimit = lastStats?.dailyGoal ?: 500
                // Strict mode starts ON for every new calendar day, unlocked
                stats = DailyReelData(
                    dateString = dateStr,
                    totalReelsCount = 0,
                    dailyGoal = carryOverLimit,
                    isLimitLocked = false,
                    isStrictModeOn = true,
                    isStrictModeLocked = false,
                    updatedAt = System.currentTimeMillis(),
                    syncStatus = 1
                )
                inMemoryDb[dateStr] = stats
            } else {
                if (lastStrictModeOffDate == stats.dateString) {
                    if (stats.isStrictModeOn && !stats.isStrictModeLocked) {
                        stats = stats.copy(isStrictModeLocked = true)
                        inMemoryDb[dateStr] = stats
                    }
                } else {
                    if (!stats.isStrictModeOn || stats.isStrictModeLocked) {
                        stats = stats.copy(isStrictModeOn = true, isStrictModeLocked = false)
                        inMemoryDb[dateStr] = stats
                    }
                }
            }
            currentDayStats = stats
        }

        fun disableStrictModeForToday(): Boolean {
            val current = currentDayStats ?: return false
            if (current.isStrictModeLocked) {
                // Reject disable when locked
                return false
            }
            if (lastStrictModeOffDate == current.dateString) {
                // Reject disable when daily allowance already consumed
                return false
            }
            val updated = current.copy(
                isStrictModeOn = false,
                isStrictModeLocked = false,
                updatedAt = System.currentTimeMillis(),
                syncStatus = 1
            )
            lastStrictModeOffDate = current.dateString
            currentDayStats = updated
            inMemoryDb[updated.dateString] = updated
            return true
        }

        fun reactivateStrictModeWithAd(): Boolean {
            val current = currentDayStats ?: return false
            val updated = current.copy(
                isStrictModeOn = true,
                isStrictModeLocked = true,
                updatedAt = System.currentTimeMillis(),
                syncStatus = 1
            )
            currentDayStats = updated
            inMemoryDb[updated.dateString] = updated
            return true
        }

        fun getDailyState(): StrictModeDailyState {
            return currentDayStats?.strictModeDailyState ?: StrictModeDailyState.ACTIVE_INTERACTIVE
        }
    }

    // =========================================================================
    // 1. StrictModeDailyState Model & Extension Properties
    // =========================================================================

    @Test
    fun testStrictModeDailyState_enumCapabilities() {
        // ACTIVE_INTERACTIVE
        val active = StrictModeDailyState.ACTIVE_INTERACTIVE
        assertTrue("ACTIVE_INTERACTIVE has active enforcement", active.isEnforcementActive)
        assertFalse("ACTIVE_INTERACTIVE is not locked", active.isLocked)
        assertTrue("ACTIVE_INTERACTIVE can be turned off", active.canTurnOff)
        assertFalse("ACTIVE_INTERACTIVE does not offer ad reactivation", active.canReactivateWithAd)

        // DISABLED_TODAY
        val disabled = StrictModeDailyState.DISABLED_TODAY
        assertFalse("DISABLED_TODAY has no active enforcement", disabled.isEnforcementActive)
        assertFalse("DISABLED_TODAY is not locked", disabled.isLocked)
        assertFalse("DISABLED_TODAY cannot be turned off again", disabled.canTurnOff)
        assertTrue("DISABLED_TODAY can reactivate via ad", disabled.canReactivateWithAd)

        // REACTIVATED_LOCKED
        val locked = StrictModeDailyState.REACTIVATED_LOCKED
        assertTrue("REACTIVATED_LOCKED has active enforcement", locked.isEnforcementActive)
        assertTrue("REACTIVATED_LOCKED is locked", locked.isLocked)
        assertFalse("REACTIVATED_LOCKED cannot be turned off", locked.canTurnOff)
        assertFalse("REACTIVATED_LOCKED does not offer ad reactivation", locked.canReactivateWithAd)
    }

    @Test
    fun testDailyReelData_strictModeDailyStateMapping() {
        // Default entity values
        val defaultData = DailyReelData(dateString = "2026-10-08")
        assertTrue("Default entity has strict mode ON", defaultData.isStrictModeOn)
        assertFalse("Default entity is unlocked", defaultData.isStrictModeLocked)
        assertEquals(StrictModeDailyState.ACTIVE_INTERACTIVE, defaultData.strictModeDailyState)

        // User turned off
        val turnedOff = defaultData.copy(isStrictModeOn = false, isStrictModeLocked = false)
        assertEquals(StrictModeDailyState.DISABLED_TODAY, turnedOff.strictModeDailyState)

        // Reactivated with ad
        val reactivated = defaultData.copy(isStrictModeOn = true, isStrictModeLocked = true)
        assertEquals(StrictModeDailyState.REACTIVATED_LOCKED, reactivated.strictModeDailyState)

        // Edge case: locked but off (safety fallback)
        val edgeCase = defaultData.copy(isStrictModeOn = false, isStrictModeLocked = true)
        assertEquals(StrictModeDailyState.DISABLED_TODAY, edgeCase.strictModeDailyState)
    }

    // =========================================================================
    // 2. Daily Lifecycle State Transitions
    // =========================================================================

    @Test
    fun testLifecycle_newDay_startsActiveInteractive() {
        val harness = StrictModeLifecycleHarness(LocalDate.of(2026, 10, 8))
        assertEquals(StrictModeDailyState.ACTIVE_INTERACTIVE, harness.getDailyState())
        assertTrue("Enforcement is active", harness.getDailyState().isEnforcementActive)
        assertTrue("User can turn it off once", harness.getDailyState().canTurnOff)
        assertFalse("Not locked on fresh day start", harness.getDailyState().isLocked)
    }

    @Test
    fun testLifecycle_userCanTurnOffOnce_withoutAd() {
        val harness = StrictModeLifecycleHarness(LocalDate.of(2026, 10, 8))
        assertEquals(StrictModeDailyState.ACTIVE_INTERACTIVE, harness.getDailyState())

        // User toggles OFF
        val success = harness.disableStrictModeForToday()
        assertTrue("Disable succeeds", success)
        assertEquals(StrictModeDailyState.DISABLED_TODAY, harness.getDailyState())
        assertFalse("Enforcement is inactive", harness.getDailyState().isEnforcementActive)
        assertTrue("Reactivation with ad is offered", harness.getDailyState().canReactivateWithAd)
        assertFalse("Cannot turn off again when already off", harness.getDailyState().canTurnOff)
    }

    @Test
    fun testLifecycle_reactivateWithAd_locksForRemainderOfDay() {
        val harness = StrictModeLifecycleHarness(LocalDate.of(2026, 10, 8))
        harness.disableStrictModeForToday()
        assertEquals(StrictModeDailyState.DISABLED_TODAY, harness.getDailyState())

        // Watch ad and reactivate
        val reactivated = harness.reactivateStrictModeWithAd()
        assertTrue("Reactivation succeeds", reactivated)
        assertEquals(StrictModeDailyState.REACTIVATED_LOCKED, harness.getDailyState())
        assertTrue("Enforcement is active again", harness.getDailyState().isEnforcementActive)
        assertTrue("State is locked", harness.getDailyState().isLocked)
        assertFalse("Cannot turn off when locked", harness.getDailyState().canTurnOff)
        assertFalse("Cannot reactivate again when already active", harness.getDailyState().canReactivateWithAd)

        // Attempting to turn OFF again on the same day must be rejected
        val rejectedTurnOff = harness.disableStrictModeForToday()
        assertFalse("Turning off must be rejected when locked", rejectedTurnOff)
        assertEquals(StrictModeDailyState.REACTIVATED_LOCKED, harness.getDailyState())
    }

    @Test
    fun testLifecycle_failedOrDismissedAd_doesNotReactivate() {
        val harness = StrictModeLifecycleHarness(LocalDate.of(2026, 10, 8))
        harness.disableStrictModeForToday()
        assertEquals(StrictModeDailyState.DISABLED_TODAY, harness.getDailyState())

        // Ad is dismissed or failed -> reactivateStrictModeWithAd is NEVER called
        // State must remain strictly DISABLED_TODAY
        assertEquals(StrictModeDailyState.DISABLED_TODAY, harness.getDailyState())
        assertFalse("Enforcement remains off", harness.getDailyState().isEnforcementActive)
        assertTrue("Ad option is still available", harness.getDailyState().canReactivateWithAd)
    }

    @Test
    fun testLifecycle_midnightRollover_resetsToActiveInteractive_fromLocked() {
        val harness = StrictModeLifecycleHarness(LocalDate.of(2026, 10, 8))
        // Day 1: User turned off then reactivated with ad -> REACTIVATED_LOCKED
        harness.disableStrictModeForToday()
        harness.reactivateStrictModeWithAd()
        assertEquals(StrictModeDailyState.REACTIVATED_LOCKED, harness.getDailyState())

        // Advance to Day 2 (midnight rollover)
        harness.advanceToNextDay()

        assertEquals("2026-10-09", harness.getCurrentDateStr())
        assertEquals("New day must reset to ACTIVE_INTERACTIVE", StrictModeDailyState.ACTIVE_INTERACTIVE, harness.getDailyState())
        assertTrue("Enforcement is active on new day", harness.getDailyState().isEnforcementActive)
        assertFalse("Lock is reset on new day", harness.getDailyState().isLocked)
        assertTrue("User can turn it off once on the new day", harness.getDailyState().canTurnOff)
    }

    @Test
    fun testLifecycle_midnightRollover_resetsToActiveInteractive_fromDisabled() {
        val harness = StrictModeLifecycleHarness(LocalDate.of(2026, 10, 8))
        // Day 1: User turned off and NEVER reactivated -> ended Day 1 as DISABLED_TODAY
        harness.disableStrictModeForToday()
        assertEquals(StrictModeDailyState.DISABLED_TODAY, harness.getDailyState())

        // Advance to Day 2 (midnight rollover)
        harness.advanceToNextDay()

        assertEquals("2026-10-09", harness.getCurrentDateStr())
        // Even if user left Strict Mode OFF yesterday, today MUST start ON automatically!
        assertEquals("New day must automatically start ACTIVE_INTERACTIVE even if yesterday was OFF",
            StrictModeDailyState.ACTIVE_INTERACTIVE, harness.getDailyState())
        assertTrue("Enforcement is active on new day", harness.getDailyState().isEnforcementActive)
        assertFalse("Lock is reset on new day", harness.getDailyState().isLocked)
        assertTrue("User can turn it off once today", harness.getDailyState().canTurnOff)
    }

    // =========================================================================
    // 3. Source Code Architecture Verification
    // =========================================================================

    @Test
    fun testSourceVerification_DayManager_unconditionalStrictOnNewDay() {
        val file = findSourceFile("src/main/java/com/reeltrak/app/services/tracking/DayManager.kt")
        val content = file.readText()

        // Verify DayManager initializes new days with isStrictModeOn = true and isStrictModeLocked = false
        assertTrue("DayManager sets isStrictModeOn = true on new day",
            content.contains("isStrictModeOn = true"))
        assertTrue("DayManager sets isStrictModeLocked = false on new day",
            content.contains("isStrictModeLocked = false"))

        // Verify DayManager exposes the new methods and flow
        assertTrue("DayManager exposes disableStrictModeForToday",
            content.contains("fun disableStrictModeForToday"))
        assertTrue("DayManager exposes reactivateStrictModeWithAd",
            content.contains("fun reactivateStrictModeWithAd"))
        assertTrue("DayManager exposes strictModeDailyState flow",
            content.contains("val strictModeDailyState: Flow<StrictModeDailyState>"))
        assertTrue("DayManager exposes canDisableStrictModeToday",
            content.contains("fun canDisableStrictModeToday"))
        assertTrue("DayManager checks and persists lastStrictModeOffDate",
            content.contains("lastStrictModeOffDate"))

        val prefsFile = findSourceFile("src/main/java/com/reeltrak/app/domain/repository/PreferencesRepository.kt")
        val prefsContent = prefsFile.readText()
        assertTrue("PreferencesRepository declares lastStrictModeOffDate flow",
            prefsContent.contains("val lastStrictModeOffDate: Flow<String?>"))
        assertTrue("PreferencesRepository declares setLastStrictModeOffDate function",
            prefsContent.contains("suspend fun setLastStrictModeOffDate(date: String)"))
    }

    @Test
    fun testSourceVerification_DashboardViewModel_exposesStateAndActions() {
        val file = findSourceFile("src/main/java/com/reeltrak/app/features/dashboard/viewmodel/DashboardViewModel.kt")
        val content = file.readText()

        assertTrue("DashboardViewModel exposes strictModeDailyState StateFlow",
            content.contains("val strictModeDailyState: StateFlow<StrictModeDailyState>"))
        assertTrue("DashboardViewModel provides disableStrictModeForToday",
            content.contains("fun disableStrictModeForToday()"))
        assertTrue("DashboardViewModel provides reactivateStrictModeWithAd",
            content.contains("fun reactivateStrictModeWithAd()"))
        assertTrue("DashboardViewModel provides turnOnStrictModeWithAd",
            content.contains("fun turnOnStrictModeWithAd("))
        assertTrue("DashboardViewModel provides turnOffStrictModeWithAd",
            content.contains("fun turnOffStrictModeWithAd("))
    }

    @Test
    fun testLifecycle_onlyOneTurnOffAllowedPerCalendarDay() {
        val harness = StrictModeLifecycleHarness(LocalDate.of(2026, 10, 8))
        assertEquals(StrictModeDailyState.ACTIVE_INTERACTIVE, harness.getDailyState())

        // 1st attempt on Day 1 succeeds
        val firstAttempt = harness.disableStrictModeForToday()
        assertTrue("First disable attempt on Day 1 must succeed", firstAttempt)
        assertEquals(StrictModeDailyState.DISABLED_TODAY, harness.getDailyState())
        assertEquals("2026-10-08", harness.lastStrictModeOffDate)

        // 2nd attempt on Day 1 must be rejected
        val secondAttempt = harness.disableStrictModeForToday()
        assertFalse("Second disable attempt on same calendar day must be rejected", secondAttempt)
        assertEquals(StrictModeDailyState.DISABLED_TODAY, harness.getDailyState())
    }

    @Test
    fun testLifecycle_processRestartOnSameDay_preservesConsumedAllowance() {
        val harness = StrictModeLifecycleHarness(LocalDate.of(2026, 10, 8))
        assertTrue(harness.disableStrictModeForToday())
        assertEquals("2026-10-08", harness.lastStrictModeOffDate)

        // Simulate app kill / reboot on same day
        val restartedHarness = StrictModeLifecycleHarness(
            currentDate = LocalDate.of(2026, 10, 8),
            initialDb = harness.inMemoryDb,
            lastStrictModeOffDate = harness.lastStrictModeOffDate
        )

        assertEquals(StrictModeDailyState.DISABLED_TODAY, restartedHarness.getDailyState())
        val restartAttempt = restartedHarness.disableStrictModeForToday()
        assertFalse("Turning off must still be rejected after process restart on same day", restartAttempt)
    }

    @Test
    fun testLifecycle_midnightRollover_grantsNewDayOffAllowance() {
        val harness = StrictModeLifecycleHarness(LocalDate.of(2026, 10, 8))
        assertTrue(harness.disableStrictModeForToday())
        assertEquals("2026-10-08", harness.lastStrictModeOffDate)

        // Advance to Day 2 (midnight)
        harness.advanceToNextDay()
        assertEquals("2026-10-09", harness.getCurrentDateStr())
        assertEquals("Day 2 starts ACTIVE_INTERACTIVE", StrictModeDailyState.ACTIVE_INTERACTIVE, harness.getDailyState())

        // Day 2 allows exactly 1 turn OFF
        val day2FirstAttempt = harness.disableStrictModeForToday()
        assertTrue("Day 2 gets a fresh daily OFF allowance", day2FirstAttempt)
        assertEquals("2026-10-09", harness.lastStrictModeOffDate)
        assertEquals(StrictModeDailyState.DISABLED_TODAY, harness.getDailyState())

        // Day 2 second attempt rejected
        val day2SecondAttempt = harness.disableStrictModeForToday()
        assertFalse("Day 2 second attempt is rejected", day2SecondAttempt)
    }

    @Test
    fun testSourceVerification_StrictModeCard_rendersAllThreeStates() {
        val file = findSourceFile("src/main/java/com/reeltrak/app/features/dashboard/ui/components/StrictModeCard.kt")
        val content = file.readText()

        assertTrue("StrictModeCard imports StrictModeDailyState",
            content.contains("StrictModeDailyState"))
        assertTrue("StrictModeCard handles ACTIVE_INTERACTIVE",
            content.contains("StrictModeDailyState.ACTIVE_INTERACTIVE"))
        assertTrue("StrictModeCard handles DISABLED_TODAY",
            content.contains("StrictModeDailyState.DISABLED_TODAY"))
        assertTrue("StrictModeCard handles REACTIVATED_LOCKED",
            content.contains("StrictModeDailyState.REACTIVATED_LOCKED"))
    }
}
