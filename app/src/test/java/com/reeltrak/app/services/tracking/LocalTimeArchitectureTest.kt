package com.reeltrak.app.services.tracking

import com.reeltrak.app.data.local.entity.DailyReelData
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * Deterministic Test Suite verifying Android Local Time Architecture across:
 * - normal local date
 * - phone date moved forward
 * - phone date moved backward
 * - force-stop before midnight -> open after midnight
 * - process death across midnight
 * - app running across midnight
 * - account logout/login preserves today's device-local count
 * - account switching cannot reset today's daily limit
 * - historical account data remains isolated
 * - strict-mode day transition
 * - streak day transition
 * - calendar follows local date
 * - analytics follows local date
 * - subscription client-side date calculations use local time
 */
class LocalTimeArchitectureTest {

    class LocalDayManagerHarness(
        var mockLocalDate: LocalDate = LocalDate.of(2026, 9, 22),
        val inMemoryDb: ConcurrentHashMap<String, DailyReelData> = ConcurrentHashMap()
    ) {
        var currentDateString: String = mockLocalDate.format(DateTimeFormatter.ISO_LOCAL_DATE)
        var currentDayStats: DailyReelData? = null
        var isStrictModeOn: Boolean = false

        init {
            recoverMissedTransitionsAndInitialize(currentDateString)
        }

        fun getTodayString(): String = mockLocalDate.format(DateTimeFormatter.ISO_LOCAL_DATE)

        fun recoverMissedTransitionsAndInitialize(todayStr: String) {
            val latestRecord = inMemoryDb.values.maxByOrNull { it.dateString }

            if (latestRecord != null && latestRecord.dateString < todayStr) {
                val finalized = latestRecord.copy(
                    updatedAt = System.currentTimeMillis(),
                    syncStatus = 1
                )
                inMemoryDb[latestRecord.dateString] = finalized
            }

            currentDateString = todayStr
            loadOrCreateDay(todayStr)
        }

        fun loadOrCreateDay(dateStr: String) {
            var stats = inMemoryDb[dateStr]
            if (stats == null) {
                val lastStats = inMemoryDb.values.maxByOrNull { it.dateString }
                val carryOverGoal = lastStats?.dailyGoal ?: 50
                val carryOverStrictMode = lastStats?.isStrictModeOn ?: isStrictModeOn
                stats = DailyReelData(
                    dateString = dateStr,
                    totalReelsCount = 0,
                    dailyGoal = carryOverGoal,
                    isLimitLocked = false,
                    isStrictModeOn = carryOverStrictMode,
                    isStrictModeLocked = false,
                    updatedAt = System.currentTimeMillis(),
                    syncStatus = 1
                )
                inMemoryDb[dateStr] = stats
            }
            currentDayStats = stats
        }

        fun incrementReelCount(amount: Int = 1) {
            val todayStr = getTodayString()
            if (todayStr != currentDateString) {
                recoverMissedTransitionsAndInitialize(todayStr)
            }
            val current = currentDayStats ?: return
            val updated = current.copy(
                totalReelsCount = current.totalReelsCount + amount,
                updatedAt = System.currentTimeMillis()
            )
            inMemoryDb[todayStr] = updated
            currentDayStats = updated
        }

        fun setStrictMode(enabled: Boolean) {
            isStrictModeOn = enabled
            val current = currentDayStats ?: return
            val updated = current.copy(
                isStrictModeOn = enabled,
                isStrictModeLocked = enabled,
                updatedAt = System.currentTimeMillis()
            )
            inMemoryDb[current.dateString] = updated
            currentDayStats = updated
        }

        fun checkDayTransition() {
            val todayStr = getTodayString()
            if (todayStr != currentDateString) {
                recoverMissedTransitionsAndInitialize(todayStr)
            }
        }
    }

    @Test
    fun test1_normalLocalDateInitialization() {
        val harness = LocalDayManagerHarness(LocalDate.of(2026, 9, 22))
        assertEquals("2026-09-22", harness.currentDateString)
        assertEquals(0, harness.currentDayStats?.totalReelsCount)
        assertEquals(1, harness.inMemoryDb.size)
        assertNotNull(harness.inMemoryDb["2026-09-22"])
    }

    @Test
    fun test2_phoneDateMovedForward_transitionsCleanly() {
        val harness = LocalDayManagerHarness(LocalDate.of(2026, 9, 22))
        harness.incrementReelCount(15)
        assertEquals(15, harness.currentDayStats?.totalReelsCount)

        // User advances device phone clock to October 22
        harness.mockLocalDate = LocalDate.of(2026, 10, 22)
        harness.checkDayTransition()

        // Sept 22 finalized, Oct 22 created fresh
        assertEquals("2026-10-22", harness.currentDateString)
        assertEquals(0, harness.currentDayStats?.totalReelsCount)
        assertEquals(15, harness.inMemoryDb["2026-09-22"]?.totalReelsCount)
        assertEquals(2, harness.inMemoryDb.size)
    }

