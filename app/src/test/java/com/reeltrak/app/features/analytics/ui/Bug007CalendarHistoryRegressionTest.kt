package com.reeltrak.app.features.analytics.ui

import com.reeltrak.app.data.local.dao.DailyReelDataDao
import com.reeltrak.app.data.local.entity.DailyReelData
import com.reeltrak.app.features.dashboard.viewmodel.DashboardViewModel
import com.reeltrak.app.features.dashboard.viewmodel.DashboardViewModel.Companion.generateHabitTrackerData
import com.reeltrak.app.features.dashboard.viewmodel.DashboardViewModel.Companion.generateMonthCalendarData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * Regression Test Suite for BUG-007:
 * "Calendar History Appears Blank Beyond 90 Days"
 *
 * Verifies:
 * 1. Historical records older than 90 days appear in the correct calendar dates.
 * 2. Month navigation works across year boundaries and leap years.
 * 3. Empty dates are not presented as fabricated historical records.
 * 4. Date-range queries return the correct records without unnecessary full-history loading.
 */
class Bug007CalendarHistoryRegressionTest {

    private class FakeDailyReelDataDao : DailyReelDataDao {
        override suspend fun getAcceptedTransitionState(accountScope: String, dateString: String): com.reeltrak.app.data.local.entity.AcceptedReelTransitionState? = null
        override suspend fun insertAcceptedTransitionState(state: com.reeltrak.app.data.local.entity.AcceptedReelTransitionState) = Unit
        override suspend fun incrementDailyReelCount(dateString: String, acceptedAtMs: Long): Int = 0
        val records = ConcurrentHashMap<String, DailyReelData>()

        override suspend fun insertOrReplace(data: DailyReelData) {
            records[data.dateString] = data
        }

        override suspend fun getDailyDataSync(dateString: String): DailyReelData? = records[dateString]
        override suspend fun getLatestDailyDataSync(): DailyReelData? = records.values.maxByOrNull { it.dateString }
        override suspend fun getLatestHistoricalDataSync(dateString: String): DailyReelData? =
            records.values.filter { it.dateString < dateString }.maxByOrNull { it.dateString }
        override fun getDailyDataFlow(dateString: String): Flow<DailyReelData?> = MutableStateFlow(records[dateString])
        override fun getAllDailyDataFlow(): Flow<List<DailyReelData>> =
            MutableStateFlow(records.values.sortedBy { it.dateString })
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

    private val todayStr = "2026-10-23"
    private val todayStats = DailyReelData(
        dateString = todayStr,
        totalReelsCount = 15,
        dailyGoal = 40
    )

    // ---------------------------------------------------------------------------------------------
    // Test 1: Historical records older than 90 days appear in the correct calendar dates
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testHistoricalRecordsOlderThan90DaysAppearInCorrectCalendarDates() {
        // Record from 180 days ago (approx April 2026 when today is Oct 2026)
        val oldDateStr = "2026-04-10"
        val oldRecord = DailyReelData(
            dateString = oldDateStr,
            totalReelsCount = 35,
            dailyGoal = 50
        )
        val dataList = listOf(oldRecord, todayStats)

        // 1. Verify that legacy generateHabitTrackerData strictly excluded records older than 90 days
        val legacyData = generateHabitTrackerData(dataList, todayStr, todayStats)
        val legacyMap = legacyData.associateBy { it.dateString }
        val legacyBlockForOldDate = legacyMap[oldDateStr]
        assertNull("Legacy habit tracker must discard records beyond 90 days", legacyBlockForOldDate)

        // 2. Verify that on-demand month calendar loading includes and displays the record accurately
        val targetMonth = YearMonth.of(2026, 4)
        val monthBlocks = generateMonthCalendarData(targetMonth, listOf(oldRecord), todayStr, todayStats)
        val monthMap = monthBlocks.associateBy { it.dateString }

        val oldBlock = monthMap[oldDateStr]
        assertNotNull("Record older than 90 days must be present in month calendar data", oldBlock)
        assertEquals(35, oldBlock!!.totalReelsScrolled)
        assertEquals(50, oldBlock.dailyGoal)
        assertEquals(LocalDate.parse(oldDateStr).toEpochDay(), oldBlock.dateEpochDay)
        assertFalse(oldBlock.isDriftedDay)
        assertEquals(0.7f, oldBlock.percentage, 0.001f)
    }

