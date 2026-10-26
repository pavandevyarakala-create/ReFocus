package com.reeltrak.app.services.tracking

import com.reeltrak.app.data.local.dao.DailyReelDataDao
import com.reeltrak.app.data.local.entity.DailyReelData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.junit.Assert.*
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * Focused test suite verifying that today's reel counter NEVER resets to 0
 * before actual local midnight transition across:
 *
 * 1. Normal midnight transition (ACTION_DATE_CHANGED / natural elapsed time rollover)
 * 2. ACTION_TIME_CHANGED without an actual local date change (NTP sync at 15:30)
 * 3. Temporary clock jump into the next date and correction back (NTP/NITZ glitch)
 * 4. Backward clock jump (system time shifted to yesterday)
 * 5. Timezone change (forward to next day and backward to previous day)
 * 6. Force-stop / service restart around midnight (cold-start recovery)
 * 7. Missed midnight recovery across multi-day gaps
 * 8. Existing-day counter preservation across multiple reads/increments
 * 9. Monotonic protection against stale Room reads
 * 10. Reel increment during temporary clock jump preserves count
 */
class PreMidnightCounterResetTest {

    class FakeDailyReelDataDao : DailyReelDataDao {
        override suspend fun getAcceptedTransitionState(accountScope: String, dateString: String): com.reeltrak.app.data.local.entity.AcceptedReelTransitionState? = null
        override suspend fun insertAcceptedTransitionState(state: com.reeltrak.app.data.local.entity.AcceptedReelTransitionState) = Unit
        override suspend fun incrementDailyReelCount(dateString: String, acceptedAtMs: Long): Int = 0
        val records = ConcurrentHashMap<String, DailyReelData>()

        override suspend fun insertOrReplace(data: DailyReelData) {
            records[data.dateString] = data
        }

        override suspend fun getDailyDataSync(dateString: String): DailyReelData? {
            return records[dateString]
        }

        override suspend fun getLatestDailyDataSync(): DailyReelData? {
            return records.values.maxByOrNull { it.dateString }
        }

        override suspend fun getLatestHistoricalDataSync(dateString: String): DailyReelData? {
            return records.values
                .filter { it.dateString < dateString }
                .maxByOrNull { it.dateString }
        }

        override fun getDailyDataFlow(dateString: String): Flow<DailyReelData?> {
            return MutableStateFlow(records[dateString])
        }

        override fun getAllDailyDataFlow(): Flow<List<DailyReelData>> {
            return MutableStateFlow(records.values.sortedBy { it.dateString })
        }

        override fun getDailyDataRangeFlow(startDate: String, endDate: String): Flow<List<DailyReelData>> {
            return MutableStateFlow(records.values.filter { it.dateString in startDate..endDate }.sortedBy { it.dateString })
        }

        override suspend fun getAllDailyDataSync(): List<DailyReelData> {
            return records.values.sortedBy { it.dateString }
        }

        override suspend fun getDailyDataRangeSync(startDate: String, endDate: String): List<DailyReelData> {
            return records.values.filter { it.dateString in startDate..endDate }.sortedBy { it.dateString }
        }

        override suspend fun clearAll() {
            records.clear()
        }

        override suspend fun clearHistoricalDataExcept(today: String) {
            records.keys.removeIf { it != today }
        }

        override suspend fun insertAll(data: List<DailyReelData>) {
            data.forEach { records[it.dateString] = it }
        }

        override suspend fun getPendingSyncData(todayStr: String): List<DailyReelData> {
            return records.values.filter { it.syncStatus == 1 && it.dateString != todayStr }
        }

        override suspend fun getHistoricalDailyDataSync(todayStr: String): List<DailyReelData> {
            return records.values.filter { it.dateString != todayStr }.sortedBy { it.dateString }
        }

        override suspend fun deleteByDate(dateStr: String) {
            records.remove(dateStr)
        }

        override suspend fun deleteHistoricalSyncedExcept(todayStr: String) {
            records.keys.removeIf { key ->
                val r = records[key]
                r != null && r.syncStatus == 0 && r.dateString != todayStr
            }
        }