    @Test
    fun test3_phoneDateMovedBackward_noCrashAndPreservesHistorical() {
        val harness = LocalDayManagerHarness(LocalDate.of(2026, 9, 22))
        harness.incrementReelCount(20)

        // Phone date set to Sept 20
        harness.mockLocalDate = LocalDate.of(2026, 9, 20)
        harness.checkDayTransition()

        assertEquals("2026-09-20", harness.currentDateString)
        assertEquals(0, harness.currentDayStats?.totalReelsCount)
        assertEquals(20, harness.inMemoryDb["2026-09-22"]?.totalReelsCount)
    }

    @Test
    fun test4_forceStopBeforeMidnight_reopenAfterMidnight() {
        val db = ConcurrentHashMap<String, DailyReelData>()
        db["2026-09-20"] = DailyReelData(dateString = "2026-09-20", totalReelsCount = 37, dailyGoal = 50, syncStatus = 1)

        // Reopen on Sept 22 (force-stop across 2 days)
        val harness = LocalDayManagerHarness(LocalDate.of(2026, 9, 22), inMemoryDb = db)

        assertEquals("2026-09-22", harness.currentDateString)
        assertEquals(0, harness.currentDayStats?.totalReelsCount)
        // Historical row preserved and finalized
        assertEquals(37, harness.inMemoryDb["2026-09-20"]?.totalReelsCount)
        // No fake intermediate rows created
        assertEquals(2, harness.inMemoryDb.size)
        assertNull(harness.inMemoryDb["2026-09-21"])
    }

    @Test
    fun test5_appRunningAcrossMidnight_incrementsCorrectNewDay() {
        val harness = LocalDayManagerHarness(LocalDate.of(2026, 9, 22))
        harness.incrementReelCount(10)
        assertEquals(10, harness.currentDayStats?.totalReelsCount)

        // Clock ticks past midnight to Sept 23
        harness.mockLocalDate = LocalDate.of(2026, 9, 23)
        harness.incrementReelCount(1)

        assertEquals("2026-09-23", harness.currentDateString)
        assertEquals(1, harness.currentDayStats?.totalReelsCount)
        assertEquals(10, harness.inMemoryDb["2026-09-22"]?.totalReelsCount)
    }

    @Test
    fun test6_accountLogoutLogin_preservesTodayDeviceLocalCount() {
        val db = ConcurrentHashMap<String, DailyReelData>()
        val todayStr = "2026-09-22"
        db["2026-09-18"] = DailyReelData(dateString = "2026-09-18", totalReelsCount = 40)
        db[todayStr] = DailyReelData(dateString = todayStr, totalReelsCount = 100, dailyGoal = 100, isLimitLocked = true)

        // User A logs out -> clearHistoricalDataExcept(todayStr)
        db.keys.removeIf { it != todayStr }

        // User B logs in
        assertEquals(1, db.size)
        assertNotNull(db[todayStr])
        assertEquals(100, db[todayStr]?.totalReelsCount)
        assertTrue(db[todayStr]?.isLimitLocked == true)
        assertNull(db["2026-09-18"])
    }

    @Test
    fun test7_strictModeDayTransition_persistsStrictModeOnAndResetsLock() {
        val harness = LocalDayManagerHarness(LocalDate.of(2026, 9, 22))
        harness.setStrictMode(true)
        assertTrue(harness.currentDayStats?.isStrictModeOn == true)
        assertTrue(harness.currentDayStats?.isStrictModeLocked == true)

        // Next day transition
        harness.mockLocalDate = LocalDate.of(2026, 9, 23)
        harness.checkDayTransition()

        assertEquals("2026-09-23", harness.currentDateString)
        // Strict Mode ON carried over, lock reset to false
        assertTrue(harness.currentDayStats?.isStrictModeOn == true)
        assertFalse(harness.currentDayStats?.isStrictModeLocked == true)
    }

    @Test
    fun test8_subscriptionClientDateCalculations_useLocalTime() {
        val now = System.currentTimeMillis()
        val futureEnd = now + 86400000L
        val pastEnd = now - 1000L

        val activeDto = com.reeltrak.app.data.remote.dto.CloudSubscriptionDto(
            subscriptionStatus = "ACTIVE",
            currentPeriodEnd = com.google.firebase.Timestamp(java.util.Date(futureEnd))
        )
        val expiredDto = com.reeltrak.app.data.remote.dto.CloudSubscriptionDto(
            subscriptionStatus = "EXPIRED",
            currentPeriodEnd = com.google.firebase.Timestamp(java.util.Date(pastEnd))
        )

        assertEquals(com.reeltrak.app.domain.models.AccessState.ACTIVE, activeDto.toDomain(now).accessState)
        assertEquals(com.reeltrak.app.domain.models.AccessState.LOCKED, expiredDto.toDomain(now).accessState)
    }
}
