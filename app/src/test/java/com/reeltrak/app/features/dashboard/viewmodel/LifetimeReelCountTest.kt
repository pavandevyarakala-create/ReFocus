package com.reeltrak.app.features.dashboard.viewmodel

import com.reeltrak.app.data.local.entity.DailyReelData
import com.reeltrak.app.services.overlay.OverlayState
import com.reeltrak.app.services.tracking.SessionState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Rigorous test suite validating the Lifetime Reel Count calculation in Re:Focus.
 *
 * Verifies that every reel is counted EXACTLY ONCE and that:
 * 1. No active session + historical Room data computes correct total.
 * 2. Active session with today's Room count already containing session reels does NOT double-count.
 * 3. Active session with an unpersisted live delta accounts for the delta exactly once.
 * 4. Session termination does not suddenly drop or double the lifetime count.
 * 5. Multiple sessions on the same day accumulate accurately.
 * 6. Multiple historical days are fully included without omission.
 * 7. Background -> Foreground transitions preserve the exact count.
 * 8. Dashboard/ViewModel recreation yields consistent, deterministic values.
 * 9. Midnight rollover transitions cleanly without reel duplication or loss.
 * 10. Zero-reel session does not alter lifetime total.
 * 11. Repeated collection/emission of the same state emits identical value.
 * 12. Lifetime count never increases merely because duplicate data is emitted.
 */
class LifetimeReelCountTest {

    private val today = "2026-09-27"
    private val yesterday = "2026-09-26"
    private val dayBeforeYesterday = "2026-09-25"

    private fun idleOverlay(): OverlayState {
        return OverlayState(
            isVisible = false,
            sessionState = SessionState.IDLE,
            currentSessionReels = 0
        )
    }

    private fun activeOverlay(sessionReels: Int): OverlayState {
        return OverlayState(
            isVisible = true,
            sessionState = SessionState.ACTIVE,
            currentSessionReels = sessionReels
        )
    }

    // =========================================================================
    // 1. No active session + historical Room data
    // =========================================================================
    @Test
    fun test01_noActiveSessionWithHistoricalData() {
        val dataList = listOf(
            DailyReelData(dateString = dayBeforeYesterday, totalReelsCount = 30),
            DailyReelData(dateString = yesterday, totalReelsCount = 50),
            DailyReelData(dateString = today, totalReelsCount = 0)
        )
        val todayStats = DailyReelData(dateString = today, totalReelsCount = 0)
        val live = idleOverlay()

        val lifetime = DashboardViewModel.calculateLifetimeReels(
            dataList = dataList,
            todayStr = today,
            todayStats = todayStats,
            live = live
        )

        assertEquals(80L, lifetime)
    }

    // =========================================================================
    // 2. Active session with today's Room count already containing session reels (NO DOUBLE-COUNTING)
    // =========================================================================
    @Test
    fun test02_activeSessionDoesNotDoubleCountPersistedReels() {
        // User watched 15 reels in the active session.
        // DayManager.incrementReelCount has persisted totalReelsCount = 15 to today's Room record.
        val dataList = listOf(
            DailyReelData(dateString = yesterday, totalReelsCount = 40),
            DailyReelData(dateString = today, totalReelsCount = 15)
        )
        val todayStats = DailyReelData(dateString = today, totalReelsCount = 15)
        val live = activeOverlay(sessionReels = 15)

        val lifetime = DashboardViewModel.calculateLifetimeReels(
            dataList = dataList,
            todayStr = today,
            todayStats = todayStats,
            live = live
        )

        // Must be 40 + 15 = 55. MUST NOT be 40 + 15 + 15 = 70!
        assertEquals(55L, lifetime)
    }

    // =========================================================================
    // 3. Active session where a live delta genuinely has not yet been persisted
    // =========================================================================
    @Test
    fun test03_activeSessionWithGenuineUnpersistedDelta() {
        // Suppose Room has 10 reels for today, but live session has 12 reels (unpersisted delta of 2).
        val dataList = listOf(
            DailyReelData(dateString = yesterday, totalReelsCount = 50),
            DailyReelData(dateString = today, totalReelsCount = 10)
        )
        val todayStats = DailyReelData(dateString = today, totalReelsCount = 10)
        val live = activeOverlay(sessionReels = 12)

        val lifetime = DashboardViewModel.calculateLifetimeReels(
            dataList = dataList,
            todayStr = today,
            todayStats = todayStats,
            live = live
        )

        // 50 (yesterday) + 10 (today persisted) + 2 (unpersisted delta) = 62
        assertEquals(62L, lifetime)
    }

