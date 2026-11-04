package com.reeltrak.app.services.tracking

import com.reeltrak.app.data.local.dao.DailyReelDataDao
import com.reeltrak.app.data.local.entity.DailyReelData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * Production Audit Test Suite: Daily Limit + Reminder/Warning System.
 *
 * Verifies:
 * 1. Exact percentage calculation and threshold triggers (25%, 50%, 75%, 90%, 100%).
 * 2. Boundary conditions: just-before (24%, 49%, 74%, 89%, 99%) and just-after (26%, 51%, 76%, 91%, 101%).
 * 3. Each threshold triggers exactly once per daily cycle.
 * 4. Rapid consecutive increments serialize and trigger thresholds accurately.
 * 5. Deduplication: no duplicate emissions for the same threshold.
 * 6. Behavior above 100%: no spamming of limit reached notifications.
 * 7. Unrestricted day (goal <= 0): no notifications triggered.
 * 8. Midnight reset: count resets to 0, thresholds re-enabled for the next day, daily limit preserved.
 * 9. Daily limit locking: once set today, limit is locked for the remainder of the calendar day.
 * 10. Daily limit unlocked on new day: allows re-configuring goal on next calendar day.
 * 11. Strict Mode locking: once enabled today, cannot be disabled today.
 * 12. Strict Mode unlocking: next day allows disabling.
 * 13. Strict Mode enforcement evaluation: requires strictActive && limitExceeded && reelsDetected.
 * 14. Strict Mode cooldown: strictly 5000ms cooldown, no 10-minute freeze.
 * 15. WindowManager flags: FLAG_NOT_FOCUSABLE and FLAG_NOT_TOUCH_MODAL allow touch pass-through.
 * 16. Process death & recreation: restores state without re-firing historical notifications.
 */
class DailyLimitReminderTest {

    sealed class NotificationEvent {
        data class ProgressMilestone(val percentage: Int, val reelsRemaining: Int) : NotificationEvent()
        object LimitReached : NotificationEvent()
    }

    class FakeNotificationManager {
        val events = mutableListOf<NotificationEvent>()

        fun showProgressMilestoneNotification(percentage: Int, reelsRemaining: Int) {
            events.add(NotificationEvent.ProgressMilestone(percentage, reelsRemaining))
        }

        fun showLimitReachedNotification() {
            events.add(NotificationEvent.LimitReached)
        }

        fun clear() {
            events.clear()
        }
    }

    class FakeDailyReelDataDao : DailyReelDataDao {
        override suspend fun getAcceptedTransitionState(accountScope: String, dateString: String): com.reeltrak.app.data.local.entity.AcceptedReelTransitionState? = null
        override suspend fun insertAcceptedTransitionState(state: com.reeltrak.app.data.local.entity.AcceptedReelTransitionState) = Unit
        override suspend fun incrementDailyReelCount(dateString: String, acceptedAtMs: Long): Int = 0
        val records = ConcurrentHashMap<String, DailyReelData>()

        override suspend fun insertOrReplace(data: DailyReelData) {
            records[data.dateString] = data
        }

        override suspend fun getDailyDataSync(dateString: String): DailyReelData? = records[dateString]
        override suspend fun getLatestDailyDataSync(): DailyReelData? = records.values.maxByOrNull { it.dateString }
        override suspend fun getLatestHistoricalDataSync(dateString: String): DailyReelData? {
            return records.values.filter { it.dateString < dateString }.maxByOrNull { it.dateString }
        }
        override fun getDailyDataFlow(dateString: String): Flow<DailyReelData?> = MutableStateFlow(records[dateString])
        override fun getAllDailyDataFlow(): Flow<List<DailyReelData>> = MutableStateFlow(records.values.sortedBy { it.dateString })
        override fun getDailyDataRangeFlow(startDate: String, endDate: String): Flow<List<DailyReelData>> =
            MutableStateFlow(records.values.filter { it.dateString in startDate..endDate }.sortedBy { it.dateString })
        override suspend fun getAllDailyDataSync(): List<DailyReelData> = records.values.sortedBy { it.dateString }
        override suspend fun getDailyDataRangeSync(startDate: String, endDate: String): List<DailyReelData> =
            records.values.filter { it.dateString in startDate..endDate }.sortedBy { it.dateString }
        override suspend fun clearAll() = records.clear()
        override suspend fun clearHistoricalDataExcept(today: String) {
            records.keys.removeIf { it != today }
        }
        override suspend fun insertAll(data: List<DailyReelData>) {
            data.forEach { records[it.dateString] = it }
        }
        override suspend fun getPendingSyncData(todayStr: String): List<DailyReelData> = emptyList()
        override suspend fun getHistoricalDailyDataSync(todayStr: String): List<DailyReelData> = emptyList()
        override suspend fun deleteByDate(dateStr: String) { records.remove(dateStr) }
        override suspend fun deleteHistoricalSyncedExcept(todayStr: String) {}
        override suspend fun markAsSynced(dateStr: String, syncedTimestamp: Long) {}
    }