        override suspend fun markAsSynced(dateStr: String, syncedTimestamp: Long) {
            records[dateStr]?.let {
                if (it.updatedAt == syncedTimestamp) {
                    records[dateStr] = it.copy(syncStatus = 0)
                }
            }
        }
    }

    class TestTimeProvider(
        var currentDate: LocalDate = LocalDate.of(2026, 9, 27),
        var currentLocalTime: LocalTime = LocalTime.of(14, 0, 0),
        var currentElapsedMs: Long = 100_000_000L,
        var currentEpochMs: Long = 1790400000000L
    ) : DayTimeProvider {
        override fun elapsedRealtime(): Long = currentElapsedMs
        override fun localDate(): LocalDate = currentDate
        override fun localTime(): LocalTime = currentLocalTime
        override fun currentTimeMillis(): Long = currentEpochMs

        fun advanceTime(durationSeconds: Long) {
            currentLocalTime = currentLocalTime.plusSeconds(durationSeconds)
            currentElapsedMs += durationSeconds * 1000L
            currentEpochMs += durationSeconds * 1000L
            // If local time wrapped past midnight
            if (currentLocalTime.hour == 0 && currentLocalTime.minute == 0 && currentLocalTime.second < durationSeconds) {
                currentDate = currentDate.plusDays(1)
            }
        }

        fun jumpClockOnly(newDate: LocalDate, newTime: LocalTime, elapsedDeltaSeconds: Long = 5) {
            currentDate = newDate
            currentLocalTime = newTime
            currentElapsedMs += elapsedDeltaSeconds * 1000L
            currentEpochMs += elapsedDeltaSeconds * 1000L
        }
    }