    // =========================================================================
    // 4. Session termination: lifetime count must not suddenly drop or change
    // =========================================================================
    @Test
    fun test04_sessionTerminationPreservesExactCount() {
        val dataListDuring = listOf(
            DailyReelData(dateString = yesterday, totalReelsCount = 100),
            DailyReelData(dateString = today, totalReelsCount = 25)
        )
        val todayStatsDuring = DailyReelData(dateString = today, totalReelsCount = 25)
        val liveDuring = activeOverlay(sessionReels = 25)

        val countDuring = DashboardViewModel.calculateLifetimeReels(
            dataList = dataListDuring,
            todayStr = today,
            todayStats = todayStatsDuring,
            live = liveDuring
        )

        // Session ends -> live session state becomes IDLE with 0 session reels.
        // Today's totalReelsCount in Room and DayManager remains 25.
        val liveAfter = idleOverlay()
        val countAfter = DashboardViewModel.calculateLifetimeReels(
            dataList = dataListDuring,
            todayStr = today,
            todayStats = todayStatsDuring,
            live = liveAfter
        )

        assertEquals(125L, countDuring)
        assertEquals(125L, countAfter)
        assertEquals("Lifetime count must be identical before and after session termination", countDuring, countAfter)
    }

    // =========================================================================
    // 5. Multiple sessions on the same day
    // =========================================================================
    @Test
    fun test05_multipleSessionsOnSameDay() {
        val historical = listOf(DailyReelData(dateString = yesterday, totalReelsCount = 60))

        // Session 1: User watches 10 reels
        val dataAfterSession1 = historical + listOf(DailyReelData(dateString = today, totalReelsCount = 10))
        val statsAfterSession1 = DailyReelData(dateString = today, totalReelsCount = 10)
        val countAfterSession1 = DashboardViewModel.calculateLifetimeReels(
            dataList = dataAfterSession1,
            todayStr = today,
            todayStats = statsAfterSession1,
            live = idleOverlay()
        )
        assertEquals(70L, countAfterSession1)

        // Session 2: User watches 5 more reels in an active session (session counter = 5, total today = 15)
        val dataDuringSession2 = historical + listOf(DailyReelData(dateString = today, totalReelsCount = 15))
        val statsDuringSession2 = DailyReelData(dateString = today, totalReelsCount = 15)
        val countDuringSession2 = DashboardViewModel.calculateLifetimeReels(
            dataList = dataDuringSession2,
            todayStr = today,
            todayStats = statsDuringSession2,
            live = activeOverlay(sessionReels = 5)
        )
        // 60 + 15 = 75. Must NOT be 60 + 15 + 5 = 80!
        assertEquals(75L, countDuringSession2)

        // Session 2 ends
        val countAfterSession2 = DashboardViewModel.calculateLifetimeReels(
            dataList = dataDuringSession2,
            todayStr = today,
            todayStats = statsDuringSession2,
            live = idleOverlay()
        )
        assertEquals(75L, countAfterSession2)
    }

    // =========================================================================
    // 6. Multiple historical days
    // =========================================================================
    @Test
    fun test06_multipleHistoricalDays() {
        val dataList = listOf(
            DailyReelData(dateString = "2026-09-20", totalReelsCount = 10),
            DailyReelData(dateString = "2026-09-21", totalReelsCount = 20),
            DailyReelData(dateString = "2026-09-22", totalReelsCount = 30),
            DailyReelData(dateString = "2026-09-23", totalReelsCount = 40),
            DailyReelData(dateString = "2026-09-24", totalReelsCount = 50),
            DailyReelData(dateString = "2026-09-25", totalReelsCount = 60),
            DailyReelData(dateString = "2026-09-26", totalReelsCount = 70),
            DailyReelData(dateString = today, totalReelsCount = 8)
        )
        val todayStats = DailyReelData(dateString = today, totalReelsCount = 8)

        val lifetime = DashboardViewModel.calculateLifetimeReels(
            dataList = dataList,
            todayStr = today,
            todayStats = todayStats,
            live = idleOverlay()
        )

        // Sum(10..70) = 280 + 8 = 288
        assertEquals(288L, lifetime)
    }

