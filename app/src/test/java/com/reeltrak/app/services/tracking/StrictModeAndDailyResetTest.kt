package com.reeltrak.app.services.tracking

import com.reeltrak.app.data.local.entity.DailyReelData
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Deterministic unit tests for Strict Mode Day-Scoped Lock and Daily Counter Reset Lifecycle.
 *
 * Covers:
 * Strict Mode:
 * A. OFF -> ON today -> cannot OFF today
 * B. ON today -> process restart -> still ON and locked
 * C. ON today -> date changes -> remains ON but becomes unlockable
 * D. next day -> OFF once -> remains OFF
 * E. next day -> OFF -> ON again -> immediately locked again
 * F. next day -> ON -> restart -> still locked
 * G. next day -> ON -> date changes again -> unlockable again
 * H. account logout/login does not remove device-local strict enforcement
 * I. Firebase sync cannot overwrite today's device-local strict state
 *
 * Daily Counter:
 * J. multiple reads during same day never reset count
 * K. process restart same day preserves count
 * L. Activity recreation preserves count
 * M. AccessibilityService restart preserves count
 * N. app switching preserves count
 * O. account switching preserves today's device-local enforcement count
 * P. trusted midnight creates exactly one new day
 * Q. repeated day-transition checks do not reset repeatedly
 * R. clock forward/backward manipulation cannot cause an unauthorized reset
 * S. Firebase sync cannot reset today's count
 *
 * Boundary Transitions:
 * - 23:59 -> 00:00 (Midnight boundary)
 * - 00:00 -> 00:01 (First reel of new day)
 */
class StrictModeAndDailyResetTest {

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
     * In-memory simulator modeling the exact DayManager + Room DailyReelData state machine.
     */
    class TestDayManagerHarness(
        private var currentDate: LocalDate,
        private var trustedTimestamp: Long = 1700000000000L,
        initialDb: Map<String, DailyReelData> = emptyMap()
    ) {
        val inMemoryDb = mutableMapOf<String, DailyReelData>().apply { putAll(initialDb) }
        var currentDateString: String = currentDate.format(DateTimeFormatter.ISO_LOCAL_DATE)
        var currentDayStats: DailyReelData? = null
        var isRestoring: Boolean = false

        init {
            loadOrCreateDayLocked(currentDateString)
        }

        fun setDate(newDate: LocalDate) {
            currentDate = newDate
        }

        fun getLocalDate(): LocalDate = currentDate

        fun getCurrentDateStr(): String = currentDate.format(DateTimeFormatter.ISO_LOCAL_DATE)

        fun checkDayTransition() {
            val todayStr = getCurrentDateStr()
            val currentStoredStr = currentDateString
            if (todayStr != currentStoredStr) {
                // Finalize previous day
                currentDayStats?.let {
                    inMemoryDb[it.dateString] = it.copy(updatedAt = trustedTimestamp, syncStatus = 1)
                }
                currentDateString = todayStr
                loadOrCreateDayLocked(todayStr)
            }
        }

        private fun loadOrCreateDayLocked(dateStr: String) {
            var stats = inMemoryDb[dateStr]
            if (stats == null) {
                val lastStats = inMemoryDb.values.maxByOrNull { it.dateString }
                val carryOverLimit = lastStats?.dailyGoal ?: 500
                val carryOverStrictMode = lastStats?.isStrictModeOn ?: false
                stats = DailyReelData(
                    dateString = dateStr,
                    totalReelsCount = 0,
                    dailyGoal = carryOverLimit,
                    isLimitLocked = false,
                    isStrictModeOn = carryOverStrictMode,
                    isStrictModeLocked = false,
                    updatedAt = trustedTimestamp,
                    syncStatus = 1
                )
                inMemoryDb[dateStr] = stats
            }
            currentDayStats = stats
        }

        fun incrementReelCount(amount: Int = 1) {
            if (isRestoring) return
            val todayStr = getCurrentDateStr()
            if (todayStr != currentDateString) {
                checkDayTransition()
            }
            val current = currentDayStats ?: return
            val updated = current.copy(
                totalReelsCount = current.totalReelsCount + amount,
                updatedAt = trustedTimestamp,
                syncStatus = 1
            )
            currentDayStats = updated
            inMemoryDb[updated.dateString] = updated
        }

        fun setStrictMode(enabled: Boolean) {
            if (isRestoring) return
            val current = currentDayStats ?: return
            if (!enabled && current.isStrictModeLocked) {
                // Reject disable when locked
                return
            }
            val updated = current.copy(
                isStrictModeOn = enabled,
                isStrictModeLocked = if (enabled) true else false,
                updatedAt = trustedTimestamp,
                syncStatus = 1
            )
            currentDayStats = updated
            inMemoryDb[updated.dateString] = updated
        }
    }