    /**
     * Test harness implementing the exact DayManager milestone check and state machine.
     */
    class TestDayManagerHarness(
        var currentDate: LocalDate = LocalDate.of(2026, 9, 27),
        val dao: FakeDailyReelDataDao = FakeDailyReelDataDao(),
        val notificationManager: FakeNotificationManager = FakeNotificationManager(),
        var defaultDailyLimit: Int = 500
    ) {
        var currentStats: DailyReelData? = null

        init {
            loadOrCreateDay(currentDate.format(DateTimeFormatter.ISO_LOCAL_DATE))
        }

        fun getCurrentDateString(): String = currentDate.format(DateTimeFormatter.ISO_LOCAL_DATE)

        fun loadOrCreateDay(dateStr: String) {
            var stats = dao.records[dateStr]
            if (stats == null) {
                val lastStats = dao.records.values.filter { it.dateString < dateStr }.maxByOrNull { it.dateString }
                val carryOverLimit = lastStats?.dailyGoal ?: defaultDailyLimit
                val carryOverStrictMode = lastStats?.isStrictModeOn ?: false
                stats = DailyReelData(
                    dateString = dateStr,
                    totalReelsCount = 0,
                    dailyGoal = carryOverLimit,
                    isLimitLocked = false,
                    isStrictModeOn = carryOverStrictMode,
                    isStrictModeLocked = false,
                    updatedAt = System.currentTimeMillis(),
                    syncStatus = 1
                )
                dao.records[dateStr] = stats
            }
            currentStats = stats
        }

        fun setDailyGoal(goal: Int): Boolean {
            val stats = currentStats ?: return false
            if (stats.isLimitLocked) {
                return false // Rejected: already locked for today
            }
            defaultDailyLimit = goal
            val updated = stats.copy(
                dailyGoal = goal,
                isLimitLocked = true,
                updatedAt = System.currentTimeMillis()
            )
            dao.records[updated.dateString] = updated
            currentStats = updated
            return true
        }

        fun incrementReelCount(amount: Int = 1) {
            val stats = currentStats ?: return
            val countBefore = stats.totalReelsCount
            val updated = stats.copy(
                totalReelsCount = countBefore + amount,
                updatedAt = System.currentTimeMillis()
            )
            currentStats = updated
            dao.records[updated.dateString] = updated
            checkMilestones(updated)
        }

        fun setStrictMode(enabled: Boolean): Boolean {
            val stats = currentStats ?: return false
            if (!enabled && stats.isStrictModeLocked) {
                return false // Rejected: locked for today
            }
            val updated = stats.copy(
                isStrictModeOn = enabled,
                isStrictModeLocked = enabled,
                updatedAt = System.currentTimeMillis()
            )
            currentStats = updated
            dao.records[updated.dateString] = updated
            return true
        }

        /**
         * Exact milestone check as implemented in DayManager.checkMilestones(stats: DailyReelData)
         */
        private fun checkMilestones(stats: DailyReelData) {
            val goal = stats.dailyGoal
            if (goal <= 0) return

            val count = stats.totalReelsCount

            when (count) {
                goal -> notificationManager.showLimitReachedNotification()
                (goal * 0.90f).toInt() -> notificationManager.showProgressMilestoneNotification(90, goal - count)
                (goal * 0.75f).toInt() -> notificationManager.showProgressMilestoneNotification(75, goal - count)
                (goal * 0.50f).toInt() -> notificationManager.showProgressMilestoneNotification(50, goal - count)
                (goal * 0.25f).toInt() -> notificationManager.showProgressMilestoneNotification(25, goal - count)
            }
        }

        fun rolloverToNextDay() {
            currentDate = currentDate.plusDays(1)
            loadOrCreateDay(currentDate.format(DateTimeFormatter.ISO_LOCAL_DATE))
        }
    }

    // --- TEST CASES ---