    // =========================================================================
    // 7. Background -> Foreground transition
    // =========================================================================
    @Test
    fun test07_backgroundToForegroundTransition() {
        val dataList = listOf(
            DailyReelData(dateString = yesterday, totalReelsCount = 100),
            DailyReelData(dateString = today, totalReelsCount = 20)
        )
        val todayStats = DailyReelData(dateString = today, totalReelsCount = 20)

        // App in foreground
        val foregroundCount1 = DashboardViewModel.calculateLifetimeReels(
            dataList = dataList,
            todayStr = today,
            todayStats = todayStats,
            live = idleOverlay()
        )

        // App backgrounded (OverlayState might toggle screen ready or paused)
        val pausedOverlay = OverlayState(isVisible = false, sessionState = SessionState.PAUSED, currentSessionReels = 0)
        val backgroundCount = DashboardViewModel.calculateLifetimeReels(
            dataList = dataList,
            todayStr = today,
            todayStats = todayStats,
            live = pausedOverlay
        )

        // App foregrounded again
        val foregroundCount2 = DashboardViewModel.calculateLifetimeReels(
            dataList = dataList,
            todayStr = today,
            todayStats = todayStats,
            live = idleOverlay()
        )

        assertEquals(120L, foregroundCount1)
        assertEquals(120L, backgroundCount)
        assertEquals(120L, foregroundCount2)
    }

    // =========================================================================
    // 8. Dashboard / ViewModel recreation
    // =========================================================================
    @Test
    fun test08_viewModelRecreationDeterministic() {
        val dataList = listOf(
            DailyReelData(dateString = yesterday, totalReelsCount = 77),
            DailyReelData(dateString = today, totalReelsCount = 13)
        )
        val todayStats = DailyReelData(dateString = today, totalReelsCount = 13)
        val live = activeOverlay(sessionReels = 13)

        // Instance 1
        val val1 = DashboardViewModel.calculateLifetimeReels(dataList, today, todayStats, live)
        // Instance 2 (simulating fresh ViewModel injection)
        val val2 = DashboardViewModel.calculateLifetimeReels(dataList, today, todayStats, live)

        assertEquals(90L, val1)
        assertEquals(val1, val2)
    }

    // =========================================================================
    // 9. Midnight rollover
    // =========================================================================
    @Test
    fun test09_midnightRollover() {
        val tomorrow = "2026-09-28"

        // At 23:59:59 on 2026-09-27
        val dataBeforeMidnight = listOf(
            DailyReelData(dateString = yesterday, totalReelsCount = 50),
            DailyReelData(dateString = today, totalReelsCount = 30)
        )
        val statsBeforeMidnight = DailyReelData(dateString = today, totalReelsCount = 30)
        val countBeforeMidnight = DashboardViewModel.calculateLifetimeReels(
            dataList = dataBeforeMidnight,
            todayStr = today,
            todayStats = statsBeforeMidnight,
            live = idleOverlay()
        )
        assertEquals(80L, countBeforeMidnight)

        // At 00:00:01 on 2026-09-28 (midnight transition)
        // Today becomes tomorrow (2026-09-28), yesterday's count (30) is now finalized historical
        val dataAfterMidnight = dataBeforeMidnight + listOf(DailyReelData(dateString = tomorrow, totalReelsCount = 0))
        val statsAfterMidnight = DailyReelData(dateString = tomorrow, totalReelsCount = 0)
        val countAfterMidnight = DashboardViewModel.calculateLifetimeReels(
            dataList = dataAfterMidnight,
            todayStr = tomorrow,
            todayStats = statsAfterMidnight,
            live = idleOverlay()
        )

        // Must be exactly 80: no lost reels from yesterday, no phantom reels added
        assertEquals(80L, countAfterMidnight)
    }