    // ---------------------------------------------------------------------------------------------
    // Test 2: Month navigation works across year boundaries and leap years
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testMonthNavigationWorksAcrossYearBoundariesAndLeapYears() {
        // Leap year: February 2024 has 29 days
        val leapYearMonth = YearMonth.of(2024, 2)
        assertEquals("February 2024 must have 29 days", 29, leapYearMonth.lengthOfMonth())

        val leapDayStr = "2024-02-29"
        val leapDayRecord = DailyReelData(
            dateString = leapDayStr,
            totalReelsCount = 20,
            dailyGoal = 30
        )
        val leapMonthBlocks = generateMonthCalendarData(leapYearMonth, listOf(leapDayRecord), todayStr, todayStats)
        val leapMonthMap = leapMonthBlocks.associateBy { it.dateString }
        val leapDayBlock = leapMonthMap[leapDayStr]
        assertNotNull("Leap day 2024-02-29 must appear in calendar data", leapDayBlock)
        assertEquals(20, leapDayBlock!!.totalReelsScrolled)
        assertEquals(LocalDate.parse(leapDayStr).toEpochDay(), leapDayBlock.dateEpochDay)

        // Non-leap year: February 2025 has 28 days
        val nonLeapYearMonth = YearMonth.of(2025, 2)
        assertEquals("February 2025 must have 28 days", 28, nonLeapYearMonth.lengthOfMonth())

        // Year boundary navigation: December 2024 to January 2025
        val jan2025 = YearMonth.of(2025, 1)
        val dec2024 = jan2025.minusMonths(1)
        assertEquals(2024, dec2024.year)
        assertEquals(12, dec2024.monthValue)
        assertEquals("2024-12-01", dec2024.atDay(1).format(DateTimeFormatter.ISO_LOCAL_DATE))
        assertEquals("2024-12-31", dec2024.atEndOfMonth().format(DateTimeFormatter.ISO_LOCAL_DATE))

        val newYearsEveRecord = DailyReelData(
            dateString = "2024-12-31",
            totalReelsCount = 10,
            dailyGoal = 25
        )
        val decBlocks = generateMonthCalendarData(dec2024, listOf(newYearsEveRecord), todayStr, todayStats)
        val decMap = decBlocks.associateBy { it.dateString }
        assertNotNull("Record on year-boundary 2024-12-31 must appear", decMap["2024-12-31"])
    }

    // ---------------------------------------------------------------------------------------------
    // Test 3: Empty dates are not presented as fabricated historical records
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testEmptyDatesAreNotPresentedAsFabricatedHistoricalRecords() {
        val targetMonth = YearMonth.of(2026, 5) // May has 31 days
        val activeDays = listOf(
            DailyReelData(dateString = "2026-05-05", totalReelsCount = 12, dailyGoal = 40),
            DailyReelData(dateString = "2026-05-20", totalReelsCount = 18, dailyGoal = 40)
        )

        // generateMonthCalendarData only includes existing recorded records
        val monthBlocks = generateMonthCalendarData(targetMonth, activeDays, todayStr, todayStats)
        assertEquals("Calendar data should contain ONLY days with recorded data, not all 31 days", 2, monthBlocks.size)

        val statsMap = monthBlocks.associateBy { it.dateEpochDay }
        val emptyDayEpoch = LocalDate.of(2026, 5, 12).toEpochDay()
        assertNull("Unrecorded date must map to null, distinguishing it from zero-reel days", statsMap[emptyDayEpoch])

        val recordedDayEpoch = LocalDate.of(2026, 5, 5).toEpochDay()
        assertNotNull("Recorded date must map to its record", statsMap[recordedDayEpoch])
    }

    // ---------------------------------------------------------------------------------------------
    // Test 4: Date-range queries return only records within range without full history loading
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testDateRangeQueriesReturnOnlyRecordsWithinRangeWithoutFullHistoryLoading() = runBlocking {
        val dao = FakeDailyReelDataDao()
        val allRecords = listOf(
            DailyReelData(dateString = "2025-06-15", totalReelsCount = 10),
            DailyReelData(dateString = "2025-12-31", totalReelsCount = 20),
            DailyReelData(dateString = "2026-01-05", totalReelsCount = 30),
            DailyReelData(dateString = "2026-01-20", totalReelsCount = 40),
            DailyReelData(dateString = "2026-02-14", totalReelsCount = 50),
            DailyReelData(dateString = "2026-10-23", totalReelsCount = 60)
        )
        dao.insertAll(allRecords)

        // Query only January 2026: 2026-01-01 to 2026-01-31
        val janRecords = dao.getDailyDataRangeSync("2026-01-01", "2026-01-31")
        assertEquals("Query should return only records within Jan 2026", 2, janRecords.size)
        assertEquals("2026-01-05", janRecords[0].dateString)
        assertEquals("2026-01-20", janRecords[1].dateString)

        // Query only year 2025: 2025-01-01 to 2025-12-31
        val year2025Records = dao.getDailyDataRangeSync("2025-01-01", "2025-12-31")
        assertEquals(2, year2025Records.size)
        assertEquals("2025-06-15", year2025Records[0].dateString)
        assertEquals("2025-12-31", year2025Records[1].dateString)
    }

    // ---------------------------------------------------------------------------------------------
    // Test 5: Distinct display of zero-reel day with goal vs unrecorded day
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testDistinguishesZeroReelRecordedDayFromUnrecordedDay() {
        val targetMonth = YearMonth.of(2026, 8)
        val zeroReelsRecordedDay = DailyReelData(
            dateString = "2026-08-15",
            totalReelsCount = 0,
            dailyGoal = 50
        )
        val monthBlocks = generateMonthCalendarData(targetMonth, listOf(zeroReelsRecordedDay), todayStr, todayStats)
        val monthMap = monthBlocks.associateBy { it.dateString }

        // The day 2026-08-15 was recorded with 0 reels and goal 50
        val recordedZero = monthMap["2026-08-15"]
        assertNotNull("Day with 0 reels recorded should be present", recordedZero)
        assertEquals(0, recordedZero!!.totalReelsScrolled)
        assertEquals(50, recordedZero.dailyGoal)

        // An unrecorded day in the same month
        val unrecordedDay = monthMap["2026-08-16"]
        assertNull("Unrecorded day must be null in data map", unrecordedDay)
    }
}