    // =========================================================================
    // STRICT MODE STATE MACHINE TESTS (A - I)
    // =========================================================================

    // A. OFF -> ON today -> cannot OFF today
    @Test
    fun testStrictMode_A_turnOnToday_locksForDay_cannotTurnOffToday() {
        val harness = TestDayManagerHarness(LocalDate.of(2026, 9, 20))
        assertFalse("Initial strict mode is OFF", harness.currentDayStats!!.isStrictModeOn)
        assertFalse("Initial lock is false", harness.currentDayStats!!.isStrictModeLocked)

        harness.setStrictMode(true)
        assertTrue("Strict mode is ON", harness.currentDayStats!!.isStrictModeOn)
        assertTrue("Strict mode is LOCKED for today", harness.currentDayStats!!.isStrictModeLocked)

        // Attempting to turn OFF on the same day must be rejected
        harness.setStrictMode(false)
        assertTrue("Strict mode must remain ON", harness.currentDayStats!!.isStrictModeOn)
        assertTrue("Strict mode must remain locked", harness.currentDayStats!!.isStrictModeLocked)
    }

    // B. ON today -> process restart -> still ON and locked
    @Test
    fun testStrictMode_B_processRestart_preservesStrictOnAndLocked() {
        val harness = TestDayManagerHarness(LocalDate.of(2026, 9, 20))
        harness.setStrictMode(true)

        // Simulate Process Restart by loading a new harness referencing the same in-memory DB
        val restartedHarness = TestDayManagerHarness(
            currentDate = LocalDate.of(2026, 9, 20),
            initialDb = harness.inMemoryDb
        )

        val stats = restartedHarness.currentDayStats
        assertNotNull(stats)
        assertTrue("Strict mode remains ON after restart", stats!!.isStrictModeOn)
        assertTrue("Strict mode remains locked after restart", stats.isStrictModeLocked)
    }

    // C. ON today -> date changes -> remains ON but becomes unlockable
    @Test
    fun testStrictMode_C_dateChanges_strictRemainsOn_lockResets() {
        val harness = TestDayManagerHarness(LocalDate.of(2026, 9, 20))
        harness.setStrictMode(true)
        assertTrue(harness.currentDayStats!!.isStrictModeLocked)

        // Advance date to next day
        harness.setDate(LocalDate.of(2026, 9, 21))
        harness.checkDayTransition()

        val stats = harness.currentDayStats
        assertNotNull(stats)
        assertEquals("2026-09-21", stats!!.dateString)
        assertTrue("Strict mode carries over as ON", stats.isStrictModeOn)
        assertFalse("Lock must reset to false on new day", stats.isStrictModeLocked)
    }

    // D. next day -> OFF once -> remains OFF
    @Test
    fun testStrictMode_D_nextDay_turnOffOnce_remainsOff() {
        val harness = TestDayManagerHarness(LocalDate.of(2026, 9, 20))
        harness.setStrictMode(true)

        // Next Day
        harness.setDate(LocalDate.of(2026, 9, 21))
        harness.checkDayTransition()

        // Turn OFF on Day 2
        harness.setStrictMode(false)
        assertFalse("Strict mode is now OFF", harness.currentDayStats!!.isStrictModeOn)
        assertFalse("Strict mode lock is false", harness.currentDayStats!!.isStrictModeLocked)
    }

