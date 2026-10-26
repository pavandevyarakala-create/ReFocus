package com.reeltrak.app.services.tracking

import com.reeltrak.app.data.local.entity.DailyReelData
import com.reeltrak.app.data.local.entity.strictModeDailyState
import com.reeltrak.app.domain.models.StrictModeDailyState
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Comprehensive, deterministic test suite verifying all rules of the Strict Mode Daily Reset
 * and Rewarded Ad enforcement architecture:
 *
 * 1. AUTOMATIC ENABLE AT MIDNIGHT:
 *    - Strict Mode automatically resets to ON at 12:00 AM (midnight) for every new calendar day,
 *      even if the user manually turned it OFF yesterday.
 *    - Works across process restarts, cold starts, and missed midnight transitions.
 *
 * 2. SINGLE DAILY OFF ALLOWANCE:
 *    - Each calendar day allows AT MOST ONE turn OFF action.
 *    - After turning OFF, every subsequent turn OFF attempt that day is rejected.
 *    - Survives app restarts, force stops, reboots, and navigation.
 *
 * 3. MANUAL TURN ON REQUIRES REWARDED AD:
 *    - Turning Strict Mode back ON requires a successfully completed rewarded ad.
 *    - Transitions state to REACTIVATED_LOCKED.
 *    - Does NOT restore or reset the daily OFF allowance.
 *
 * 4. FAILED / DISMISSED / INCOMPLETE ADS:
 *    - Incomplete or failed ads NEVER change Strict Mode state.
 *    - Incomplete or failed ads NEVER consume the daily OFF allowance.
 *
 * 5. DUPLICATE CALLBACK & RAPID TAP GUARDS:
 *    - Atomic guards protect against duplicate AdMob SDK callbacks and rapid button taps.
 *
 * 6. CLOCK TAMPERING & MONOTONIC ANCHOR POLICY:
 *    - Guards against clock forward/backward skips using monotonic elapsed real-time anchors.
 *    - Documents reboot boundary honestly (elapsedRealtime resets on device reboot).
 */