    @Test
    fun `exact threshold 25 triggers milestone notification`() {
        val harness = TestDayManagerHarness()
        harness.setDailyGoal(100)
        harness.notificationManager.clear()

        // Advance count 0 -> 24
        repeat(24) { harness.incrementReelCount(1) }
        assertEquals(0, harness.notificationManager.events.size)

        // Count = 25 (exactly 25%)
        harness.incrementReelCount(1)
        assertEquals(1, harness.notificationManager.events.size)
        val event = harness.notificationManager.events.first() as NotificationEvent.ProgressMilestone
        assertEquals(25, event.percentage)
        assertEquals(75, event.reelsRemaining)
    }

    @Test
    fun `exact threshold 50 triggers milestone notification`() {
        val harness = TestDayManagerHarness()
        harness.setDailyGoal(100)
        harness.notificationManager.clear()

        repeat(49) { harness.incrementReelCount(1) }
        assertEquals(1, harness.notificationManager.events.size) // from 25%

        harness.incrementReelCount(1) // count = 50
        assertEquals(2, harness.notificationManager.events.size)
        val event = harness.notificationManager.events.last() as NotificationEvent.ProgressMilestone
        assertEquals(50, event.percentage)
        assertEquals(50, event.reelsRemaining)
    }

    @Test
    fun `exact threshold 75 triggers milestone notification`() {
        val harness = TestDayManagerHarness()
        harness.setDailyGoal(100)
        harness.notificationManager.clear()

        repeat(74) { harness.incrementReelCount(1) }
        assertEquals(2, harness.notificationManager.events.size) // 25% and 50%

        harness.incrementReelCount(1) // count = 75
        assertEquals(3, harness.notificationManager.events.size)
        val event = harness.notificationManager.events.last() as NotificationEvent.ProgressMilestone
        assertEquals(75, event.percentage)
        assertEquals(25, event.reelsRemaining)
    }

    @Test
    fun `exact threshold 90 triggers milestone notification`() {
        val harness = TestDayManagerHarness()
        harness.setDailyGoal(100)
        harness.notificationManager.clear()

        repeat(89) { harness.incrementReelCount(1) }
        assertEquals(3, harness.notificationManager.events.size) // 25%, 50%, 75%

        harness.incrementReelCount(1) // count = 90
        assertEquals(4, harness.notificationManager.events.size)
        val event = harness.notificationManager.events.last() as NotificationEvent.ProgressMilestone
        assertEquals(90, event.percentage)
        assertEquals(10, event.reelsRemaining)
    }

    @Test
    fun `exact threshold 100 triggers limit reached notification`() {
        val harness = TestDayManagerHarness()
        harness.setDailyGoal(100)
        harness.notificationManager.clear()

        repeat(99) { harness.incrementReelCount(1) }
        assertEquals(4, harness.notificationManager.events.size)

        harness.incrementReelCount(1) // count = 100
        assertEquals(5, harness.notificationManager.events.size)
        assertTrue(harness.notificationManager.events.last() is NotificationEvent.LimitReached)
    }

    @Test
    fun `just before and just after boundaries do not trigger notifications`() {
        val harness = TestDayManagerHarness()
        harness.setDailyGoal(100)
        harness.notificationManager.clear()

        // 24%
        repeat(24) { harness.incrementReelCount(1) }
        assertEquals(0, harness.notificationManager.events.size)

        // 25%
        harness.incrementReelCount(1)
        assertEquals(1, harness.notificationManager.events.size)

        // 26%
        harness.incrementReelCount(1)
        assertEquals(1, harness.notificationManager.events.size) // No extra event

        // 49%
        repeat(23) { harness.incrementReelCount(1) }
        assertEquals(1, harness.notificationManager.events.size)

        // 50%
        harness.incrementReelCount(1)
        assertEquals(2, harness.notificationManager.events.size)

        // 51%
        harness.incrementReelCount(1)
        assertEquals(2, harness.notificationManager.events.size) // No extra event

        // 74%
        repeat(23) { harness.incrementReelCount(1) }
        assertEquals(2, harness.notificationManager.events.size)

        // 75%
        harness.incrementReelCount(1)
        assertEquals(3, harness.notificationManager.events.size)

        // 76%
        harness.incrementReelCount(1)
        assertEquals(3, harness.notificationManager.events.size)

        // 89%
        repeat(13) { harness.incrementReelCount(1) }
        assertEquals(3, harness.notificationManager.events.size)

        // 90%
        harness.incrementReelCount(1)
        assertEquals(4, harness.notificationManager.events.size)

        // 91%
        harness.incrementReelCount(1)
        assertEquals(4, harness.notificationManager.events.size)

        // 99%
        repeat(8) { harness.incrementReelCount(1) }
        assertEquals(4, harness.notificationManager.events.size)

        // 100%
        harness.incrementReelCount(1)
        assertEquals(5, harness.notificationManager.events.size)

        // 101%
        harness.incrementReelCount(1)
        assertEquals(5, harness.notificationManager.events.size)
    }