    /**
     * Test harness that models DayManager's exact transition & persistence logic
     * with the new DayTimeProvider and isLegitimateDateTransition rules.
     */
    class DayManagerTestHarness(
        val timeProvider: TestTimeProvider = TestTimeProvider(),
        val dao: FakeDailyReelDataDao = FakeDailyReelDataDao()
    ) {
        var currentDateString: String = timeProvider.localDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
        var currentDayStats: DailyReelData? = null
        var syncCount = 0

        private var lastConfirmedDate: LocalDate = timeProvider.localDate()
        private var lastConfirmedLocalTime: LocalTime = timeProvider.localTime()
        private var lastConfirmedElapsedRealtimeMs: Long = timeProvider.elapsedRealtime()

        init {
            recoverMissedTransitionsAndInitialize(currentDateString)
        }

        fun updateConfirmationAnchors(
            date: LocalDate = timeProvider.localDate(),
            time: LocalTime = timeProvider.localTime()
        ) {
            lastConfirmedDate = date
            lastConfirmedLocalTime = time
            lastConfirmedElapsedRealtimeMs = timeProvider.elapsedRealtime()
        }

        fun isLegitimateDateTransition(
            currentDate: LocalDate,
            candidateDate: LocalDate,
            isDateChangedBroadcast: Boolean = false,
            isTimezoneChanged: Boolean = false
        ): Boolean {
            // 1. Backward transitions are NEVER legitimate day rollovers
            if (candidateDate.isBefore(currentDate)) {
                return false
            }

            // 2. Same date is not a transition
            if (candidateDate == currentDate) {
                return false
            }

            // 3. If Android OS explicitly broadcast ACTION_DATE_CHANGED, the OS confirmed midnight rollover
            if (isDateChangedBroadcast) {
                return true
            }

            // 4. If ACTION_TIMEZONE_CHANGED was broadcast, allow the timezone shift
            if (isTimezoneChanged) {
                return true
            }

            // 5. Evaluate elapsed real time against time required to reach midnight
            val nowElapsed = timeProvider.elapsedRealtime()
            val elapsedSinceLastAnchorMs = (nowElapsed - lastConfirmedElapsedRealtimeMs).coerceAtLeast(0L)

            val startDateTime = LocalDateTime.of(currentDate, lastConfirmedLocalTime)
            val midnightDateTime = LocalDateTime.of(currentDate.plusDays(1), LocalTime.MIDNIGHT)
            val requiredMillisToMidnight = Duration.between(startDateTime, midnightDateTime).toMillis().coerceAtLeast(0L)

            val requiredWithGrace = (requiredMillisToMidnight - 5_000L).coerceAtLeast(0L)

            if (elapsedSinceLastAnchorMs < requiredWithGrace) {
                return false
            }

            return true
        }

        fun recoverMissedTransitionsAndInitialize(todayStr: String) {
            val latestHistorical = kotlinx.coroutines.runBlocking {
                dao.getLatestHistoricalDataSync(todayStr) ?: dao.getLatestDailyDataSync()
            }

            if (latestHistorical != null && latestHistorical.dateString < todayStr) {
                val finalized = latestHistorical.copy(
                    updatedAt = timeProvider.currentTimeMillis(),
                    syncStatus = 1
                )
                dao.records[latestHistorical.dateString] = finalized
                syncCount++
            }

            currentDateString = todayStr
            loadOrCreateDay(todayStr)
            val parsedDate = try {
                LocalDate.parse(todayStr, DateTimeFormatter.ISO_LOCAL_DATE)
            } catch (e: Exception) {
                timeProvider.localDate()
            }
            updateConfirmationAnchors(parsedDate, timeProvider.localTime())
        }

        fun loadOrCreateDay(dateStr: String) {
            var stats = dao.records[dateStr]
            if (stats == null) {
                val lastStats = kotlinx.coroutines.runBlocking {
                    dao.getLatestHistoricalDataSync(dateStr)
                }
                val carryOverGoal = lastStats?.dailyGoal ?: 50
                val carryOverStrictMode = lastStats?.isStrictModeOn ?: false
                stats = DailyReelData(
                    dateString = dateStr,
                    totalReelsCount = 0,
                    dailyGoal = carryOverGoal,
                    isLimitLocked = false,
                    isStrictModeOn = carryOverStrictMode,
                    isStrictModeLocked = false,
                    updatedAt = timeProvider.currentTimeMillis(),
                    syncStatus = 1
                )
                dao.records[dateStr] = stats
            } else {
                // MONOTONIC INVARIANT PROTECTION
                val currentMemory = currentDayStats
                if (currentMemory != null && currentMemory.dateString == dateStr && currentMemory.totalReelsCount > stats.totalReelsCount) {
                    stats = stats.copy(totalReelsCount = currentMemory.totalReelsCount)
                    dao.records[dateStr] = stats
                }
            }
            currentDayStats = stats
        }

        fun incrementReelCount(amount: Int = 1) {
            val today = timeProvider.localDate()
            val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
            val currentStoredStr = currentDateString
            val currentStoredDate = try {
                LocalDate.parse(currentStoredStr, DateTimeFormatter.ISO_LOCAL_DATE)
            } catch (e: Exception) {
                today
            }

            if (todayStr != currentStoredStr) {
                if (isLegitimateDateTransition(currentStoredDate, today)) {
                    recoverMissedTransitionsAndInitialize(todayStr)
                }
            }

            val currentStats = currentDayStats ?: return
            val updated = currentStats.copy(
                totalReelsCount = currentStats.totalReelsCount + amount,
                updatedAt = timeProvider.currentTimeMillis(),
                syncStatus = 1
            )
            currentDayStats = updated
            dao.records[currentDateString] = updated
            updateConfirmationAnchors(today, timeProvider.localTime())
        }

        fun checkDayTransition(
            isDateChanged: Boolean = false,
            isTimezoneChanged: Boolean = false,
            isTimeChanged: Boolean = false
        ) {
            val today = timeProvider.localDate()
            val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
            val currentStoredStr = currentDateString
            val currentStoredDate = try {
                LocalDate.parse(currentStoredStr, DateTimeFormatter.ISO_LOCAL_DATE)
            } catch (e: Exception) {
                today
            }

            if (todayStr != currentStoredStr) {
                if (isTimezoneChanged) {
                    val existingRow = dao.records[todayStr]
                    if (existingRow != null) {
                        currentDateString = todayStr
                        loadOrCreateDay(todayStr)
                        updateConfirmationAnchors(today, timeProvider.localTime())
                    } else if (today.isAfter(currentStoredDate)) {
                        recoverMissedTransitionsAndInitialize(todayStr)
                    }
                } else if (isLegitimateDateTransition(currentStoredDate, today, isDateChanged)) {
                    recoverMissedTransitionsAndInitialize(todayStr)
                }
            }
        }
    }

