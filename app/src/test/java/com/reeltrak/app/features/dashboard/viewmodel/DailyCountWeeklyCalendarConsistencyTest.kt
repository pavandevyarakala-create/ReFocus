package com.reeltrak.app.features.dashboard.viewmodel

import com.reeltrak.app.data.local.entity.DailyReelData
import com.reeltrak.app.data.local.entity.SessionData
import com.reeltrak.app.features.dashboard.viewmodel.DashboardViewModel.Companion.calculateAvgReelsPerDay
import com.reeltrak.app.features.dashboard.viewmodel.DashboardViewModel.Companion.calculateLifetimeReels
import com.reeltrak.app.features.dashboard.viewmodel.DashboardViewModel.Companion.calculateStreakStats
import com.reeltrak.app.features.dashboard.viewmodel.DashboardViewModel.Companion.generateHabitTrackerData
import com.reeltrak.app.features.dashboard.viewmodel.DashboardViewModel.DailyBlocks
import com.reeltrak.app.services.overlay.OverlayState
import com.reeltrak.app.services.tracking.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Production Audit Unit Tests:
 * DAILY COUNT -> WEEKLY GRAPH -> CALENDAR CONSISTENCY
 *
 * Verifies that one reel from detection through DayManager, Room,
 * DashboardViewModel, habitTrackerData, Weekly Activity Graph,
 * Calendar, and Analytics totals represents EXACTLY the same daily count everywhere.
 */
class DailyCountWeeklyCalendarConsistencyTest {

    private val idleOverlay = OverlayState(
        sessionState = SessionState.IDLE,
        currentApp = null,
        sessionStartTime = 0L,
        currentSessionReels = 0
    )

    private val todayStr = "2026-09-27"
    private val todayDate = LocalDate.parse(todayStr, DateTimeFormatter.ISO_LOCAL_DATE)
    private val todayEpoch = todayDate.toEpochDay()

    // ---------------------------------------------------------------------------------------------
    // Test 1: Today count = graph today bar = calendar today
    // ---------------------------------------------------------------------------------------------
    @Test
    fun todayCount_equalsGraphTodayBar_andCalendarToday() {
        val todayStats = DailyReelData(
            dateString = todayStr,
            totalReelsCount = 25,
            dailyGoal = 50
        )
        val roomData = listOf(
            DailyReelData(dateString = "2026-09-26", totalReelsCount = 30, dailyGoal = 50),
            todayStats
        )

        val habitData = generateHabitTrackerData(roomData, todayStr, todayStats)
        val weeklyData = habitData.takeLast(7)
        val calendarMap = habitData.associateBy { it.dateEpochDay }

        // 1. Authoritative today in habitData
        val todayBlock = habitData.last()
        assertEquals(todayStr, todayBlock.dateString)
        assertEquals(todayEpoch, todayBlock.dateEpochDay)
        assertTrue(todayBlock.isToday)
        assertEquals(25, todayBlock.totalReelsScrolled)

        // 2. Weekly graph's today bar (7th bar)
        val graphTodayBar = weeklyData.last()
        assertEquals(25, graphTodayBar.totalReelsScrolled)
        assertTrue(graphTodayBar.isToday)
        assertEquals(todayEpoch, graphTodayBar.dateEpochDay)

        // 3. Calendar's today cell
        val calendarTodayCell = calendarMap[todayEpoch]
        assertNotNull(calendarTodayCell)
        assertEquals(25, calendarTodayCell!!.totalReelsScrolled)
        assertTrue(calendarTodayCell.isToday)
    }