    // =========================================================================
    // 10. Zero-reel session
    // =========================================================================
    @Test
    fun test10_zeroReelSessionDoesNotAlterLifetime() {
        val dataList = listOf(
            DailyReelData(dateString = yesterday, totalReelsCount = 45),
            DailyReelData(dateString = today, totalReelsCount = 0)
        )
        val todayStats = DailyReelData(dateString = today, totalReelsCount = 0)

        // Entering reels (0 reels watched)
        val enteringOverlay = OverlayState(isVisible = true, sessionState = SessionState.ENTERING_REELS, currentSessionReels = 0)
        val countEntering = DashboardViewModel.calculateLifetimeReels(dataList, today, todayStats, enteringOverlay)
        assertEquals(45L, countEntering)

        // Active session with 0 reels
        val activeZeroOverlay = OverlayState(isVisible = true, sessionState = SessionState.ACTIVE, currentSessionReels = 0)
        val countActiveZero = DashboardViewModel.calculateLifetimeReels(dataList, today, todayStats, activeZeroOverlay)
        assertEquals(45L, countActiveZero)

        // Exiting without scrolling (0 reels)
        val countExited = DashboardViewModel.calculateLifetimeReels(dataList, today, todayStats, idleOverlay())
        assertEquals(45L, countExited)
    }

    // =========================================================================
    // 11. Repeated collection/emission of identical state
    // =========================================================================
    @Test
    fun test11_repeatedCollectionOfSameStateProducesIdenticalValue() = runBlocking {
        val allDailyDataFlow = MutableStateFlow(listOf(
            DailyReelData(dateString = yesterday, totalReelsCount = 50),
            DailyReelData(dateString = today, totalReelsCount = 10)
        ))
        val currentDateFlow = MutableStateFlow(today)
        val currentDayStatsFlow = MutableStateFlow<DailyReelData?>(DailyReelData(dateString = today, totalReelsCount = 10))
        val liveSessionFlow = MutableStateFlow(activeOverlay(10))

        val emittedValues = mutableListOf<Long>()
        val job = launch {
            combine(
                allDailyDataFlow,
                currentDateFlow,
                currentDayStatsFlow,
                liveSessionFlow
            ) { dataList, todayStr, todayStats, live ->
                DashboardViewModel.calculateLifetimeReels(dataList, todayStr, todayStats, live)
            }.distinctUntilChanged().collect {
                emittedValues.add(it)
            }
        }

        delay(50)

        // Re-emit identical states multiple times
        allDailyDataFlow.value = allDailyDataFlow.value.toList()
        currentDayStatsFlow.value = currentDayStatsFlow.value?.copy()
        liveSessionFlow.value = liveSessionFlow.value.copy()

        delay(50)
        job.cancel()

        // Because distinctUntilChanged is used, exactly 1 emission should have occurred
        assertEquals(1, emittedValues.size)
        assertEquals(60L, emittedValues[0])
    }

    // =========================================================================
    // 12. Lifetime count never increases merely because duplicate data is emitted
    // =========================================================================
    @Test
    fun test12_lifetimeCountNeverIncreasesOnDuplicateEmissions() {
        val dataList = listOf(
            DailyReelData(dateString = yesterday, totalReelsCount = 20),
            DailyReelData(dateString = today, totalReelsCount = 5)
        )
        val todayStats = DailyReelData(dateString = today, totalReelsCount = 5)
        val live = activeOverlay(sessionReels = 5)

        val firstRun = DashboardViewModel.calculateLifetimeReels(dataList, today, todayStats, live)
        for (i in 1..100) {
            val repeatedRun = DashboardViewModel.calculateLifetimeReels(dataList, today, todayStats, live)
            assertEquals("Run $i must match exactly", firstRun, repeatedRun)
        }
        assertEquals(25L, firstRun)
    }

    // =========================================================================
    // 13. Edge case: Room write latency where in-memory stats is 1 ahead of Room
    // =========================================================================
    @Test
    fun test13_inMemoryStatsAheadOfRoomWriteLatency() {
        // Room still has 9 reels, but DayManager memory has updated to 10 reels
        val dataList = listOf(
            DailyReelData(dateString = yesterday, totalReelsCount = 30),
            DailyReelData(dateString = today, totalReelsCount = 9)
        )
        val todayStats = DailyReelData(dateString = today, totalReelsCount = 10)
        val live = activeOverlay(sessionReels = 10)

        val count = DashboardViewModel.calculateLifetimeReels(
            dataList = dataList,
            todayStr = today,
            todayStats = todayStats,
            live = live
        )

        // Coalesces to maxOf(9, 10) = 10. Lifetime = 30 + 10 = 40.
        assertEquals(40L, count)
    }