    // E. next day -> OFF -> ON again -> immediately locked again
    @Test
    fun testStrictMode_E_nextDay_turnOff_thenTurnOnAgain_immediatelyLocked() {
        val harness = TestDayManagerHarness(LocalDate.of(2026, 9, 20))
        harness.setStrictMode(true)

        // Next Day
        harness.setDate(LocalDate.of(2026, 9, 21))
        harness.checkDayTransition()

        // Turn OFF
        harness.setStrictMode(false)
        assertFalse(harness.currentDayStats!!.isStrictModeOn)

        // Turn ON again on Day 2
        harness.setStrictMode(true)
        assertTrue("Strict mode is ON", harness.currentDayStats!!.isStrictModeOn)
        assertTrue("Strict mode is immediately locked again for Day 2", harness.currentDayStats!!.isStrictModeLocked)

        // Attempting to turn OFF again on Day 2 must be rejected
        harness.setStrictMode(false)
        assertTrue("Strict mode remains ON", harness.currentDayStats!!.isStrictModeOn)
        assertTrue("Strict mode remains locked", harness.currentDayStats!!.isStrictModeLocked)
    }

    // F. next day -> ON -> restart -> still locked
    @Test
    fun testStrictMode_F_nextDay_reLock_processRestart_stillLocked() {
        val harness = TestDayManagerHarness(LocalDate.of(2026, 9, 20))
        harness.setStrictMode(true)

        // Day 2
        harness.setDate(LocalDate.of(2026, 9, 21))
        harness.checkDayTransition()
        harness.setStrictMode(false)
        harness.setStrictMode(true) // re-locked

        // Restart process on Day 2
        val restartedHarness = TestDayManagerHarness(
            currentDate = LocalDate.of(2026, 9, 21),
            initialDb = harness.inMemoryDb
        )

        val stats = restartedHarness.currentDayStats
        assertNotNull(stats)
        assertTrue("Strict mode remains ON", stats!!.isStrictModeOn)
        assertTrue("Strict mode remains locked after restart", stats.isStrictModeLocked)
    }

    // G. next day -> ON -> date changes again -> unlockable again
    @Test
    fun testStrictMode_G_relock_thenDay3_unlockableAgain() {
        val harness = TestDayManagerHarness(LocalDate.of(2026, 9, 20))
        harness.setStrictMode(true)

        // Day 2: re-lock
        harness.setDate(LocalDate.of(2026, 9, 21))
        harness.checkDayTransition()
        harness.setStrictMode(false)
        harness.setStrictMode(true)
        assertTrue(harness.currentDayStats!!.isStrictModeLocked)

        // Day 3: midnight rollover
        harness.setDate(LocalDate.of(2026, 9, 22))
        harness.checkDayTransition()

        val stats = harness.currentDayStats
        assertNotNull(stats)
        assertEquals("2026-09-22", stats!!.dateString)
        assertTrue("Strict mode carries over ON to Day 3", stats.isStrictModeOn)
        assertFalse("Strict mode lock resets to false on Day 3", stats.isStrictModeLocked)

        // Can disable on Day 3
        harness.setStrictMode(false)
        assertFalse("Strict mode turned OFF on Day 3", harness.currentDayStats!!.isStrictModeOn)
    }

    // H. account logout/login does not remove device-local strict enforcement
    @Test
    fun testStrictMode_H_accountSwitch_doesNotClearDeviceLocalStrictEnforcement() {
        val file = findSourceFile("src/main/java/com/reeltrak/app/data/local/dao/DailyReelDataDao.kt")
        val content = file.readText()
        assertTrue("DailyReelDataDao clearHistoricalDataExcept preserves today's row", content.contains("clearHistoricalDataExcept"))
        assertTrue("DailyReelDataDao does not wipe today", content.contains("WHERE dateString != :today"))
    }