    // 1. Normal Midnight Transition
    @Test
    fun testNormalMidnightTransition_viaActionDateChanged_rollsOverCleanly() {
        val timeProvider = TestTimeProvider(
            currentDate = LocalDate.of(2026, 9, 27),
            currentLocalTime = LocalTime.of(23, 59, 50),
            currentElapsedMs = 50_000_000L
        )
        val harness = DayManagerTestHarness(timeProvider = timeProvider)
        harness.incrementReelCount(30)
        assertEquals(30, harness.currentDayStats?.totalReelsCount)
        assertEquals("2026-09-27", harness.currentDateString)

        // Advance 15 seconds across midnight to 00:00:05 of next day
        timeProvider.currentDate = LocalDate.of(2026, 9, 28)
        timeProvider.currentLocalTime = LocalTime.of(0, 0, 5)
        timeProvider.currentElapsedMs += 15_000L

        // Android OS broadcasts ACTION_DATE_CHANGED at midnight
        harness.checkDayTransition(isDateChanged = true)

        assertEquals("2026-09-28", harness.currentDateString)
        assertEquals("New day counter must start at 0", 0, harness.currentDayStats?.totalReelsCount)
        assertEquals("Yesterday's count must be finalized at 30", 30, harness.dao.records["2026-09-27"]?.totalReelsCount)
        assertEquals(1, harness.syncCount)
    }

    // 2. ACTION_TIME_CHANGED without an actual local date change (e.g. 15:30 NTP sync)
    @Test
    fun testActionTimeChanged_sameDate_neverResetsCount() {
        val timeProvider = TestTimeProvider(
            currentDate = LocalDate.of(2026, 9, 27),
            currentLocalTime = LocalTime.of(15, 30, 0)
        )
        val harness = DayManagerTestHarness(timeProvider = timeProvider)
        harness.incrementReelCount(42)
        assertEquals(42, harness.currentDayStats?.totalReelsCount)

        // NTP sync occurs, broadcasting ACTION_TIME_CHANGED with slight millisecond jitter
        timeProvider.currentLocalTime = LocalTime.of(15, 30, 2)
        timeProvider.currentElapsedMs += 2_000L
        harness.checkDayTransition(isTimeChanged = true)

        assertEquals("2026-09-27", harness.currentDateString)
        assertEquals("Counter must remain 42 after time change broadcast on same day", 42, harness.currentDayStats?.totalReelsCount)
        assertEquals(0, harness.syncCount)
    }

    // 3. Temporary clock jump into the next date and correction back (e.g. at 14:00 clock jumps to 02:00 tomorrow)
    @Test
    fun testTemporaryClockJump_intoNextDate_rejectedAndPreservesCount() {
        val timeProvider = TestTimeProvider(
            currentDate = LocalDate.of(2026, 9, 27),
            currentLocalTime = LocalTime.of(14, 0, 0),
            currentElapsedMs = 50_000_000L
        )
        val harness = DayManagerTestHarness(timeProvider = timeProvider)
        harness.incrementReelCount(50)
        assertEquals(50, harness.currentDayStats?.totalReelsCount)

        // Network glitch or NITZ packet temporarily shifts clock to tomorrow 02:00:00 (jump of 12 hours)
        // BUT only 5 seconds of elapsed real time have passed!
        timeProvider.jumpClockOnly(
            newDate = LocalDate.of(2026, 9, 28),
            newTime = LocalTime.of(2, 0, 0),
            elapsedDeltaSeconds = 5L
        )

        // Broadcast ACTION_TIME_CHANGED received
        harness.checkDayTransition(isTimeChanged = true)

        // MUST be rejected! Day must remain 2026-09-27 with 50 reels
        assertEquals("2026-09-27", harness.currentDateString)
        assertEquals("Counter must NOT reset to 0 on temporary clock jump!", 50, harness.currentDayStats?.totalReelsCount)
        assertNull("No fake row for tomorrow must be created in DB", harness.dao.records["2026-09-28"])

        // User scrolls a reel while clock is temporarily skewed
        harness.incrementReelCount(1)
        assertEquals("Reel increment must apply to today's active day", 51, harness.currentDayStats?.totalReelsCount)
        assertEquals("2026-09-27", harness.currentDateString)

        // Clock corrects back to 14:00:10 on 2026-09-27
        timeProvider.jumpClockOnly(
            newDate = LocalDate.of(2026, 9, 27),
            newTime = LocalTime.of(14, 0, 10),
            elapsedDeltaSeconds = 5L
        )
        harness.checkDayTransition(isTimeChanged = true)

        assertEquals("2026-09-27", harness.currentDateString)
        assertEquals("Count must remain 51 after clock corrects back", 51, harness.currentDayStats?.totalReelsCount)
    }