    // =========================================================================
    // 14. Average Reels Per Day: First day with 0 reels
    // =========================================================================
    @Test
    fun test14_avgReelsPerDay_firstDayWithZeroReels() {
        val dataList = emptyList<DailyReelData>()
        val todayStats = DailyReelData(dateString = today, totalReelsCount = 0)
        val lifetime = DashboardViewModel.calculateLifetimeReels(dataList, today, todayStats, idleOverlay())
        val avg = DashboardViewModel.calculateAvgReelsPerDay(lifetime, dataList, today, todayStats)

        assertEquals(0L, lifetime)
        assertEquals(0, avg)
    }

    // =========================================================================
    // 15. Average Reels Per Day: First day with active reels (e.g. 15 reels)
    // =========================================================================
    @Test
    fun test15_avgReelsPerDay_firstDayWithActiveReels() {
        val dataList = listOf(DailyReelData(dateString = today, totalReelsCount = 15))
        val todayStats = DailyReelData(dateString = today, totalReelsCount = 15)
        val live = activeOverlay(sessionReels = 15)

        val lifetime = DashboardViewModel.calculateLifetimeReels(dataList, today, todayStats, live)
        val avg = DashboardViewModel.calculateAvgReelsPerDay(lifetime, dataList, today, todayStats)

        // 15 reels on day 1 -> avg must be 15
        assertEquals(15L, lifetime)
        assertEquals(15, avg)
    }

    // =========================================================================
    // 16. Average Reels Per Day: Several consecutive days (e.g. 20, 30, 40)
    // =========================================================================
    @Test
    fun test16_avgReelsPerDay_severalConsecutiveDays() {
        val dataList = listOf(
            DailyReelData(dateString = dayBeforeYesterday, totalReelsCount = 20),
            DailyReelData(dateString = yesterday, totalReelsCount = 30),
            DailyReelData(dateString = today, totalReelsCount = 40)
        )
        val todayStats = DailyReelData(dateString = today, totalReelsCount = 40)

        val lifetime = DashboardViewModel.calculateLifetimeReels(dataList, today, todayStats, idleOverlay())
        val avg = DashboardViewModel.calculateAvgReelsPerDay(lifetime, dataList, today, todayStats)

        // 20 + 30 + 40 = 90 reels over 3 days -> avg = 30
        assertEquals(90L, lifetime)
        assertEquals(30, avg)
    }

    // =========================================================================
    // 17. Average Reels Per Day: Several days with no reels / missing rows
    // =========================================================================
    @Test
    fun test17_avgReelsPerDay_nonConsecutiveDaysMissingRows() {
        // App used on 2026-09-20 (60 reels), not opened until today 2026-09-27 (today count = 0)
        val dataList = listOf(
            DailyReelData(dateString = "2026-09-20", totalReelsCount = 60),
            DailyReelData(dateString = today, totalReelsCount = 0)
        )
        val todayStats = DailyReelData(dateString = today, totalReelsCount = 0)

        val lifetime = DashboardViewModel.calculateLifetimeReels(dataList, today, todayStats, idleOverlay())
        val avg = DashboardViewModel.calculateAvgReelsPerDay(lifetime, dataList, today, todayStats)

        // 1 historical tracked day + 1 today = 2 tracked days. 60 / 2 = 30
        assertEquals(60L, lifetime)
        assertEquals(30, avg)
    }

    // =========================================================================
    // 18. Average Reels Per Day: Today included while actively scrolling
    // =========================================================================
    @Test
    fun test18_avgReelsPerDay_todayIncludedWhileActivelyScrolling() {
        val dataList = listOf(
            DailyReelData(dateString = yesterday, totalReelsCount = 50),
            DailyReelData(dateString = today, totalReelsCount = 10)
        )
        val todayStats = DailyReelData(dateString = today, totalReelsCount = 10)
        val live = activeOverlay(sessionReels = 10)

        val lifetime = DashboardViewModel.calculateLifetimeReels(dataList, today, todayStats, live)
        val avg = DashboardViewModel.calculateAvgReelsPerDay(lifetime, dataList, today, todayStats)

        // Total 60 over 2 days -> avg = 30
        assertEquals(60L, lifetime)
        assertEquals(30, avg)
    }