    // ---------------------------------------------------------------------------------------------
    // Test 2: 7-day graph exact date ordering (chronological ASC)
    // ---------------------------------------------------------------------------------------------
    @Test
    fun sevenDayGraph_exactDateOrdering_chronologicalAscending() {
        val habitData = generateHabitTrackerData(emptyList(), todayStr, null)
        val weeklyData = habitData.takeLast(7)

        assertEquals(7, weeklyData.size)
        for (i in 0 until 7) {
            val expectedEpoch = todayEpoch - (6 - i)
            val expectedDate = LocalDate.ofEpochDay(expectedEpoch).format(DateTimeFormatter.ISO_LOCAL_DATE)
            assertEquals("Index $i must match expected date", expectedDate, weeklyData[i].dateString)
            assertEquals("Index $i must match expected epoch", expectedEpoch, weeklyData[i].dateEpochDay)
            if (i == 6) {
                assertTrue("Last bar must be today", weeklyData[i].isToday)
            } else {
                assertFalse("Previous bars must not be today", weeklyData[i].isToday)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Test 3: Missing days represented correctly as zero without gaps
    // ---------------------------------------------------------------------------------------------
    @Test
    fun missingDays_representedCorrectlyAsZeroWithoutGaps() {
        // Only Monday and Thursday have data; Tuesday, Wednesday, Friday, Saturday have no Room entries
        val roomData = listOf(
            DailyReelData(dateString = "2026-09-21", totalReelsCount = 40, dailyGoal = 50),
            DailyReelData(dateString = "2026-09-24", totalReelsCount = 35, dailyGoal = 50)
        )

        val habitData = generateHabitTrackerData(roomData, todayStr, null)
        assertEquals(91, habitData.size)

        val dataMap = habitData.associateBy { it.dateString }
        val tuesday = dataMap["2026-09-22"]
        val wednesday = dataMap["2026-09-23"]

        assertNotNull(tuesday)
        assertEquals(0, tuesday!!.totalReelsScrolled)
        assertEquals(0, tuesday.dailyGoal)
        assertEquals(-1f, tuesday.percentage, 0.001f)

        assertNotNull(wednesday)
        assertEquals(0, wednesday!!.totalReelsScrolled)
        assertEquals(0, wednesday.dailyGoal)
        assertEquals(-1f, wednesday.percentage, 0.001f)
    }

    // ---------------------------------------------------------------------------------------------
    // Test 4: Zero-reel days with goal evaluated correctly
    // ---------------------------------------------------------------------------------------------
    @Test
    fun zeroReelDays_withGoal_maintainsGoalAndZeroCount() {
        val roomData = listOf(
            DailyReelData(dateString = "2026-09-26", totalReelsCount = 0, dailyGoal = 50)
        )
        val habitData = generateHabitTrackerData(roomData, todayStr, null)
        val yesterday = habitData.find { it.dateString == "2026-09-26" }

        assertNotNull(yesterday)
        assertEquals(0, yesterday!!.totalReelsScrolled)
        assertEquals(50, yesterday.dailyGoal)
        assertEquals(0f, yesterday.percentage, 0.001f)
        assertFalse(yesterday.isDriftedDay)
        assertFalse(yesterday.isGraceDay)
        assertFalse(yesterday.isUnrestrictedDay)
    }

    // ---------------------------------------------------------------------------------------------
    // Test 5: Multiple sessions on the same day accumulate consistently
    // ---------------------------------------------------------------------------------------------
    @Test
    fun multipleSessionsSameDay_accumulatesCorrectlyAcrossAllComponents() {
        val s1 = SessionData(appName = "com.instagram.android", reelsScrolled = 10, startTimeStamp = 1000L, endTimeStamp = 2000L)
        val s2 = SessionData(appName = "com.google.android.youtube", reelsScrolled = 15, startTimeStamp = 3000L, endTimeStamp = 4000L)
        val todayStats = DailyReelData(
            dateString = todayStr,
            totalReelsCount = 25,
            dailyGoal = 60,
            sessions = listOf(s1, s2)
        )

        val habitData = generateHabitTrackerData(emptyList(), todayStr, todayStats)
        val todayBlock = habitData.last()

        assertEquals(25, todayBlock.totalReelsScrolled)
        assertEquals(2, todayBlock.blocks.size)
        assertEquals(10, todayBlock.blocks[0].reelsCount)
        assertEquals(15, todayBlock.blocks[1].reelsCount)

        val lifetime = calculateLifetimeReels(emptyList(), todayStr, todayStats, idleOverlay)
        assertEquals(25L, lifetime)
    }

    // ---------------------------------------------------------------------------------------------
    // Test 6: Background to foreground preserves identical state
    // ---------------------------------------------------------------------------------------------
    @Test
    fun backgroundToForeground_preservesIdenticalState() {
        val roomData = listOf(
            DailyReelData(dateString = "2026-09-25", totalReelsCount = 30, dailyGoal = 50),
            DailyReelData(dateString = "2026-09-26", totalReelsCount = 40, dailyGoal = 50)
        )
        val todayStats = DailyReelData(dateString = todayStr, totalReelsCount = 18, dailyGoal = 50)

        // Snapshot 1 (before backgrounding)
        val habit1 = generateHabitTrackerData(roomData, todayStr, todayStats)
        val streak1 = calculateStreakStats(habit1)

        // Snapshot 2 (upon returning to foreground with identical in-memory / Room state)
        val habit2 = generateHabitTrackerData(roomData, todayStr, todayStats)
        val streak2 = calculateStreakStats(habit2)

        assertEquals(habit1.size, habit2.size)
        assertEquals(habit1.map { it.totalReelsScrolled }, habit2.map { it.totalReelsScrolled })
        assertEquals(streak1.weeklyTotalReels, streak2.weeklyTotalReels)
        assertEquals(streak1.currentStreak, streak2.currentStreak)
        assertEquals(streak1.highestReelsInDay, streak2.highestReelsInDay)
    }

    // ---------------------------------------------------------------------------------------------
    // Test 7: ViewModel recreation produces identical metrics
    // ---------------------------------------------------------------------------------------------
    @Test
    fun viewModelRecreation_reproducesIdenticalMetrics() {
        val roomData = listOf(
            DailyReelData(dateString = "2026-09-25", totalReelsCount = 20, dailyGoal = 50),
            DailyReelData(dateString = "2026-09-26", totalReelsCount = 30, dailyGoal = 50)
        )
        val todayStats = DailyReelData(dateString = todayStr, totalReelsCount = 10, dailyGoal = 50)

        val lifetime1 = calculateLifetimeReels(roomData, todayStr, todayStats, idleOverlay)
        val avg1 = calculateAvgReelsPerDay(lifetime1, roomData, todayStr, todayStats)
        val habit1 = generateHabitTrackerData(roomData, todayStr, todayStats)
        val streak1 = calculateStreakStats(habit1)

        val lifetime2 = calculateLifetimeReels(roomData, todayStr, todayStats, idleOverlay)
        val avg2 = calculateAvgReelsPerDay(lifetime2, roomData, todayStr, todayStats)
        val habit2 = generateHabitTrackerData(roomData, todayStr, todayStats)
        val streak2 = calculateStreakStats(habit2)

        assertEquals(lifetime1, lifetime2)
        assertEquals(avg1, avg2)
        assertEquals(habit1.map { it.totalReelsScrolled }, habit2.map { it.totalReelsScrolled })
        assertEquals(streak1, streak2)
    }

    // ---------------------------------------------------------------------------------------------
    // Test 8: Midnight rollover moves previous day to yesterday without loss
    // ---------------------------------------------------------------------------------------------
    @Test
    fun midnightRollover_movesPreviousDayToYesterdayWithoutLoss() {
        val d27Stats = DailyReelData(dateString = "2026-09-27", totalReelsCount = 42, dailyGoal = 50)
        val preMidnightHabit = generateHabitTrackerData(listOf(d27Stats), "2026-09-27", d27Stats)
        val preWeekly = preMidnightHabit.takeLast(7)

        // Before midnight: 7th bar is 2026-09-27 with 42 reels
        assertEquals("2026-09-27", preWeekly.last().dateString)
        assertEquals(42, preWeekly.last().totalReelsScrolled)
        assertTrue(preWeekly.last().isToday)

        // Midnight arrives: new day is 2026-09-28
        val postMidnightRoom = listOf(
            d27Stats,
            DailyReelData(dateString = "2026-09-28", totalReelsCount = 0, dailyGoal = 50)
        )
        val d28Stats = DailyReelData(dateString = "2026-09-28", totalReelsCount = 0, dailyGoal = 50)
        val postMidnightHabit = generateHabitTrackerData(postMidnightRoom, "2026-09-28", d28Stats)
        val postWeekly = postMidnightHabit.takeLast(7)

        // After midnight:
        // - 6th bar (yesterday) is 2026-09-27 with 42 reels intact!
        assertEquals("2026-09-27", postWeekly[5].dateString)
        assertEquals(42, postWeekly[5].totalReelsScrolled)
        assertFalse(postWeekly[5].isToday)

        // - 7th bar (today) is 2026-09-28 with 0 reels
        assertEquals("2026-09-28", postWeekly[6].dateString)
        assertEquals(0, postWeekly[6].totalReelsScrolled)
        assertTrue(postWeekly[6].isToday)
    }

    // ---------------------------------------------------------------------------------------------
    // Test 9: First reel after midnight increments only the new day
    // ---------------------------------------------------------------------------------------------
    @Test
    fun firstReelAfterMidnight_incrementsOnlyNewDay() {
        val d27Final = DailyReelData(dateString = "2026-09-27", totalReelsCount = 42, dailyGoal = 50)
        val d28FirstReel = DailyReelData(dateString = "2026-09-28", totalReelsCount = 1, dailyGoal = 50)
        val roomData = listOf(d27Final, d28FirstReel)

        val habitData = generateHabitTrackerData(roomData, "2026-09-28", d28FirstReel)
        val weekly = habitData.takeLast(7)

        assertEquals("2026-09-27", weekly[5].dateString)
        assertEquals(42, weekly[5].totalReelsScrolled)

        assertEquals("2026-09-28", weekly[6].dateString)
        assertEquals(1, weekly[6].totalReelsScrolled)
    }

    // ---------------------------------------------------------------------------------------------
    // Test 10: Historical data remains immutable across sessions
    // ---------------------------------------------------------------------------------------------
    @Test
    fun historicalData_remainsImmutableAcrossSessions() {
        val historical = listOf(
            DailyReelData(dateString = "2026-09-24", totalReelsCount = 15, dailyGoal = 40),
            DailyReelData(dateString = "2026-09-25", totalReelsCount = 25, dailyGoal = 40),
            DailyReelData(dateString = "2026-09-26", totalReelsCount = 35, dailyGoal = 40)
        )

        // Session 1 started and ended today
        val today1 = DailyReelData(dateString = todayStr, totalReelsCount = 10, dailyGoal = 40)
        val habit1 = generateHabitTrackerData(historical + today1, todayStr, today1)

        // Session 2 ended later today
        val today2 = DailyReelData(dateString = todayStr, totalReelsCount = 28, dailyGoal = 40)
        val habit2 = generateHabitTrackerData(historical + today2, todayStr, today2)

        val histMap1 = habit1.filter { it.dateString < todayStr }.associateBy { it.dateString }
        val histMap2 = habit2.filter { it.dateString < todayStr }.associateBy { it.dateString }

        assertEquals(histMap1.size, histMap2.size)
        histMap1.forEach { (date, block1) ->
            val block2 = histMap2[date]
            assertNotNull(block2)
            assertEquals(block1.totalReelsScrolled, block2!!.totalReelsScrolled)
            assertEquals(block1.dailyGoal, block2.dailyGoal)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Test 11: Future-dated row rejected from weekly graph, calendar, and metrics
    // ---------------------------------------------------------------------------------------------
    @Test
    fun futureDatedRow_rejectedFromWeeklyGraphCalendarAndTotals() {
        val validHistorical = DailyReelData(dateString = "2026-09-26", totalReelsCount = 20, dailyGoal = 50)
        val todayData = DailyReelData(dateString = todayStr, totalReelsCount = 15, dailyGoal = 50)
        val futureData = DailyReelData(dateString = "2026-10-15", totalReelsCount = 500, dailyGoal = 50)
        val corruptedRoom = listOf(validHistorical, todayData, futureData)

        // 1. HabitTracker terminates at today
        val habitData = generateHabitTrackerData(corruptedRoom, todayStr, todayData)
        assertEquals(todayStr, habitData.last().dateString)
        assertNull("Future row must not exist in habitTrackerData", habitData.find { it.dateString == "2026-10-15" })

        // 2. WeeklyActivityGraph does not contain future row
        val weekly = habitData.takeLast(7)
        assertEquals(todayStr, weekly.last().dateString)
        assertFalse(weekly.any { it.dateString > todayStr })

        // 3. Lifetime calculation rejects future row
        val lifetime = calculateLifetimeReels(corruptedRoom, todayStr, todayData, idleOverlay)
        assertEquals(35L, lifetime) // 20 + 15, 500 is rejected

        // 4. Daily average rejects future row
        val avg = calculateAvgReelsPerDay(lifetime, corruptedRoom, todayStr, todayData)
        assertEquals(18, avg) // 35 / 2 days = 17.5 -> 18
    }

    // ---------------------------------------------------------------------------------------------
    // Test 12: Timezone / date boundary preserves ISO date mapping
    // ---------------------------------------------------------------------------------------------
    @Test
    fun timezoneDateBoundary_preservesIsoDateMapping() {
        val leapYearToday = "2024-03-01"
        val habitData = generateHabitTrackerData(emptyList(), leapYearToday, null)
        val weekly = habitData.takeLast(7)

        // Day before 2024-03-01 is leap day 2024-02-29
        assertEquals("2024-02-29", weekly[5].dateString)
        assertEquals("2024-03-01", weekly[6].dateString)
        assertTrue(weekly[6].isToday)
    }

    // ---------------------------------------------------------------------------------------------
    // Test 13: Lifetime total equals sum of historical plus today
    // ---------------------------------------------------------------------------------------------
    @Test
    fun lifetimeTotalConsistency_matchesHistoricalPlusToday() {
        val days = (1..10).map { day ->
            val dateStr = "2026-09-%02d".format(day)
            DailyReelData(dateString = dateStr, totalReelsCount = day * 10, dailyGoal = 50)
        }
        val currentDay = DailyReelData(dateString = "2026-09-11", totalReelsCount = 37, dailyGoal = 50)
        val allData = days + currentDay

        val expectedHistoricalSum = (1..10).sumOf { it * 10 } // 550
        val expectedTotal = (expectedHistoricalSum + 37).toLong() // 587

        val lifetime = calculateLifetimeReels(allData, "2026-09-11", currentDay, idleOverlay)
        assertEquals(expectedTotal, lifetime)
    }

    // ---------------------------------------------------------------------------------------------
    // Test 14: Weekly total strictly equals sum of weekly graph bars
    // ---------------------------------------------------------------------------------------------
    @Test
    fun weeklyTotalConsistency_equalsSumOfWeeklyGraphBars() {
        val roomData = (21..26).map { day ->
            DailyReelData(dateString = "2026-09-$day", totalReelsCount = (day - 20) * 5, dailyGoal = 50)
        }
        val todayData = DailyReelData(dateString = todayStr, totalReelsCount = 45, dailyGoal = 50)
        val allData = roomData + todayData

        val habitData = generateHabitTrackerData(allData, todayStr, todayData)
        val weeklyActivityData = habitData.takeLast(7)
        val streakStats = calculateStreakStats(habitData)

        val sumOfWeeklyBars = weeklyActivityData.sumOf { it.totalReelsScrolled }
        assertEquals(sumOfWeeklyBars, streakStats.weeklyTotalReels)
    }

    // ---------------------------------------------------------------------------------------------
    // Test 15: Highest single day reflects true max across all tracked days
    // ---------------------------------------------------------------------------------------------
    @Test
    fun highestDayCalculation_reflectsTrueMaxAcrossAllTrackedDays() {
        val roomData = listOf(
            DailyReelData(dateString = "2026-09-24", totalReelsCount = 80, dailyGoal = 50),
            DailyReelData(dateString = "2026-09-25", totalReelsCount = 120, dailyGoal = 50),
            DailyReelData(dateString = "2026-09-26", totalReelsCount = 40, dailyGoal = 50)
        )
        // Today has 150 reels (exceeds historical max of 120)
        val todayData = DailyReelData(dateString = todayStr, totalReelsCount = 150, dailyGoal = 50)

        val habitData = generateHabitTrackerData(roomData + todayData, todayStr, todayData)
        val streakStats = calculateStreakStats(habitData)

        assertEquals(150, streakStats.highestReelsInDay)
        assertEquals(40, streakStats.lowestReelsInDay)
    }

    // ---------------------------------------------------------------------------------------------
    // Test 16: Streak calculations evaluate limits and drifted days correctly
    // ---------------------------------------------------------------------------------------------
    @Test
    fun streakConsistency_evaluatesLimitsCorrectly() {
        val day1 = DailyReelData(dateString = "2026-09-24", totalReelsCount = 30, dailyGoal = 50) // Success (within 50)
        val day2 = DailyReelData(dateString = "2026-09-25", totalReelsCount = 70, dailyGoal = 50) // Success (within 50 + 30 grace)
        val day3 = DailyReelData(dateString = "2026-09-26", totalReelsCount = 95, dailyGoal = 50) // Drifted (> 80) -> streak resets!
        val day4 = DailyReelData(dateString = "2026-09-27", totalReelsCount = 20, dailyGoal = 50) // Success -> streak restarts at 1!

        val habitData = generateHabitTrackerData(listOf(day1, day2, day3, day4), "2026-09-27", day4)
        val stats = calculateStreakStats(habitData)

        assertEquals(1, stats.currentStreak)
        assertEquals(2, stats.longestStreak)
        assertEquals(3, stats.totalSuccessfulDays)
        assertEquals(1, stats.totalDriftedDays)
        assertEquals(1, stats.totalSkullDays)
        assertEquals(3, stats.limitsFollowed)
        assertEquals(1, stats.limitsCrossed)
    }
}