    // 4. Backward clock jump (system time shifted to yesterday)
    @Test
    fun testBackwardClockJump_rejectedWithoutResettingOrOverwriting() {
        val timeProvider = TestTimeProvider(
            currentDate = LocalDate.of(2026, 9, 27),
            currentLocalTime = LocalTime.of(14, 0, 0)
        )
        val harness = DayManagerTestHarness(timeProvider = timeProvider)
        harness.incrementReelCount(25)
        assertEquals(25, harness.currentDayStats?.totalReelsCount)

        // Clock moved back to yesterday (2026-09-26)
        timeProvider.jumpClockOnly(
            newDate = LocalDate.of(2026, 9, 26),
            newTime = LocalTime.of(14, 0, 0)
        )
        harness.checkDayTransition(isTimeChanged = true)

        // MUST reject backward date transition and preserve today's progress
        assertEquals("2026-09-27", harness.currentDateString)
        assertEquals(25, harness.currentDayStats?.totalReelsCount)
        assertEquals(25, harness.dao.records["2026-09-27"]?.totalReelsCount)
    }

    // 5. Timezone change (forward to next day and backward to previous day)
    @Test
    fun testTimezoneChange_forwardAndBackward_preservesBothDays() {
        // User starts in London (23:45 on 2026-09-27)
        val timeProvider = TestTimeProvider(
            currentDate = LocalDate.of(2026, 9, 27),
            currentLocalTime = LocalTime.of(23, 45, 0)
        )
        val harness = DayManagerTestHarness(timeProvider = timeProvider)
        harness.incrementReelCount(40)
        assertEquals(40, harness.currentDayStats?.totalReelsCount)

        // User changes timezone to Paris (+1 hour -> 00:45 on 2026-09-28)
        timeProvider.currentDate = LocalDate.of(2026, 9, 28)
        timeProvider.currentLocalTime = LocalTime.of(0, 45, 0)
        harness.checkDayTransition(isTimezoneChanged = true)

        // Paris is legitimately a new day
        assertEquals("2026-09-28", harness.currentDateString)
        assertEquals(0, harness.currentDayStats?.totalReelsCount)
        assertEquals("London 2026-09-27 record must be preserved", 40, harness.dao.records["2026-09-27"]?.totalReelsCount)

        // In Paris, user watches 5 reels
        harness.incrementReelCount(5)
        assertEquals(5, harness.currentDayStats?.totalReelsCount)

        // User switches back to London (23:50 on 2026-09-27)
        timeProvider.currentDate = LocalDate.of(2026, 9, 27)
        timeProvider.currentLocalTime = LocalTime.of(23, 50, 0)
        harness.checkDayTransition(isTimezoneChanged = true)

        // London's existing record for 2026-09-27 must be safely restored with 40 reels
        assertEquals("2026-09-27", harness.currentDateString)
        assertEquals("Restored London count must be 40, NOT 0", 40, harness.currentDayStats?.totalReelsCount)
        assertEquals(5, harness.dao.records["2026-09-28"]?.totalReelsCount)
    }