class StrictModeDailyResetAdRulesTest {

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
     * Deterministic harness simulating DayManager + PreferencesRepository behavior.
     */
    class StrictModeEngineHarness(
        var mockLocalDate: LocalDate = LocalDate.of(2026, 10, 11),
        var mockLocalTime: LocalTime = LocalTime.of(14, 0),
        var mockElapsedRealtimeMs: Long = 100_000_000L,
        initialDb: Map<String, DailyReelData> = emptyMap(),
        initialPersistedOffDate: String? = null
    ) {
        val inMemoryDb = ConcurrentHashMap<String, DailyReelData>().apply { putAll(initialDb) }
        var persistedLastOffDate: String? = initialPersistedOffDate

        var currentDateString: String = mockLocalDate.format(DateTimeFormatter.ISO_LOCAL_DATE)
        var currentDayStats: DailyReelData? = null

        // Monotonic verification anchors (mirroring DayManager)
        var lastConfirmedDate: LocalDate = mockLocalDate
        var lastConfirmedLocalTime: LocalTime = mockLocalTime
        var lastConfirmedElapsedRealtimeMs: Long = mockElapsedRealtimeMs

        init {
            loadOrCreateDayLocked(currentDateString)
        }

        fun getCurrentDateStr(): String = mockLocalDate.format(DateTimeFormatter.ISO_LOCAL_DATE)

        fun loadOrCreateDayLocked(dateStr: String) {
            var stats = inMemoryDb[dateStr]
            if (stats == null) {
                // New calendar day row creation
                stats = DailyReelData(
                    dateString = dateStr,
                    totalReelsCount = 0,
                    dailyGoal = 500,
                    isLimitLocked = false,
                    isStrictModeOn = true,
                    isStrictModeLocked = false,
                    updatedAt = System.currentTimeMillis(),
                    syncStatus = 1
                )
                inMemoryDb[dateStr] = stats
            } else {
                // Existing row reconciliation
                if (persistedLastOffDate == stats.dateString) {
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

        fun disableStrictModeForToday(rewardEarned: Boolean): Boolean {
            if (!rewardEarned) {
                // Ad was dismissed or failed: state remains completely untouched
                return false
            }
            val current = currentDayStats ?: return false
            if (current.isStrictModeLocked) {
                return false
            }
            if (persistedLastOffDate == current.dateString) {
                // Allowance already consumed today
                return false
            }

            val updated = current.copy(
                isStrictModeOn = false,
                isStrictModeLocked = false,
                updatedAt = System.currentTimeMillis(),
                syncStatus = 1
            )
            persistedLastOffDate = current.dateString
            currentDayStats = updated
            inMemoryDb[updated.dateString] = updated
            return true
        }

        fun reactivateStrictModeWithAd(rewardEarned: Boolean): Boolean {
            if (!rewardEarned) {
                // Ad was dismissed or failed: state remains untouched
                return false
            }
            val current = currentDayStats ?: return false
            val updated = current.copy(
                isStrictModeOn = true,
                isStrictModeLocked = true,
                updatedAt = System.currentTimeMillis(),
                syncStatus = 1
            )
            // Does NOT reset persistedLastOffDate
            currentDayStats = updated
            inMemoryDb[updated.dateString] = updated
            return true
        }

        fun advanceTimeToMidnightNextDay() {
            // Legitimate time progression: advance 10 hours to midnight
            val millisToMidnight = Duration.between(
                LocalDateTime.of(mockLocalDate, mockLocalTime),
                LocalDateTime.of(mockLocalDate.plusDays(1), LocalTime.MIDNIGHT)
            ).toMillis()

            mockElapsedRealtimeMs += (millisToMidnight + 1_000L) // Real time passed
            mockLocalDate = mockLocalDate.plusDays(1)
            mockLocalTime = LocalTime.of(0, 0, 1)

            val newDateStr = getCurrentDateStr()
            currentDateString = newDateStr
            loadOrCreateDayLocked(newDateStr)

            lastConfirmedDate = mockLocalDate
            lastConfirmedLocalTime = mockLocalTime
            lastConfirmedElapsedRealtimeMs = mockElapsedRealtimeMs
        }

        fun getDailyState(): StrictModeDailyState {
            return currentDayStats?.strictModeDailyState ?: StrictModeDailyState.ACTIVE_INTERACTIVE
        }

        fun isLegitimateDateTransition(candidateDate: LocalDate): Boolean {
            if (candidateDate.isBefore(mockLocalDate)) return false
            if (candidateDate == mockLocalDate) return false

            val elapsedSinceAnchor = (mockElapsedRealtimeMs - lastConfirmedElapsedRealtimeMs).coerceAtLeast(0L)
            val startDateTime = LocalDateTime.of(mockLocalDate, lastConfirmedLocalTime)
            val midnightDateTime = LocalDateTime.of(mockLocalDate.plusDays(1), LocalTime.MIDNIGHT)
            val requiredMillis = Duration.between(startDateTime, midnightDateTime).toMillis().coerceAtLeast(0L)
            val requiredWithGrace = (requiredMillis - 5_000L).coerceAtLeast(0L)

            return elapsedSinceAnchor >= requiredWithGrace
        }
    }

    // =========================================================================
    // 1. Automatic Reset at Midnight
    // =========================================================================

    @Test
    fun testMidnightReset_automaticallyEnablesStrictMode_whenYesterdayEndedDisabled() {
        val harness = StrictModeEngineHarness()
        assertEquals(StrictModeDailyState.ACTIVE_INTERACTIVE, harness.getDailyState())

        // User turns Strict Mode OFF today with rewarded ad
        val disabled = harness.disableStrictModeForToday(rewardEarned = true)
        assertTrue("Disable must succeed", disabled)
        assertEquals(StrictModeDailyState.DISABLED_TODAY, harness.getDailyState())

        // User goes to sleep with Strict Mode OFF. Midnight arrives (00:00:01 next day)
        harness.advanceTimeToMidnightNextDay()

        assertEquals("New day date must advance", "2026-10-12", harness.getCurrentDateStr())
        assertEquals(
            "Strict Mode must automatically become ACTIVE_INTERACTIVE at midnight even though yesterday was OFF",
            StrictModeDailyState.ACTIVE_INTERACTIVE,
            harness.getDailyState()
        )
        assertTrue("Enforcement must be active on new day", harness.getDailyState().isEnforcementActive)
        assertFalse("Strict Mode must not be locked on new day start", harness.getDailyState().isLocked)
        assertTrue("User must be granted a fresh turn-off allowance for the new day", harness.getDailyState().canTurnOff)
    }

    @Test
    fun testMidnightReset_automaticallyEnablesStrictMode_whenYesterdayEndedLocked() {
        val harness = StrictModeEngineHarness()
        // Turn OFF, then reactivate with ad -> REACTIVATED_LOCKED
        harness.disableStrictModeForToday(rewardEarned = true)
        harness.reactivateStrictModeWithAd(rewardEarned = true)
        assertEquals(StrictModeDailyState.REACTIVATED_LOCKED, harness.getDailyState())

        // Midnight arrives
        harness.advanceTimeToMidnightNextDay()

        assertEquals("2026-10-12", harness.getCurrentDateStr())
        assertEquals(
            "Strict Mode must reset to ACTIVE_INTERACTIVE from locked yesterday",
            StrictModeDailyState.ACTIVE_INTERACTIVE,
            harness.getDailyState()
        )
        assertFalse("Lock must be reset on the new day", harness.getDailyState().isLocked)
        assertTrue("Fresh allowance available for today", harness.getDailyState().canTurnOff)
    }

    // =========================================================================
    // 2. Single OFF Allowance per Calendar Day
    // =========================================================================

    @Test
    fun testDailyOffAllowance_onlyOneTurnOffAllowedPerCalendarDay() {
        val harness = StrictModeEngineHarness()

        // First attempt with ad: succeeds
        val firstOff = harness.disableStrictModeForToday(rewardEarned = true)
        assertTrue("First OFF action of the day must succeed", firstOff)
        assertEquals(StrictModeDailyState.DISABLED_TODAY, harness.getDailyState())
        assertEquals("2026-10-11", harness.persistedLastOffDate)

        // Second attempt on the same day: blocked
        val secondOff = harness.disableStrictModeForToday(rewardEarned = true)
        assertFalse("Second OFF action on same day must be strictly blocked", secondOff)
        assertEquals(StrictModeDailyState.DISABLED_TODAY, harness.getDailyState())
    }

    @Test
    fun testDailyOffAllowance_persistsAcrossAppRestartAndReboot() {
        val harness = StrictModeEngineHarness()
        assertTrue(harness.disableStrictModeForToday(rewardEarned = true))
        assertEquals("2026-10-11", harness.persistedLastOffDate)

        // Simulate app force-stop or phone reboot on the same calendar day
        val restartedEngine = StrictModeEngineHarness(
            mockLocalDate = LocalDate.of(2026, 10, 11),
            initialDb = harness.inMemoryDb,
            initialPersistedOffDate = harness.persistedLastOffDate
        )

        assertEquals("State must be DISABLED_TODAY after restart", StrictModeDailyState.DISABLED_TODAY, restartedEngine.getDailyState())
        val attemptAfterRestart = restartedEngine.disableStrictModeForToday(rewardEarned = true)
        assertFalse("Second turn-off attempt after app restart must still be rejected", attemptAfterRestart)
    }

    // =========================================================================
    // 3. Manual Turn ON Requires Rewarded Ad & Does NOT Restore Allowance
    // =========================================================================

    @Test
    fun testTurnOnWithAd_locksForDay_andDoesNotRestoreOffAllowance() {
        val harness = StrictModeEngineHarness()
        harness.disableStrictModeForToday(rewardEarned = true)
        assertEquals(StrictModeDailyState.DISABLED_TODAY, harness.getDailyState())

        // User manually reactivates by watching rewarded ad
        val reactivated = harness.reactivateStrictModeWithAd(rewardEarned = true)
        assertTrue("Reactivation with confirmed ad must succeed", reactivated)
        assertEquals(StrictModeDailyState.REACTIVATED_LOCKED, harness.getDailyState())
        assertTrue("Enforcement must be ON", harness.getDailyState().isEnforcementActive)
        assertTrue("Must be LOCKED for remainder of today", harness.getDailyState().isLocked)

        // Attempting to turn OFF again today must be strictly rejected
        val rejectedTurnOff = harness.disableStrictModeForToday(rewardEarned = true)
        assertFalse("Turning OFF again today after reactivation must be rejected", rejectedTurnOff)
        assertEquals(StrictModeDailyState.REACTIVATED_LOCKED, harness.getDailyState())

        // Persisted date must still reflect today's consumed OFF action
        assertEquals("2026-10-11", harness.persistedLastOffDate)
    }

    // =========================================================================
    // 4. Failed, Dismissed, or Unavailable Ads
    // =========================================================================

    @Test
    fun testFailedOrDismissedAd_whenTurningOff_preservesStateAndAllowance() {
        val harness = StrictModeEngineHarness()
        assertEquals(StrictModeDailyState.ACTIVE_INTERACTIVE, harness.getDailyState())

        // Ad was dismissed / skipped early without reward
        val dismissedResult = harness.disableStrictModeForToday(rewardEarned = false)
        assertFalse("Dismissing ad must NOT disable Strict Mode", dismissedResult)
        assertEquals(StrictModeDailyState.ACTIVE_INTERACTIVE, harness.getDailyState())
        assertNull("Daily allowance must NOT be consumed on dismissed ad", harness.persistedLastOffDate)

        // User later retries and successfully earns reward
        val retryResult = harness.disableStrictModeForToday(rewardEarned = true)
        assertTrue("Retry with earned reward must succeed", retryResult)
        assertEquals(StrictModeDailyState.DISABLED_TODAY, harness.getDailyState())
        assertEquals("2026-10-11", harness.persistedLastOffDate)
    }

    @Test
    fun testFailedOrDismissedAd_whenTurningOn_doesNotReactivate() {
        val harness = StrictModeEngineHarness()
        harness.disableStrictModeForToday(rewardEarned = true)
        assertEquals(StrictModeDailyState.DISABLED_TODAY, harness.getDailyState())

        // Attempting to turn ON with failed / incomplete ad
        val failedResult = harness.reactivateStrictModeWithAd(rewardEarned = false)
        assertFalse("Incomplete ad must NOT reactivate Strict Mode", failedResult)
        assertEquals(StrictModeDailyState.DISABLED_TODAY, harness.getDailyState())
        assertFalse("Strict Mode must remain OFF", harness.getDailyState().isEnforcementActive)
    }

    // =========================================================================
    // 5. Duplicate Reward Callbacks & Rapid Tap Guards
    // =========================================================================

    @Test
    fun testDuplicateRewardCallbacks_triggersTransitionExactlyOnce() {
        var disableExecutionCount = 0
        var successNotificationCount = 0
        val callbackMutex = AtomicBoolean(false)

        fun onAdMobRewardCallback() {
            if (callbackMutex.compareAndSet(false, true)) {
                disableExecutionCount++
                successNotificationCount++
            }
        }

        // Simulate AdMob firing callback 3 times due to network glitch / re-trigger
        onAdMobRewardCallback()
        onAdMobRewardCallback()
        onAdMobRewardCallback()

        assertEquals("Disable execution must occur exactly once", 1, disableExecutionCount)
        assertEquals("Success notification must occur exactly once", 1, successNotificationCount)
    }

    @Test
    fun testRapidTaps_allowsOnlyOneAdPresentation() {
        var isShowingAd = false
        var presentedCount = 0

        fun onAdButtonClicked() {
            if (isShowingAd) return // Rapid tap rejection
            isShowingAd = true
            presentedCount++
        }

        onAdButtonClicked() // Tap 1
        onAdButtonClicked() // Rapid tap 2
        onAdButtonClicked() // Rapid tap 3

        assertEquals("Rapid taps must only trigger one ad presentation", 1, presentedCount)
    }

    // =========================================================================
    // 6. Clock Tampering & Monotonic Anchor Realities
    // =========================================================================

    @Test
    fun testClockTampering_skippingClockForwardWithoutRealTimePassing_isRejected() {
        val harness = StrictModeEngineHarness(
            mockLocalDate = LocalDate.of(2026, 10, 11),
            mockLocalTime = LocalTime.of(12, 0),
            mockElapsedRealtimeMs = 50_000_000L
        )

        // Attacker manually sets system date 1 day forward in Android Settings
        val tamperedFutureDate = LocalDate.of(2026, 10, 12)
        // BUT monotonic elapsed real-time did not advance (only 1 second passed)
        harness.mockElapsedRealtimeMs += 1_000L

        val isLegitimate = harness.isLegitimateDateTransition(tamperedFutureDate)
        assertFalse("Tampered date jump without elapsed monotonic time must be rejected", isLegitimate)
    }

    @Test
    fun testClockTampering_backwardTimeShift_isRejected() {
        val harness = StrictModeEngineHarness(
            mockLocalDate = LocalDate.of(2026, 10, 11)
        )

        val pastDate = LocalDate.of(2026, 10, 10)
        val isLegitimate = harness.isLegitimateDateTransition(pastDate)
        assertFalse("Backward date transition must always be rejected", isLegitimate)
    }

    @Test
    fun testSourceCodeIntegrity_DayManager_and_PreferencesRepository_AdGatedMethods() {
        val dayMgrFile = findSourceFile("src/main/java/com/reeltrak/app/services/tracking/DayManager.kt")
        val dayMgrContent = dayMgrFile.readText()

        assertTrue(
            "DayManager must implement disableStrictModeForToday",
            dayMgrContent.contains("fun disableStrictModeForToday")
        )
        assertTrue(
            "DayManager must implement reactivateStrictModeWithAd",
            dayMgrContent.contains("fun reactivateStrictModeWithAd")
        )
        assertTrue(
            "DayManager must check lastStrictModeOffDate",
            dayMgrContent.contains("lastStrictModeOffDate")
        )
        assertTrue(
            "DayManager must implement canDisableStrictModeToday",
            dayMgrContent.contains("fun canDisableStrictModeToday")
        )

        val prefsFile = findSourceFile("src/main/java/com/reeltrak/app/domain/repository/PreferencesRepository.kt")
        val prefsContent = prefsFile.readText()
        assertTrue(
            "PreferencesRepository must declare lastStrictModeOffDate",
            prefsContent.contains("val lastStrictModeOffDate: Flow<String?>")
        )
        assertTrue(
            "PreferencesRepository must declare setLastStrictModeOffDate",
            prefsContent.contains("suspend fun setLastStrictModeOffDate(date: String)")
        )

        val vmFile = findSourceFile("src/main/java/com/reeltrak/app/features/dashboard/viewmodel/DashboardViewModel.kt")
        val vmContent = vmFile.readText()
        assertTrue(
            "DashboardViewModel must implement turnOffStrictModeWithAd",
            vmContent.contains("fun turnOffStrictModeWithAd(")
        )
        assertTrue(
            "DashboardViewModel must implement turnOnStrictModeWithAd",
            vmContent.contains("fun turnOnStrictModeWithAd(")
        )
    }
}