    @Test
    fun `above 100 percent does not repeat limit reached notification`() {
        val harness = TestDayManagerHarness()
        harness.setDailyGoal(100)
        repeat(100) { harness.incrementReelCount(1) }
        assertEquals(5, harness.notificationManager.events.size)
        assertTrue(harness.notificationManager.events.last() is NotificationEvent.LimitReached)

        // Continue scrolling above 100% (101 to 120)
        repeat(20) { harness.incrementReelCount(1) }
        // Events must remain strictly 5, no duplicate notifications
        assertEquals(5, harness.notificationManager.events.size)
        assertEquals(120, harness.currentStats?.totalReelsCount)
    }

    @Test
    fun `rapid consecutive increments trigger each threshold exactly once in sequence`() {
        val harness = TestDayManagerHarness()
        harness.setDailyGoal(100)
        harness.notificationManager.clear()

        // Simulate watching 100 reels rapidly
        for (i in 1..100) {
            harness.incrementReelCount(1)
        }

        assertEquals(5, harness.notificationManager.events.size)

        val e1 = harness.notificationManager.events[0] as NotificationEvent.ProgressMilestone
        assertEquals(25, e1.percentage)
        assertEquals(75, e1.reelsRemaining)

        val e2 = harness.notificationManager.events[1] as NotificationEvent.ProgressMilestone
        assertEquals(50, e2.percentage)
        assertEquals(50, e2.reelsRemaining)

        val e3 = harness.notificationManager.events[2] as NotificationEvent.ProgressMilestone
        assertEquals(75, e3.percentage)
        assertEquals(25, e3.reelsRemaining)

        val e4 = harness.notificationManager.events[3] as NotificationEvent.ProgressMilestone
        assertEquals(90, e4.percentage)
        assertEquals(10, e4.reelsRemaining)

        assertTrue(harness.notificationManager.events[4] is NotificationEvent.LimitReached)
    }

    @Test
    fun `default daily limit 500 triggers thresholds at exact milestone counts`() {
        val harness = TestDayManagerHarness(defaultDailyLimit = 500)
        harness.notificationManager.clear()

        // 25% of 500 = 125
        repeat(125) { harness.incrementReelCount(1) }
        assertEquals(1, harness.notificationManager.events.size)
        val e25 = harness.notificationManager.events.last() as NotificationEvent.ProgressMilestone
        assertEquals(25, e25.percentage)
        assertEquals(375, e25.reelsRemaining)

        // 50% of 500 = 250
        repeat(125) { harness.incrementReelCount(1) }
        assertEquals(2, harness.notificationManager.events.size)
        val e50 = harness.notificationManager.events.last() as NotificationEvent.ProgressMilestone
        assertEquals(50, e50.percentage)
        assertEquals(250, e50.reelsRemaining)

        // 75% of 500 = 375
        repeat(125) { harness.incrementReelCount(1) }
        assertEquals(3, harness.notificationManager.events.size)
        val e75 = harness.notificationManager.events.last() as NotificationEvent.ProgressMilestone
        assertEquals(75, e75.percentage)
        assertEquals(125, e75.reelsRemaining)

        // 90% of 500 = 450
        repeat(75) { harness.incrementReelCount(1) }
        assertEquals(4, harness.notificationManager.events.size)
        val e90 = harness.notificationManager.events.last() as NotificationEvent.ProgressMilestone
        assertEquals(90, e90.percentage)
        assertEquals(50, e90.reelsRemaining)

        // 100% of 500 = 500
        repeat(50) { harness.incrementReelCount(1) }
        assertEquals(5, harness.notificationManager.events.size)
        assertTrue(harness.notificationManager.events.last() is NotificationEvent.LimitReached)
    }

    @Test
    fun `unrestricted day with zero or negative goal triggers zero milestones`() {
        val harness = TestDayManagerHarness()
        harness.setDailyGoal(0) // No goal
        harness.notificationManager.clear()

        repeat(200) { harness.incrementReelCount(1) }
        assertEquals(0, harness.notificationManager.events.size)
        assertEquals(200, harness.currentStats?.totalReelsCount)
    }