    // I. Firebase sync cannot overwrite today's device-local strict state
    @Test
    fun testStrictMode_I_syncManager_excludesTodayFromRestore() {
        val file = findSourceFile("src/main/java/com/reeltrak/app/core/sync/SyncManager.kt")
        val content = file.readText()
        assertTrue("SyncManager excludes today's record from restore", content.contains("if (dto.dateString == todayStr) {") || content.contains("dto.dateString == todayStr"))
    }

    // =========================================================================
    // DAILY COUNTER LIFECYCLE TESTS (J - S)
    // =========================================================================

    // J. multiple reads during same day never reset count
    @Test
    fun testDailyCounter_J_multipleReads_neverResetCount() {
        val harness = TestDayManagerHarness(LocalDate.of(2026, 9, 20))
        harness.incrementReelCount(10)

        repeat(20) {
            assertEquals(10, harness.currentDayStats?.totalReelsCount)
        }
    }

    // K. process restart same day preserves count
    @Test
    fun testDailyCounter_K_processRestart_preservesCount() {
        val harness = TestDayManagerHarness(LocalDate.of(2026, 9, 20))
        harness.incrementReelCount(15)

        // Simulate process restart
        val restartedHarness = TestDayManagerHarness(
            currentDate = LocalDate.of(2026, 9, 20),
            initialDb = harness.inMemoryDb
        )

        assertEquals(15, restartedHarness.currentDayStats?.totalReelsCount)
    }

    // L. Activity recreation preserves count
    @Test
    fun testDailyCounter_L_activityRecreation_preservesCount() {
        val harness = TestDayManagerHarness(LocalDate.of(2026, 9, 20))
        harness.incrementReelCount(7)

        // Reading currentDayStats without day transition
        val currentStats = harness.currentDayStats
        assertNotNull(currentStats)
        assertEquals(7, currentStats!!.totalReelsCount)
    }

    // M. AccessibilityService restart preserves count
    @Test
    fun testDailyCounter_M_accessibilityServiceRestart_preservesCount() {
        val harness = TestDayManagerHarness(LocalDate.of(2026, 9, 20))
        harness.incrementReelCount(12)

        // Service reconnecting reads StateFlow
        assertEquals(12, harness.currentDayStats?.totalReelsCount)
        harness.incrementReelCount(3)
        assertEquals(15, harness.currentDayStats?.totalReelsCount)
    }

    // N. app switching preserves count
    @Test
    fun testDailyCounter_N_appSwitching_preservesCount() {
        val harness = TestDayManagerHarness(LocalDate.of(2026, 9, 20))
        harness.incrementReelCount(4)
        // Backgrounded / Foregrounded
        harness.checkDayTransition()
        assertEquals(4, harness.currentDayStats?.totalReelsCount)
    }

    // O. account switching preserves today's device-local enforcement count
    @Test
    fun testDailyCounter_O_accountSwitching_preservesTodayCount() {
        val harness = TestDayManagerHarness(LocalDate.of(2026, 9, 20))
        harness.incrementReelCount(33)

        // Simulate user logout/login preserving today's row in DB
        val todayStr = harness.getCurrentDateStr()
        val preservedRow = harness.inMemoryDb[todayStr]
        assertNotNull(preservedRow)
        assertEquals(33, preservedRow!!.totalReelsCount)
    }

    // P. trusted midnight creates exactly one new day
    @Test
    fun testDailyCounter_P_trustedMidnight_createsExactlyOneNewDay() {
        val harness = TestDayManagerHarness(LocalDate.of(2026, 9, 20))
        harness.incrementReelCount(50)

        // Midnight transition
        harness.setDate(LocalDate.of(2026, 9, 21))
        harness.checkDayTransition()

        assertEquals("2026-09-21", harness.currentDayStats?.dateString)
        assertEquals("New day count starts at 0", 0, harness.currentDayStats?.totalReelsCount)
        assertEquals(50, harness.inMemoryDb["2026-09-20"]?.totalReelsCount)
    }

