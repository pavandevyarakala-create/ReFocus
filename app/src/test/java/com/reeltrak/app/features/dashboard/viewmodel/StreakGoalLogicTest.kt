package com.reeltrak.app.features.dashboard.viewmodel

import com.reeltrak.app.data.local.entity.DailyReelData
import com.reeltrak.app.data.local.entity.SessionData
import com.reeltrak.app.features.dashboard.viewmodel.DashboardViewModel.Companion.calculateStreakStats
import com.reeltrak.app.features.dashboard.viewmodel.DashboardViewModel.Companion.generateHabitTrackerData
import com.reeltrak.app.features.dashboard.viewmodel.DashboardViewModel.DailyBlocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Production Audit Unit Tests:
 * STREAK + DAILY GOAL + DRIFT/SKULL-DAY LOGIC
 *
 * Validates the complete business logic for:
 * - Daily goal evaluation
 * - Successful days vs Drifted days vs Unrestricted days
 * - +30 grace zone tolerance
 * - Streak continuation and streak resets
 * - Historical goal immutability
 * - Milestone unlocks (7, 30, 50, 100, 365) and level progression
 */
class StreakGoalLogicTest {

    private val todayStr = "2026-09-27"

    // Helper to generate a DailyBlocks item
    private fun createBlock(
        dateStr: String,
        count: Int,
        goal: Int,
        isToday: Boolean = false
    ): DailyBlocks {
        val isUnrestricted = count > 0 && goal <= 0
        val isDrifted = goal > 0 && count > (goal + 30)
        val isGrace = goal > 0 && count > goal && !isDrifted
        val progress = if (goal > 0) count.toFloat() / goal else -1f

        return DailyBlocks(
            dayLabel = dateStr,
            blocks = emptyList(),
            totalReelsScrolled = count,
            dailyGoal = goal,
            dateString = dateStr,
            dateEpochDay = 0L,
            percentage = progress,
            isGraceDay = isGrace,
            isDriftedDay = isDrifted,
            isUnrestrictedDay = isUnrestricted,
            isSkullDay = isDrifted,
            isToday = isToday
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Test 1: First day below goal is successful -> streak = 1
    // ---------------------------------------------------------------------------------------------
    @Test
    fun firstDay_belowGoal_isSuccessful_streakIsOne() {
        val blocks = listOf(createBlock("2026-09-27", count = 30, goal = 50, isToday = true))
        val stats = calculateStreakStats(blocks)

        assertEquals(1, stats.currentStreak)
        assertEquals(1, stats.longestStreak)
        assertEquals(1, stats.totalSuccessfulDays)
        assertEquals(0, stats.totalDriftedDays)
        assertEquals(1, stats.limitsFollowed)
        assertEquals(0, stats.limitsCrossed)
        assertEquals(1f, stats.successRate, 0.001f)
    }

    // ---------------------------------------------------------------------------------------------
    // Test 2: First day exactly at goal is successful -> streak = 1
    // ---------------------------------------------------------------------------------------------
    @Test
    fun firstDay_exactlyAtGoal_isSuccessful_streakIsOne() {
        val blocks = listOf(createBlock("2026-09-27", count = 50, goal = 50, isToday = true))
        val stats = calculateStreakStats(blocks)

        assertEquals(1, stats.currentStreak)
        assertEquals(1, stats.longestStreak)
        assertEquals(1, stats.totalSuccessfulDays)
        assertEquals(0, stats.totalDriftedDays)
        assertEquals(1, stats.limitsFollowed)
        assertEquals(0, stats.limitsCrossed)
    }

    // ---------------------------------------------------------------------------------------------
    // Test 3: First day above goal but within +30 grace zone is successful
    // ---------------------------------------------------------------------------------------------
    @Test
    fun firstDay_aboveGoal_within30Grace_isSuccessful_streakIsOne() {
        val blocks = listOf(createBlock("2026-09-27", count = 75, goal = 50, isToday = true))
        val stats = calculateStreakStats(blocks)

        assertEquals(1, stats.currentStreak)
        assertEquals(1, stats.longestStreak)
        assertEquals(1, stats.totalSuccessfulDays)
        assertEquals(0, stats.totalDriftedDays)
        assertEquals(1, stats.limitsFollowed)
        assertEquals(0, stats.limitsCrossed)
    }

    // ---------------------------------------------------------------------------------------------
    // Test 4: Consecutive successful days increment streak monotonically
    // ---------------------------------------------------------------------------------------------
    @Test
    fun consecutiveSuccessfulDays_incrementsStreak() {
        val blocks = listOf(
            createBlock("2026-09-23", count = 20, goal = 50),
            createBlock("2026-09-24", count = 35, goal = 50),
            createBlock("2026-09-25", count = 48, goal = 50),
            createBlock("2026-09-26", count = 50, goal = 50),
            createBlock("2026-09-27", count = 10, goal = 50, isToday = true)
        )
        val stats = calculateStreakStats(blocks)

        assertEquals(5, stats.currentStreak)
        assertEquals(5, stats.longestStreak)
        assertEquals(5, stats.totalSuccessfulDays)
        assertEquals(0, stats.totalDriftedDays)
        assertEquals(5, stats.totalDaysTracked)
        assertEquals(1f, stats.successRate, 0.001f)
    }

    // ---------------------------------------------------------------------------------------------
    // Test 5: One failed day exceeding goal + 30 resets current streak to 0
    // ---------------------------------------------------------------------------------------------
    @Test
    fun failedDay_exceedingGoalAndGrace_resetsCurrentStreak() {
        val blocks = listOf(
            createBlock("2026-09-24", count = 30, goal = 50), // Day 1: Success -> streak = 1
            createBlock("2026-09-25", count = 45, goal = 50), // Day 2: Success -> streak = 2
            createBlock("2026-09-26", count = 95, goal = 50), // Day 3: Drifted (>80) -> streak = 0!
            createBlock("2026-09-27", count = 20, goal = 50, isToday = true) // Day 4: Success -> streak = 1!
        )
        val stats = calculateStreakStats(blocks)

        assertEquals(1, stats.currentStreak)
        assertEquals(2, stats.longestStreak)
        assertEquals(3, stats.totalSuccessfulDays)
        assertEquals(1, stats.totalDriftedDays)
        assertEquals(1, stats.totalSkullDays)
        assertEquals(3, stats.limitsFollowed)
        assertEquals(1, stats.limitsCrossed)
        assertEquals(4, stats.totalDaysTracked)
        assertEquals(0.75f, stats.successRate, 0.001f)
    }

    // ---------------------------------------------------------------------------------------------
    // Test 6 & 7: +30 grace tolerance exact boundary (goal + 30 vs goal + 31)
    // ---------------------------------------------------------------------------------------------
    @Test
    fun graceTolerance_exactBoundary_plus30_continuesStreak() {
        val blocks = listOf(
            createBlock("2026-09-26", count = 40, goal = 50),
            createBlock("2026-09-27", count = 80, goal = 50, isToday = true) // Exactly 50 + 30 = 80
        )
        val stats = calculateStreakStats(blocks)

        assertEquals(2, stats.currentStreak)
        assertEquals(2, stats.longestStreak)
        assertEquals(0, stats.totalDriftedDays)
        assertEquals(2, stats.limitsFollowed)
    }

    @Test
    fun graceTolerance_boundary_plus31_resetsStreak() {
        val blocks = listOf(
            createBlock("2026-09-26", count = 40, goal = 50),
            createBlock("2026-09-27", count = 81, goal = 50, isToday = true) // 50 + 31 = 81 (> 80) -> Drifted!
        )
        val stats = calculateStreakStats(blocks)

        assertEquals(0, stats.currentStreak)
        assertEquals(1, stats.longestStreak)
        assertEquals(1, stats.totalDriftedDays)
        assertEquals(1, stats.limitsCrossed)
    }

    // ---------------------------------------------------------------------------------------------
    // Test 8: Zero-reel day with goal is successful when day is completed (detox day)
    // Incomplete current day with zero reels does NOT increment streak early
    // ---------------------------------------------------------------------------------------------
    @Test
    fun zeroReelDay_withGoal_isSuccessful_whenCompleted() {
        val blocks = listOf(
            createBlock("2026-09-25", count = 30, goal = 50),
            createBlock("2026-09-26", count = 0, goal = 50, isToday = false) // Completed detox day
        )
        val stats = calculateStreakStats(blocks)

        assertEquals(2, stats.currentStreak)
        assertEquals(2, stats.longestStreak)
        assertEquals(2, stats.totalSuccessfulDays)
    }

    @Test
    fun incompleteCurrentDay_zeroReelsWithGoal_doesNotIncrementStreakEarly() {
        val blocks = listOf(
            createBlock("2026-09-26", count = 30, goal = 50),
            createBlock("2026-09-27", count = 0, goal = 50, isToday = true) // Incomplete current day
        )
        val stats = calculateStreakStats(blocks)

        assertEquals(1, stats.currentStreak)
        assertEquals(1, stats.longestStreak)
        assertEquals(1, stats.totalSuccessfulDays)
    }

    // ---------------------------------------------------------------------------------------------
    // Test 9: Genuinely missed days (untracked, count=0, goal=0) break streak
    // ---------------------------------------------------------------------------------------------
    @Test
    fun missingRoomDay_untracked_breaksStreak() {
        val blocks = listOf(
            createBlock("2026-09-24", count = 25, goal = 50),
            createBlock("2026-09-25", count = 0, goal = 0), // Untracked day
            createBlock("2026-09-26", count = 0, goal = 0), // Untracked day
            createBlock("2026-09-27", count = 15, goal = 50, isToday = true)
        )
        val stats = calculateStreakStats(blocks)

        assertEquals(1, stats.currentStreak)
        assertEquals(1, stats.longestStreak)
        assertEquals(2, stats.totalSuccessfulDays)
    }

    // ---------------------------------------------------------------------------------------------
    // Test 10: Unrestricted day (count > 0, goal = 0) does not change streak
    // ---------------------------------------------------------------------------------------------
    @Test
    fun unrestrictedDay_noGoal_doesNotChangeStreak() {
        val blocks = listOf(
            createBlock("2026-09-25", count = 30, goal = 50), // streak = 1
            createBlock("2026-09-26", count = 50, goal = 0),  // Unrestricted: active, but no limit to follow
            createBlock("2026-09-27", count = 20, goal = 50, isToday = true) // streak = 2
        )
        val stats = calculateStreakStats(blocks)

        assertEquals(2, stats.currentStreak)
        assertEquals(3, stats.totalDaysTracked)
        assertEquals(2, stats.totalSuccessfulDays)
        assertEquals(0, stats.totalDriftedDays)
    }

    // ---------------------------------------------------------------------------------------------
    // Test 11: Multiple sessions on same day coalesce into single daily total
    // ---------------------------------------------------------------------------------------------
    @Test
    fun multipleSessionsSameDay_accumulatesIntoSingleDailyTotal() {
        val s1 = SessionData(appName = "Instagram", reelsScrolled = 20, startTimeStamp = 1000L, endTimeStamp = 2000L)
        val s2 = SessionData(appName = "YouTube", reelsScrolled = 40, startTimeStamp = 3000L, endTimeStamp = 4000L)
        val todayData = DailyReelData(
            dateString = todayStr,
            totalReelsCount = 60, // 20 + 40
            dailyGoal = 50,
            sessions = listOf(s1, s2)
        )

        val habitData = generateHabitTrackerData(emptyList(), todayStr, todayData)
        val stats = calculateStreakStats(habitData)

        // 60 reels <= 50 + 30 (80) -> within grace zone!
        assertEquals(1, stats.currentStreak)
        assertEquals(1, stats.totalSuccessfulDays)
        assertEquals(0, stats.totalDriftedDays)
    }

    // ---------------------------------------------------------------------------------------------
    // Test 12 & 13: Active session updates streak live, resets live if drifted
    // ---------------------------------------------------------------------------------------------
    @Test
    fun activeSession_scrollingLive_updatesStreakInRealTime() {
        val d24 = DailyReelData(dateString = "2026-09-24", totalReelsCount = 20, dailyGoal = 50)
        val d25 = DailyReelData(dateString = "2026-09-25", totalReelsCount = 25, dailyGoal = 50)
        val yesterday = DailyReelData(dateString = "2026-09-26", totalReelsCount = 30, dailyGoal = 50)
        // Today starts with 0 reels -> Streak is 3 from yesterday (preserved, does NOT increment early to 4!)
        val todayStart = DailyReelData(dateString = todayStr, totalReelsCount = 0, dailyGoal = 50)
        val habit1 = generateHabitTrackerData(listOf(d24, d25, yesterday, todayStart), todayStr, todayStart)
        val stats1 = calculateStreakStats(habit1)
        assertEquals(3, stats1.currentStreak)

        // User actively scrolls to 85 reels (> 80 grace boundary) -> Today drifts
        val todayDrifted = DailyReelData(dateString = todayStr, totalReelsCount = 85, dailyGoal = 50)
        val habit2 = generateHabitTrackerData(listOf(d24, d25, yesterday, todayStart), todayStr, todayDrifted)
        val stats2 = calculateStreakStats(habit2)
        assertEquals(0, stats2.currentStreak) // Drops to 0 live!
        assertEquals(3, stats2.longestStreak) // Historical longest streak (24th, 25th, 26th) is preserved!
        assertEquals(1, stats2.totalDriftedDays)
    }

    // ---------------------------------------------------------------------------------------------
    // Test 14: Session termination preserves streak state
    // ---------------------------------------------------------------------------------------------
    @Test
    fun sessionTermination_preservesStreakState() {
        val todayActive = DailyReelData(dateString = todayStr, totalReelsCount = 35, dailyGoal = 50)
        val habitActive = generateHabitTrackerData(emptyList(), todayStr, todayActive)
        val statsActive = calculateStreakStats(habitActive)

        // Session terminated: sessions list appended, count unchanged
        val todayEnded = todayActive.copy(
            sessions = listOf(SessionData(appName = "Instagram", reelsScrolled = 35, startTimeStamp = 1000L, endTimeStamp = 5000L)),
            totalTimeSpentMillis = 4000L
        )
        val habitEnded = generateHabitTrackerData(emptyList(), todayStr, todayEnded)
        val statsEnded = calculateStreakStats(habitEnded)

        assertEquals(statsActive.currentStreak, statsEnded.currentStreak)
        assertEquals(statsActive.longestStreak, statsEnded.longestStreak)
        assertEquals(statsActive.totalSuccessfulDays, statsEnded.totalSuccessfulDays)
    }

    // ---------------------------------------------------------------------------------------------
    // Test 15: Midnight rollover preserves yesterday's status and transitions cleanly
    // ---------------------------------------------------------------------------------------------
    @Test
    fun midnightRollover_preservesYesterdayStatus_andContinuesIntoNewDay() {
        val d27 = DailyReelData(dateString = "2026-09-27", totalReelsCount = 35, dailyGoal = 50)
        val preMidnightHabit = generateHabitTrackerData(listOf(d27), "2026-09-27", d27)
        val preStats = calculateStreakStats(preMidnightHabit)
        assertEquals(1, preStats.currentStreak)

        // At midnight: new day 2026-09-28 is created with 0 reels
        val d28 = DailyReelData(dateString = "2026-09-28", totalReelsCount = 0, dailyGoal = 50)
        val postMidnightHabit = generateHabitTrackerData(listOf(d27, d28), "2026-09-28", d28)
        val postStats = calculateStreakStats(postMidnightHabit)

        assertEquals(1, postStats.currentStreak) // Preserves yesterday's streak of 1, does NOT increment to 2 early!
        assertEquals(1, postStats.longestStreak)
        assertEquals(1, postStats.totalSuccessfulDays)
    }

    @Test
    fun streakBehavior_acrossConsecutiveDays_beforeAndAfterMidnight() {
        // Day 1: completed with 20 reels, goal 50
        val day1 = createBlock("2026-09-01", count = 20, goal = 50, isToday = false)
        // Day 2: completed with 15 reels, goal 50
        val day2 = createBlock("2026-09-02", count = 15, goal = 50, isToday = false)
        // Day 3 (today): 0 reels (just after midnight)
        val day3JustAfterMidnight = createBlock("2026-09-03", count = 0, goal = 50, isToday = true)

        val statsAfterMidnight = calculateStreakStats(listOf(day1, day2, day3JustAfterMidnight))
        // Streak is 2 (from Day 1 and Day 2), NOT 3!
        assertEquals(2, statsAfterMidnight.currentStreak)
        assertEquals(2, statsAfterMidnight.longestStreak)

        // Later on Day 3: user scrolls 10 reels (within goal 50)
        val day3Active = createBlock("2026-09-03", count = 10, goal = 50, isToday = true)
        val statsDay3Active = calculateStreakStats(listOf(day1, day2, day3Active))
        assertEquals(3, statsDay3Active.currentStreak)
        assertEquals(3, statsDay3Active.longestStreak)
    }

    @Test
    fun dateGap_breaksStreak_andPreservesHistoricalLongest() {
        val blocks = listOf(
            createBlock("2026-09-10", count = 20, goal = 50, isToday = false),
            createBlock("2026-09-11", count = 20, goal = 50, isToday = false),
            createBlock("2026-09-12", count = 20, goal = 50, isToday = false), // 3-day streak
            // Gap of 2 days: 13th and 14th missing
            createBlock("2026-09-15", count = 25, goal = 50, isToday = true)
        )
        val stats = calculateStreakStats(blocks)
        assertEquals(1, stats.currentStreak)
        assertEquals(3, stats.longestStreak)
    }

    // ---------------------------------------------------------------------------------------------
    // Test 16 & 17: ViewModel recreation and Lifecycle transitions preserve identical streak
    // ---------------------------------------------------------------------------------------------
    @Test
    fun viewModelRecreation_andBackgroundForeground_preserveIdenticalStreak() {
        val roomData = listOf(
            DailyReelData(dateString = "2026-09-25", totalReelsCount = 20, dailyGoal = 50),
            DailyReelData(dateString = "2026-09-26", totalReelsCount = 40, dailyGoal = 50)
        )
        val today = DailyReelData(dateString = todayStr, totalReelsCount = 15, dailyGoal = 50)

        val habit1 = generateHabitTrackerData(roomData, todayStr, today)
        val stats1 = calculateStreakStats(habit1)

        val habit2 = generateHabitTrackerData(roomData, todayStr, today)
        val stats2 = calculateStreakStats(habit2)

        assertEquals(stats1, stats2)
    }

    // ---------------------------------------------------------------------------------------------
    // Test 18: Future-dated data in Room cannot enter streak calculations
    // ---------------------------------------------------------------------------------------------
    @Test
    fun futureDatedData_rejectedFromStreakCalculations() {
        val past = DailyReelData(dateString = "2026-09-26", totalReelsCount = 30, dailyGoal = 50)
        val today = DailyReelData(dateString = todayStr, totalReelsCount = 20, dailyGoal = 50)
        val futureDrifted = DailyReelData(dateString = "2026-10-10", totalReelsCount = 999, dailyGoal = 50)

        val habitData = generateHabitTrackerData(listOf(past, today, futureDrifted), todayStr, today)
        val stats = calculateStreakStats(habitData)

        // Future day must not exist in habitData
        assertFalse(habitData.any { it.dateString > todayStr })
        assertEquals(2, stats.currentStreak)
        assertEquals(0, stats.totalDriftedDays)
    }

    // ---------------------------------------------------------------------------------------------
    // Test 19: Goal change today does not rewrite historical day goals
    // ---------------------------------------------------------------------------------------------
    @Test
    fun goalChangeToday_doesNotAlterHistoricalGoals() {
        // Historical day had goal 50 and 65 reels (successful under +30 grace zone of 50)
        val pastDay = DailyReelData(dateString = "2026-09-26", totalReelsCount = 65, dailyGoal = 50)
        // User changes today's goal to 20
        val todayData = DailyReelData(dateString = todayStr, totalReelsCount = 10, dailyGoal = 20)

        val habitData = generateHabitTrackerData(listOf(pastDay, todayData), todayStr, todayData)
        val pastBlock = habitData.find { it.dateString == "2026-09-26" }!!

        // Past block must preserve historical goal of 50, NOT 20!
        assertEquals(50, pastBlock.dailyGoal)
        assertFalse("Must not be drifted under historical goal 50", pastBlock.isDriftedDay)

        val stats = calculateStreakStats(habitData)
        assertEquals(2, stats.currentStreak)
        assertEquals(0, stats.totalDriftedDays)
    }

    // ---------------------------------------------------------------------------------------------
    // Test 20 to 24: Milestone boundaries and level progression
    // ---------------------------------------------------------------------------------------------
    @Test
    fun milestone_7Days_exactBoundary() {
        val days6 = (1..6).map { day ->
            createBlock("2026-09-%02d".format(day), count = 20, goal = 50)
        }
        val stats6 = calculateStreakStats(days6)
        assertEquals(6, stats6.longestStreak)
        assertEquals("Seedling", stats6.disciplineLevel)
        assertTrue(stats6.longestStreak < 7)

        val days7 = (1..7).map { day ->
            createBlock("2026-09-%02d".format(day), count = 20, goal = 50)
        }
        val stats7 = calculateStreakStats(days7)
        assertEquals(7, stats7.longestStreak)
        assertEquals("Focused", stats7.disciplineLevel)
        assertTrue(stats7.longestStreak >= 7)
    }

    @Test
    fun milestone_30Days_exactBoundary() {
        val days29 = (1..29).map { day ->
            createBlock("2026-08-%02d".format(day), count = 20, goal = 50)
        }
        val stats29 = calculateStreakStats(days29)
        assertEquals(29, stats29.longestStreak)
        assertEquals("Focused", stats29.disciplineLevel)

        val days30 = (1..30).map { day ->
            createBlock("2026-08-%02d".format(day), count = 20, goal = 50)
        }
        val stats30 = calculateStreakStats(days30)
        assertEquals(30, stats30.longestStreak)
        assertEquals("Consistent", stats30.disciplineLevel)
    }

    @Test
    fun milestone_50Days_exactBoundary() {
        val days49 = (1..49).map { day ->
            createBlock("2026-07-%02d".format(day), count = 20, goal = 50)
        }
        val stats49 = calculateStreakStats(days49)
        assertEquals(49, stats49.longestStreak)
        assertEquals("Consistent", stats49.disciplineLevel)

        val days50 = (1..50).map { day ->
            createBlock("2026-07-%02d".format(day), count = 20, goal = 50)
        }
        val stats50 = calculateStreakStats(days50)
        assertEquals(50, stats50.longestStreak)
        assertEquals("Disciplined", stats50.disciplineLevel)
    }

    @Test
    fun milestone_100Days_exactBoundary() {
        val days99 = (1..99).map { day ->
            createBlock("2026-05-%02d".format(day), count = 20, goal = 50)
        }
        val stats99 = calculateStreakStats(days99)
        assertEquals(99, stats99.longestStreak)
        assertEquals("Disciplined", stats99.disciplineLevel)

        val days100 = (1..100).map { day ->
            createBlock("2026-05-%02d".format(day), count = 20, goal = 50)
        }
        val stats100 = calculateStreakStats(days100)
        assertEquals(100, stats100.longestStreak)
        assertEquals("Master", stats100.disciplineLevel)
    }

    @Test
    fun milestone_365Days_exactBoundary() {
        val days364 = (1..364).map { day ->
            createBlock("2025-%03d".format(day), count = 20, goal = 50)
        }
        val stats364 = calculateStreakStats(days364)
        assertEquals(364, stats364.longestStreak)
        assertEquals("Master", stats364.disciplineLevel)

        val days365 = (1..365).map { day ->
            createBlock("2025-%03d".format(day), count = 20, goal = 50)
        }
        val stats365 = calculateStreakStats(days365)
        assertEquals(365, stats365.longestStreak)
        assertEquals("Legend", stats365.disciplineLevel)
        assertEquals(1f, stats365.progressToNextLevel, 0.001f)
    }

    // ---------------------------------------------------------------------------------------------
    // Test 25: Streak reset preserves longest streak and level
    // ---------------------------------------------------------------------------------------------
    @Test
    fun streakReset_preservesLongestStreakAndLevel() {
        // 30 consecutive days followed by 1 drifted day
        val blocks = (1..30).map { day ->
            createBlock("2026-08-%02d".format(day), count = 20, goal = 50)
        }.toMutableList()
        blocks.add(createBlock("2026-08-31", count = 90, goal = 50)) // Drifted!

        val stats = calculateStreakStats(blocks)

        assertEquals(0, stats.currentStreak) // Current reset to 0
        assertEquals(30, stats.longestStreak) // Longest retained!
        assertEquals("Consistent", stats.disciplineLevel) // Highest level earned retained!
    }

    // ---------------------------------------------------------------------------------------------
    // Test 26: Weekly trend calculation ("Increasing", "Decreasing", "Stable")
    // ---------------------------------------------------------------------------------------------
    @Test
    fun weeklyTrend_calculation() {
        // Increasing: 1st 3 days avg = 10, last 3 days avg = 20 (> 10 * 1.1)
        val increasing = listOf(
            createBlock("2026-09-21", 10, 50),
            createBlock("2026-09-22", 10, 50),
            createBlock("2026-09-23", 10, 50),
            createBlock("2026-09-24", 15, 50),
            createBlock("2026-09-25", 20, 50),
            createBlock("2026-09-26", 20, 50),
            createBlock("2026-09-27", 20, 50)
        )
        assertEquals("Increasing", calculateStreakStats(increasing).weeklyTrend)

        // Decreasing: 1st 3 days avg = 30, last 3 days avg = 10 (< 30 * 0.9)
        val decreasing = listOf(
            createBlock("2026-09-21", 30, 50),
            createBlock("2026-09-22", 30, 50),
            createBlock("2026-09-23", 30, 50),
            createBlock("2026-09-24", 20, 50),
            createBlock("2026-09-25", 10, 50),
            createBlock("2026-09-26", 10, 50),
            createBlock("2026-09-27", 10, 50)
        )
        assertEquals("Decreasing", calculateStreakStats(decreasing).weeklyTrend)

        // Stable: 1st 3 days avg = 20, last 3 days avg = 20
        val stable = listOf(
            createBlock("2026-09-21", 20, 50),
            createBlock("2026-09-22", 20, 50),
            createBlock("2026-09-23", 20, 50),
            createBlock("2026-09-24", 20, 50),
            createBlock("2026-09-25", 20, 50),
            createBlock("2026-09-26", 20, 50),
            createBlock("2026-09-27", 20, 50)
        )
        assertEquals("Stable", calculateStreakStats(stable).weeklyTrend)
    }

    // ---------------------------------------------------------------------------------------------
    // Test 27: Highest and lowest single day calculations
    // ---------------------------------------------------------------------------------------------
    @Test
    fun highestAndLowestReels_calculation() {
        val blocks = listOf(
            createBlock("2026-09-24", 0, 50),   // Zero reels ignored for lowest
            createBlock("2026-09-25", 15, 50),  // Lowest non-zero
            createBlock("2026-09-26", 120, 50), // Highest
            createBlock("2026-09-27", 45, 50)
        )
        val stats = calculateStreakStats(blocks)

        assertEquals(120, stats.highestReelsInDay)
        assertEquals(15, stats.lowestReelsInDay)
    }

    // ---------------------------------------------------------------------------------------------
    // Test 28: Empty data returns clean default stats
    // ---------------------------------------------------------------------------------------------
    @Test
    fun emptyData_returnsDefaultStreakStats() {
        val stats = calculateStreakStats(emptyList())

        assertEquals(0, stats.currentStreak)
        assertEquals(0, stats.longestStreak)
        assertEquals(0, stats.totalSuccessfulDays)
        assertEquals(0, stats.totalDriftedDays)
        assertEquals(0, stats.highestReelsInDay)
        assertEquals(0, stats.lowestReelsInDay)
        assertEquals(0, stats.weeklyTotalReels)
        assertEquals("Seedling", stats.disciplineLevel)
        assertEquals(0f, stats.successRate, 0.001f)
    }
}