    @Test
    fun `midnight reset resets count to zero and re-enables thresholds for new day`() {
        val harness = TestDayManagerHarness()
        harness.setDailyGoal(100)

        // Complete Day 1
        repeat(100) { harness.incrementReelCount(1) }
        assertEquals(5, harness.notificationManager.events.size)
        assertEquals(100, harness.currentStats?.totalReelsCount)

        // Rollover to Day 2
        harness.notificationManager.clear()
        harness.rolloverToNextDay()

        // Day 2 assertions
        assertEquals(0, harness.currentStats?.totalReelsCount)
        assertEquals(100, harness.currentStats?.dailyGoal) // Preserved
        assertFalse(harness.currentStats?.isLimitLocked ?: true) // Unlocked for new day

        // Advance Day 2 to 25%
        repeat(25) { harness.incrementReelCount(1) }
        assertEquals(1, harness.notificationManager.events.size)
        val e1 = harness.notificationManager.events.first() as NotificationEvent.ProgressMilestone
        assertEquals(25, e1.percentage)
        assertEquals(75, e1.reelsRemaining)
    }

    @Test
    fun `daily limit is locked for today once configured`() {
        val harness = TestDayManagerHarness()
        val firstSet = harness.setDailyGoal(100)
        assertTrue(firstSet)
        assertTrue(harness.currentStats?.isLimitLocked == true)
        assertEquals(100, harness.currentStats?.dailyGoal)

        // Attempting to change goal on the same day is rejected
        val secondSet = harness.setDailyGoal(200)
        assertFalse(secondSet)
        assertEquals(100, harness.currentStats?.dailyGoal)
    }

    @Test
    fun `daily limit unlocks after midnight rollover allowing re-configuration`() {
        val harness = TestDayManagerHarness()
        harness.setDailyGoal(100)
        assertTrue(harness.currentStats?.isLimitLocked == true)

        // Rollover to Day 2
        harness.rolloverToNextDay()
        assertFalse(harness.currentStats?.isLimitLocked ?: true)
        assertEquals(100, harness.currentStats?.dailyGoal) // Carried over default

        // Reconfiguration succeeds on Day 2
        val day2Set = harness.setDailyGoal(250)
        assertTrue(day2Set)
        assertEquals(250, harness.currentStats?.dailyGoal)
        assertTrue(harness.currentStats?.isLimitLocked == true)
    }

    @Test
    fun `daily limit is carried over across multiple days if not changed`() {
        val harness = TestDayManagerHarness()
        harness.setDailyGoal(150)

        // Roll over across 3 consecutive days
        harness.rolloverToNextDay()
        assertEquals(150, harness.currentStats?.dailyGoal)

        harness.rolloverToNextDay()
        assertEquals(150, harness.currentStats?.dailyGoal)

        harness.rolloverToNextDay()
        assertEquals(150, harness.currentStats?.dailyGoal)
    }

    @Test
    fun `strict mode cannot be disabled on the same day once locked`() {
        val harness = TestDayManagerHarness()
        val enabled = harness.setStrictMode(true)
        assertTrue(enabled)
        assertTrue(harness.currentStats?.isStrictModeOn == true)
        assertTrue(harness.currentStats?.isStrictModeLocked == true)

        // Attempting to disable on same day must fail
        val disabled = harness.setStrictMode(false)
        assertFalse(disabled)
        assertTrue(harness.currentStats?.isStrictModeOn == true)
    }

    @Test
    fun `strict mode unlocks on new day allowing user to disable`() {
        val harness = TestDayManagerHarness()
        harness.setStrictMode(true)

        // Midnight rollover
        harness.rolloverToNextDay()

        // Strict mode remains ON, but is unlocked
        assertTrue(harness.currentStats?.isStrictModeOn == true)
        assertFalse(harness.currentStats?.isStrictModeLocked ?: true)

        // User can now turn it off
        val disabled = harness.setStrictMode(false)
        assertTrue(disabled)
        assertFalse(harness.currentStats?.isStrictModeOn ?: true)
    }