    // =========================================================================
    // 19. Average Reels Per Day: Session termination does not alter average
    // =========================================================================
    @Test
    fun test19_avgReelsPerDay_sessionTerminationPreservesAverage() {
        val dataList = listOf(
            DailyReelData(dateString = yesterday, totalReelsCount = 50),
            DailyReelData(dateString = today, totalReelsCount = 10)
        )
        val todayStats = DailyReelData(dateString = today, totalReelsCount = 10)

        val liveDuring = activeOverlay(sessionReels = 10)
        val avgDuring = DashboardViewModel.calculateAvgReelsPerDay(
            DashboardViewModel.calculateLifetimeReels(dataList, today, todayStats, liveDuring),
            dataList, today, todayStats
        )

        val liveAfter = idleOverlay()
        val avgAfter = DashboardViewModel.calculateAvgReelsPerDay(
            DashboardViewModel.calculateLifetimeReels(dataList, today, todayStats, liveAfter),
            dataList, today, todayStats
        )

        assertEquals(30, avgDuring)
        assertEquals(30, avgAfter)
        assertEquals("Average reels per day must remain identical across session termination", avgDuring, avgAfter)
    }

    // =========================================================================
    // 20. Average Reels Per Day: Multiple sessions on same day
    // =========================================================================
    @Test
    fun test20_avgReelsPerDay_multipleSessionsSameDay() {
        val historical = listOf(DailyReelData(dateString = yesterday, totalReelsCount = 40))

        // Session 1: 10 reels (today total = 10)
        val dataList1 = historical + listOf(DailyReelData(dateString = today, totalReelsCount = 10))
        val todayStats1 = DailyReelData(dateString = today, totalReelsCount = 10)
        val avg1 = DashboardViewModel.calculateAvgReelsPerDay(
            DashboardViewModel.calculateLifetimeReels(dataList1, today, todayStats1, idleOverlay()),
            dataList1, today, todayStats1
        )
        // 50 / 2 = 25
        assertEquals(25, avg1)

        // Session 2: 10 more reels (today total = 20)
        val dataList2 = historical + listOf(DailyReelData(dateString = today, totalReelsCount = 20))
        val todayStats2 = DailyReelData(dateString = today, totalReelsCount = 20)
        val avg2 = DashboardViewModel.calculateAvgReelsPerDay(
            DashboardViewModel.calculateLifetimeReels(dataList2, today, todayStats2, activeOverlay(sessionReels = 10)),
            dataList2, today, todayStats2
        )
        // 60 / 2 = 30
        assertEquals(30, avg2)
    }

    // =========================================================================
    // 21. Average Reels Per Day: Midnight rollover
    // =========================================================================
    @Test
    fun test21_avgReelsPerDay_midnightRollover() {
        val tomorrow = "2026-09-28"

        // Before midnight on day 1 (10 reels watched)
        val dataDay1 = listOf(DailyReelData(dateString = today, totalReelsCount = 10))
        val statsDay1 = DailyReelData(dateString = today, totalReelsCount = 10)
        val avgBefore = DashboardViewModel.calculateAvgReelsPerDay(
            DashboardViewModel.calculateLifetimeReels(dataDay1, today, statsDay1, idleOverlay()),
            dataDay1, today, statsDay1
        )
        // 10 / 1 = 10
        assertEquals(10, avgBefore)

        // Immediately after midnight: day 1 is finalized historical (10 reels), new day starts with 0 reels
        val dataDay2 = dataDay1 + listOf(DailyReelData(dateString = tomorrow, totalReelsCount = 0))
        val statsDay2 = DailyReelData(dateString = tomorrow, totalReelsCount = 0)
        val avgAfter = DashboardViewModel.calculateAvgReelsPerDay(
            DashboardViewModel.calculateLifetimeReels(dataDay2, tomorrow, statsDay2, idleOverlay()),
            dataDay2, tomorrow, statsDay2
        )
        // 10 total reels / 2 days = 5
        assertEquals(5, avgAfter)
    }