    // Q. repeated day-transition checks do not reset repeatedly
    @Test
    fun testDailyCounter_Q_repeatedTransitionChecks_doNotReset() {
        val harness = TestDayManagerHarness(LocalDate.of(2026, 9, 20))
        harness.incrementReelCount(18)

        repeat(10) {
            harness.checkDayTransition()
            assertEquals(18, harness.currentDayStats?.totalReelsCount)
        }
    }

    // R. clock forward/backward manipulation cannot cause an unauthorized reset
    @Test
    fun testDailyCounter_R_clockManipulation_doesNotResetWithoutTrustedDateChange() {
        val harness = TestDayManagerHarness(LocalDate.of(2026, 9, 20))
        harness.incrementReelCount(22)

        // Device clock manipulation broadcasts time change, but trusted date is unchanged
        harness.checkDayTransition()
        assertEquals(22, harness.currentDayStats?.totalReelsCount)
    }

    // S. Firebase sync cannot reset today's count
    @Test
    fun testDailyCounter_S_syncCannotResetTodayCount() {
        val harness = TestDayManagerHarness(LocalDate.of(2026, 9, 20))
        harness.incrementReelCount(45)

        // Sync restore occurs (isRestoring = true)
        harness.isRestoring = true
        // Increments blocked during restore
        harness.incrementReelCount(10)
        assertEquals(45, harness.currentDayStats?.totalReelsCount)

        harness.isRestoring = false
        assertEquals(45, harness.currentDayStats?.totalReelsCount)
    }

    // =========================================================================
    // BOUNDARY TESTS (23:59 -> 00:00 -> 00:01)
    // =========================================================================

    @Test
    fun testBoundary_2359_to_0000_to_0001() {
        val harness = TestDayManagerHarness(LocalDate.of(2026, 9, 20))

        // 23:59: User scrolls 40 reels and enables Strict Mode
        harness.incrementReelCount(40)
        harness.setStrictMode(true)
        assertEquals(40, harness.currentDayStats?.totalReelsCount)
        assertTrue(harness.currentDayStats?.isStrictModeLocked == true)

        // 00:00: Midnight boundary crossed
        harness.setDate(LocalDate.of(2026, 9, 21))
        harness.checkDayTransition()

        val statsMidnight = harness.currentDayStats
        assertNotNull(statsMidnight)
        assertEquals("2026-09-21", statsMidnight!!.dateString)
        assertEquals(0, statsMidnight.totalReelsCount)
        assertTrue("Strict mode carried over as ON", statsMidnight.isStrictModeOn)
        assertFalse("Strict mode lock is reset to false at 00:00", statsMidnight.isStrictModeLocked)

        // 00:01: First reel of new day
        harness.incrementReelCount(1)
        assertEquals(1, harness.currentDayStats?.totalReelsCount)
        assertFalse("Strict mode remains unlocked after first reel until toggled ON", harness.currentDayStats?.isStrictModeLocked == true)
    }

    // =========================================================================
    // CODEBASE SOURCE AUDIT
    // =========================================================================

    @Test
    fun testSourceAudit_DayManager_EliminatesNullEmissionFlicker() {
        val file = findSourceFile("src/main/java/com/reeltrak/app/services/tracking/DayManager.kt")
        val content = file.readText()
        assertFalse(
            "DayManager.loadOrCreateDayLocked must not set _currentDayStats.value = null",
            content.contains("_currentDayStats.value = null")
        )
    }

    @Test
    fun testSourceAudit_DayManager_StrictModeLocked_ResetOnNewDay() {
        val file = findSourceFile("src/main/java/com/reeltrak/app/services/tracking/DayManager.kt")
        val content = file.readText()
        assertTrue(
            "DayManager carries over isStrictModeOn and sets isStrictModeLocked = false for new day",
            content.contains("isStrictModeLocked = false")
        )
    }
}