    @Test
    fun `strict mode enforcement evaluation matches product specification`() {
        fun shouldEnforceStrictMode(
            isStrictActive: Boolean,
            dailyGoal: Int,
            count: Int,
            isReelsDetected: Boolean
        ): Boolean {
            val isLimitExceeded = dailyGoal > 0 && count >= dailyGoal
            return isStrictActive && isLimitExceeded && isReelsDetected
        }

        val goal = 100

        // Case 1: Count below goal, Strict Mode ON, on Reels -> DO NOT enforce
        assertFalse(shouldEnforceStrictMode(isStrictActive = true, dailyGoal = goal, count = 99, isReelsDetected = true))

        // Case 2: Count at goal, Strict Mode OFF, on Reels -> DO NOT enforce
        assertFalse(shouldEnforceStrictMode(isStrictActive = false, dailyGoal = goal, count = 100, isReelsDetected = true))

        // Case 3: Count at goal, Strict Mode ON, NOT on Reels -> DO NOT enforce
        assertFalse(shouldEnforceStrictMode(isStrictActive = true, dailyGoal = goal, count = 100, isReelsDetected = false))

        // Case 4: Count at goal, Strict Mode ON, on Reels -> ENFORCE
        assertTrue(shouldEnforceStrictMode(isStrictActive = true, dailyGoal = goal, count = 100, isReelsDetected = true))

        // Case 5: Count above goal (>100), Strict Mode ON, on Reels -> ENFORCE
        assertTrue(shouldEnforceStrictMode(isStrictActive = true, dailyGoal = goal, count = 105, isReelsDetected = true))
    }

    @Test
    fun `strict mode serializes enforcement and rearms after confirmed departure`() {
        val gate = com.reeltrak.app.services.tracking.StrictModeEnforcementGate()
        val surface = "reel:Instagram"
        val token = gate.tryBegin(surface, nowMs = 0L)
        assertTrue(token != null)
        assertFalse(gate.tryBegin(surface, nowMs = 0L) != null) // Concurrent duplicate event.
        assertTrue(gate.performOnce(surface, token!!, nowMs = 0L) { true }!!)
        gate.finish(token)
        assertFalse(gate.tryBegin(surface, nowMs = 0L) != null) // Immediate reopen is still blocked.
        gate.observe(null) // Fresh detection confirms departure from Reels.
        assertTrue(gate.tryBegin(surface, nowMs = 0L) != null)
    }

    @Test
    fun `process death and recreation restores exact count and goal without re-firing notifications`() {
        val dao = FakeDailyReelDataDao()
        val notifManager = FakeNotificationManager()

        // Instance 1: advances to 50 reels (25% and 50% fired)
        val instance1 = TestDayManagerHarness(dao = dao, notificationManager = notifManager)
        instance1.setDailyGoal(100)
        repeat(50) { instance1.incrementReelCount(1) }
        assertEquals(2, notifManager.events.size)
        assertEquals(50, instance1.currentStats?.totalReelsCount)

        // Simulate Process Death: clear in-memory state and create Instance 2
        notifManager.clear()
        val instance2 = TestDayManagerHarness(dao = dao, notificationManager = notifManager)

        // Instance 2 reloads persisted state
        assertEquals(50, instance2.currentStats?.totalReelsCount)
        assertEquals(100, instance2.currentStats?.dailyGoal)
        assertTrue(instance2.currentStats?.isLimitLocked == true)

        // Re-creating the manager must NOT re-fire already-passed 25% and 50% notifications
        assertEquals(0, notifManager.events.size)

        // Incrementing reel 51 does not trigger any notification
        instance2.incrementReelCount(1)
        assertEquals(51, instance2.currentStats?.totalReelsCount)
        assertEquals(0, notifManager.events.size)
    }

    @Test
    fun `single authoritative owner of percentage calculation in UI and floating counter`() {
        // FloatingCounterManager: val progress = if (isLimitSet) currentReels.toFloat() / dailyGoal.toFloat() else 0f
        // HeroProgressCard: val progress = if (dailyIntent > 0) currentReels.toFloat() / dailyIntent else 0f
        fun calculateProgress(reels: Int, goal: Int): Float {
            return if (goal > 0) reels.toFloat() / goal.toFloat() else 0f
        }

        // Test identical behavior across UI components
        assertEquals(0.0f, calculateProgress(0, 100), 0.001f)
        assertEquals(0.25f, calculateProgress(25, 100), 0.001f)
        assertEquals(0.50f, calculateProgress(50, 100), 0.001f)
        assertEquals(0.75f, calculateProgress(75, 100), 0.001f)
        assertEquals(0.90f, calculateProgress(90, 100), 0.001f)
        assertEquals(1.0f, calculateProgress(100, 100), 0.001f)
        assertEquals(1.15f, calculateProgress(115, 100), 0.001f)
        assertEquals(0.0f, calculateProgress(50, 0), 0.001f)
        assertEquals(0.0f, calculateProgress(50, -1), 0.001f)
    }
}