    // =========================================================================
    // 22. Historical data containing zero-count days
    // =========================================================================
    @Test
    fun test22_avgReelsPerDay_historicalZeroCountDays() {
        val dataList = listOf(
            DailyReelData(dateString = "2026-09-24", totalReelsCount = 30),
            DailyReelData(dateString = "2026-09-25", totalReelsCount = 0), // tracked day with 0 reels
            DailyReelData(dateString = "2026-09-26", totalReelsCount = 30),
            DailyReelData(dateString = today, totalReelsCount = 0)
        )
        val todayStats = DailyReelData(dateString = today, totalReelsCount = 0)

        val lifetime = DashboardViewModel.calculateLifetimeReels(dataList, today, todayStats, idleOverlay())
        val avg = DashboardViewModel.calculateAvgReelsPerDay(lifetime, dataList, today, todayStats)

        // 3 historical days + today = 4 tracked days. Total = 60. 60 / 4 = 15
        assertEquals(60L, lifetime)
        assertEquals(15, avg)
    }

    // =========================================================================
    // 23. Future-dated row rejection: must not distort lifetime total or average
    // =========================================================================
    @Test
    fun test23_futureDatedRowRejection() {
        val futureDate = "2099-01-01"
        val dataList = listOf(
            DailyReelData(dateString = yesterday, totalReelsCount = 20),
            DailyReelData(dateString = today, totalReelsCount = 10),
            DailyReelData(dateString = futureDate, totalReelsCount = 9999) // corrupt future record
        )
        val todayStats = DailyReelData(dateString = today, totalReelsCount = 10)

        val lifetime = DashboardViewModel.calculateLifetimeReels(dataList, today, todayStats, idleOverlay())
        val avg = DashboardViewModel.calculateAvgReelsPerDay(lifetime, dataList, today, todayStats)

        // Future record (9999) must be ignored:
        // Lifetime = 20 (yesterday) + 10 (today) = 30
        // Total tracked days = 1 (yesterday) + 1 (today) = 2. Future day is rejected.
        // Avg = 30 / 2 = 15
        assertEquals(30L, lifetime)
        assertEquals(15, avg)
    }

    // =========================================================================
    // 24. Today's Room record not yet created: avgReelsPerDay does not jitter
    // =========================================================================
    @Test
    fun test24_roomTodayRecordNotYetCreatedDoesNotJitterAverage() {
        // Only yesterday is in Room. Today has not yet written to Room, but DayManager has 10 in memory.
        val dataList = listOf(DailyReelData(dateString = yesterday, totalReelsCount = 20))
        val todayStats = DailyReelData(dateString = today, totalReelsCount = 10)

        val lifetime = DashboardViewModel.calculateLifetimeReels(dataList, today, todayStats, idleOverlay())
        val avg = DashboardViewModel.calculateAvgReelsPerDay(lifetime, dataList, today, todayStats)

        // 20 + 10 = 30 reels over 2 days (yesterday + today) -> avg = 15
        assertEquals(30L, lifetime)
        assertEquals(15, avg)
    }

    // =========================================================================
    // 25. Lifetime total vs analytics daily sum consistency
    // =========================================================================
    @Test
    fun test25_lifetimeTotalEqualsSumOfDailyTotals() {
        val dataList = listOf(
            DailyReelData(dateString = "2026-09-24", totalReelsCount = 14),
            DailyReelData(dateString = "2026-09-25", totalReelsCount = 28),
            DailyReelData(dateString = yesterday, totalReelsCount = 42),
            DailyReelData(dateString = today, totalReelsCount = 16)
        )
        val todayStats = DailyReelData(dateString = today, totalReelsCount = 16)

        val lifetime = DashboardViewModel.calculateLifetimeReels(dataList, today, todayStats, idleOverlay())
        val sumOfDaily = dataList.filter { it.dateString <= today }.sumOf { it.totalReelsCount.toLong() }

        assertEquals(sumOfDaily, lifetime)
        assertEquals(100L, lifetime)
    }
}