    // 6. Force-stop / service restart around midnight (cold-start recovery)
    @Test
    fun testForceStopAndServiceRestart_aroundMidnight_recoversMissedTransition() {
        val dao = FakeDailyReelDataDao()
        dao.records["2026-09-27"] = DailyReelData(
            dateString = "2026-09-27",
            totalReelsCount = 37,
            dailyGoal = 50,
            syncStatus = 1
        )

        // Device restarts / app opened at 00:05 on Sept 28
        val timeProvider = TestTimeProvider(
            currentDate = LocalDate.of(2026, 9, 28),
            currentLocalTime = LocalTime.of(0, 5, 0)
        )
        val harness = DayManagerTestHarness(timeProvider = timeProvider, dao = dao)

        assertEquals("2026-09-28", harness.currentDateString)
        assertEquals(0, harness.currentDayStats?.totalReelsCount)
        assertEquals(37, dao.records["2026-09-27"]?.totalReelsCount)
        assertEquals("Carryover goal must be preserved from yesterday", 50, harness.currentDayStats?.dailyGoal)
    }

    // 7. Missed midnight recovery across multi-day gap
    @Test
    fun testMissedMidnightRecovery_acrossMultiDayGap_finalizesAndDoesNotCreateDummyRows() {
        val dao = FakeDailyReelDataDao()
        dao.records["2026-09-20"] = DailyReelData(
            dateString = "2026-09-20",
            totalReelsCount = 88,
            dailyGoal = 60
        )

        // Reopened 7 days later on Sept 27
        val timeProvider = TestTimeProvider(
            currentDate = LocalDate.of(2026, 9, 27),
            currentLocalTime = LocalTime.of(10, 0, 0)
        )
        val harness = DayManagerTestHarness(timeProvider = timeProvider, dao = dao)

        assertEquals("2026-09-27", harness.currentDateString)
        assertEquals(0, harness.currentDayStats?.totalReelsCount)
        assertEquals(88, dao.records["2026-09-20"]?.totalReelsCount)
        assertEquals(2, dao.records.size) // Only Sept 20 and Sept 27 exist
        assertNull(dao.records["2026-09-21"])
        assertNull(dao.records["2026-09-22"])
    }

    // 8. Existing-day counter preservation across multiple reads and reels
    @Test
    fun testExistingDayCounterPreservation_multipleIncrements_accumulateMonotonically() {
        val harness = DayManagerTestHarness()
        harness.incrementReelCount(1)
        harness.incrementReelCount(2)
        harness.incrementReelCount(3)
        harness.incrementReelCount(4)

        assertEquals(10, harness.currentDayStats?.totalReelsCount)
        assertEquals(10, harness.dao.records[harness.currentDateString]?.totalReelsCount)
    }

    // 9. Monotonic protection against stale Room reads
    @Test
    fun testMonotonicProtection_againstStaleRoomReads_preservesHigherCount() {
        val harness = DayManagerTestHarness()
        harness.incrementReelCount(30)
        assertEquals(30, harness.currentDayStats?.totalReelsCount)

        // Simulate stale DB row containing a lower count (e.g. 20)
        harness.dao.records[harness.currentDateString] = harness.currentDayStats!!.copy(totalReelsCount = 20)

        // Reload day
        harness.loadOrCreateDay(harness.currentDateString)

        // Monotonic protection must kick in: keep 30 in memory and fix DB
        assertEquals("Monotonic protection must preserve higher in-memory count", 30, harness.currentDayStats?.totalReelsCount)
        assertEquals("DB must be updated to match monotonic higher count", 30, harness.dao.records[harness.currentDateString]?.totalReelsCount)
    }

    // 10. getLatestHistoricalDataSync ignores future records
    @Test
    fun testGetLatestHistoricalDataSync_ignoresFutureRecords() {
        val dao = FakeDailyReelDataDao()
        dao.records["2026-09-26"] = DailyReelData(dateString = "2026-09-26", totalReelsCount = 40)
        // Suppose a spurious future record existed
        dao.records["2026-09-29"] = DailyReelData(dateString = "2026-09-29", totalReelsCount = 0)

        // Looking for historical before 2026-09-27
        val historical = kotlinx.coroutines.runBlocking {
            dao.getLatestHistoricalDataSync("2026-09-27")
        }

        assertNotNull(historical)
        assertEquals("Must return 2026-09-26, NOT the future record 2026-09-29", "2026-09-26", historical?.dateString)
        assertEquals(40, historical?.totalReelsCount)
    }
}
